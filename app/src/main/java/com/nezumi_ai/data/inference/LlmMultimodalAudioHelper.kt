package com.nezumi_ai.data.inference

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * LiteRT-LM の [com.google.ai.edge.litertlm.Content.AudioBytes] 向けに、
 * **mono 16-bit PCM の WAV** へ変換する。
 */
object LlmMultimodalAudioHelper {
    private const val TAG = "LlmMultimodalAudio"
    const val TARGET_SAMPLE_RATE = 16_000

    /**
     * @return 16kHz / mono / 16-bit PCM の WAV バイト列。失敗時は null。
     */
    fun toMono16Bit16kHzWav(context: Context, audioBytes: ByteArray): ByteArray? {
        if (audioBytes.isEmpty()) return null
        unwrapWavToPcm(audioBytes)?.let { (rate, monoShorts) ->
            val resampled = resampleLinear(monoShorts, rate, TARGET_SAMPLE_RATE)
            return pcm16MonoToWav(resampled, TARGET_SAMPLE_RATE)
        }
        return decodeFileToMono16kWav(context, audioBytes)
    }

    /**
     * fmt/data を走査し、PCM WAV なら 16-bit に正規化して mono + 短配列に落とす。
     */
    private fun unwrapWavToPcm(bytes: ByteArray): Pair<Int, ShortArray>? {
        if (bytes.size < 12) return null
        if (!isRiff(bytes, 0) || !isWave(bytes, 8)) return null
        var offset = 12
        var audioFormat = 0
        var numChannels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcmData: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val chunkId = chunkId(bytes, offset)
            val chunkSize = readLeInt(bytes, offset + 4)
            val dataStart = offset + 8
            val next = dataStart + align2(chunkSize)
            if (dataStart > bytes.size) break
            val end = (dataStart + chunkSize).coerceAtMost(bytes.size)
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize >= 16 && end >= dataStart + 16) {
                        audioFormat = readLeShort(bytes, dataStart).toInt() and 0xffff
                        numChannels = readLeShort(bytes, dataStart + 2).toInt() and 0xffff
                        sampleRate = readLeInt(bytes, dataStart + 4)
                        bitsPerSample = readLeShort(bytes, dataStart + 14).toInt() and 0xffff
                    }
                }
                "data" -> {
                    pcmData = bytes.copyOfRange(dataStart, end)
                }
            }
            offset = next
        }
        val data = pcmData ?: return null
        if (audioFormat != 1) return null
        if (bitsPerSample != 16) return null
        val shorts = byteArrayToLeShorts(data) ?: return null
        val mono = if (numChannels <= 1) {
            shorts
        } else {
            downmixToMono(shorts, numChannels)
        }
        return sampleRate to mono
    }

    private fun decodeFileToMono16kWav(context: Context, audioBytes: ByteArray): ByteArray? {
        val inFile = File(context.cacheDir, "llm_audio_in_${System.nanoTime()}.bin")
        return try {
            inFile.outputStream().use { it.write(audioBytes) }
            val extractor = MediaExtractor()
            extractor.setDataSource(inFile.absolutePath)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) {
                extractor.release()
                Log.w(TAG, "No audio track in media")
                return null
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val inSampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44_100)
            val inChannels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val pcmOut = ByteArrayOutputStream()
            var inputEos = false
            var outputEos = false
            val info = MediaCodec.BufferInfo()
            var outFormat: MediaFormat? = null
            while (!outputEos) {
                if (!inputEos) {
                    val inIx = codec.dequeueInputBuffer(25_000)
                    if (inIx >= 0) {
                        val buf = codec.getInputBuffer(inIx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIx = codec.dequeueOutputBuffer(info, 25_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (outIx >= 0) {
                        val ob = codec.getOutputBuffer(outIx)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                }
            }
            codec.stop()
            codec.release()
            extractor.release()
            val rate = outFormat?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }
                ?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: inSampleRate
            val channels = outFormat?.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
                ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: inChannels
            val raw = pcmOut.toByteArray()
            val shorts = byteArrayToLeShorts(raw) ?: return null
            val mono = if (channels <= 1) shorts else downmixToMono(shorts, channels)
            val resampled = resampleLinear(mono, rate, TARGET_SAMPLE_RATE)
            pcm16MonoToWav(resampled, TARGET_SAMPLE_RATE)
        } catch (e: Exception) {
            Log.e(TAG, "decodeFileToMono16kWav failed", e)
            null
        } finally {
            runCatching { if (inFile.exists()) inFile.delete() }
        }
    }

    private fun pcm16MonoToWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val pcmBytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) pcmBytes.putShort(s)
        val data = pcmBytes.array()
        val bitsPerSample = 16
        val numChannels = 1
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = data.size
        val chunkSize = 36 + dataSize
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(chunkSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(numChannels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        buf.put(data)
        return buf.array()
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0L
            for (c in 0 until channels) {
                acc += interleaved[f * channels + c]
            }
            out[f] = (acc / channels)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outLen = (input.size * toRate.toDouble() / fromRate).roundToInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val srcPos = i * fromRate.toDouble() / toRate
            val i0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val i1 = (i0 + 1).coerceIn(0, input.lastIndex)
            val t = (srcPos - i0).toFloat()
            val v = input[i0] * (1 - t) + input[i1] * t
            out[i] = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun byteArrayToLeShorts(pcm: ByteArray): ShortArray? {
        if (pcm.size % 2 != 0) return null
        val n = pcm.size / 2
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(n)
        bb.asShortBuffer().get(out)
        return out
    }

    private fun isRiff(bytes: ByteArray, offset: Int): Boolean =
        bytes[offset] == 'R'.code.toByte() &&
            bytes[offset + 1] == 'I'.code.toByte() &&
            bytes[offset + 2] == 'F'.code.toByte() &&
            bytes[offset + 3] == 'F'.code.toByte()

    private fun isWave(bytes: ByteArray, offset: Int): Boolean =
        bytes[offset] == 'W'.code.toByte() &&
            bytes[offset + 1] == 'A'.code.toByte() &&
            bytes[offset + 2] == 'V'.code.toByte() &&
            bytes[offset + 3] == 'E'.code.toByte()

    private fun chunkId(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readLeShort(bytes: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private fun align2(size: Int): Int = size + (size and 1)
}
