package com.nezumi_ai.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

/**
 * 動画URIから
 *   - 最大30秒 / 最大30枚 (1fps 相当) のフレーム Bitmap
 *   - 音声トラック(存在すれば) を PCM 16-bit mono WAV(単チャンネル、元サンプルレート保持) に変換
 * を取り出すユーティリティ。
 *
 * LiteRT-LM Kotlin API (0.13.x) の Content は Text / ImageBytes / ImageFile /
 * AudioBytes / AudioFile のみで、VideoBytes 相当は存在しない。
 * Gemma 4 のモデルカードも "process videos as frames" と明記しているため、
 * "動画 → 画像列 + 音声" に平坦化して既存のマルチモーダルパイプラインに流し込む方式を採っている。
 *
 * 生成される音声は [LlmMultimodalAudioHelper.toMono16Bit16kHzWav] で更に 16kHz へ再正規化される。
 */
object VideoFrameExtractor {
    private const val TAG = "VideoFrameExtractor"

    /** 動画の最大許容秒数。これを超える場合は先頭からトリムして扱う。 */
    const val MAX_VIDEO_DURATION_MS: Long = 30_000L

    /** サンプリング FPS。 1fps 相当 = 1 秒に 1 枚。 */
    const val SAMPLE_FPS: Int = 1

    /** ここでの上限枚数。 30 秒 × 1fps = 30 枚。 */
    const val MAX_FRAMES: Int = 30

    data class Extracted(
        val frames: List<Bitmap>,
        /** 抽出できた音声 WAV(16-bit PCM mono) の一時ファイル URI。null なら音声なし。 */
        val audioUriString: String?,
        /** 実際に処理対象とした動画長 [ms]。 */
        val effectiveDurationMs: Long
    )

    /**
     * 動画 URI を解析し、フレーム + 音声を取り出す。呼び出し元は IO 系ディスパッチャで呼ぶこと。
     */
    fun extract(context: Context, uri: Uri): Extracted? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val rawDurationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (rawDurationMs <= 0L) {
                Log.w(TAG, "Duration unknown or zero for $uri")
                return null
            }
            val effectiveMs = min(rawDurationMs, MAX_VIDEO_DURATION_MS)
            val frameCount = computeFrameCount(effectiveMs)
            Log.i(
                TAG,
                "Extracting frames: raw=${rawDurationMs}ms effective=${effectiveMs}ms count=$frameCount fps=$SAMPLE_FPS"
            )
            val frames = ArrayList<Bitmap>(frameCount)
            for (i in 0 until frameCount) {
                // i=0 は 0s、以降 1s ごと。 [ms] → [us]
                val timeUs = i * 1_000_000L
                val bmp = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        // OPTION_CLOSEST_SYNC は速いが飛びやすいので、精度重視で CLOSEST を使う。
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            1024,
                            1024
                        )
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "getFrameAtTime failed at ${timeUs}us", t)
                    null
                }
                if (bmp != null) {
                    frames.add(bmp)
                } else {
                    Log.w(TAG, "Null frame at index=$i time=${timeUs}us; stop early")
                    break
                }
            }
            val audio = runCatching { extractAudioAsWav(context, uri, effectiveMs) }
                .onFailure { Log.w(TAG, "Audio extraction failed", it) }
                .getOrNull()
            Extracted(
                frames = frames,
                audioUriString = audio,
                effectiveDurationMs = effectiveMs
            )
        } catch (t: Throwable) {
            Log.e(TAG, "extract() failed for $uri", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun computeFrameCount(effectiveMs: Long): Int {
        // 例) 30_000ms → 30 枚、  5_500ms → 6 枚 (0s,1s,2s,3s,4s,5s)
        val approx = ((effectiveMs + 999L) / 1000L).toInt() * SAMPLE_FPS
        return approx.coerceIn(1, MAX_FRAMES)
    }

    /**
     * MediaExtractor + MediaCodec で音声トラックを PCM に復号し、
     * mono 16-bit WAV としてアプリの cache に書き出し、file:// URI 文字列を返す。
     */
    private fun extractAudioAsWav(context: Context, uri: Uri, maxDurationMs: Long): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (t: Throwable) {
            Log.w(TAG, "MediaExtractor.setDataSource failed", t)
            runCatching { extractor.release() }
            return null
        }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            Log.i(TAG, "No audio track in video")
            return null
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (t: Throwable) {
            Log.w(TAG, "createDecoderByType failed for $mime", t)
            extractor.release()
            return null
        }
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmBytes = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L
        val maxDurationUs = maxDurationMs * 1000L
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            if (presentationTimeUs > maxDurationUs) {
                                // 30 秒を超えたら以降は捨てる。
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        pcmBytes.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Decode loop error", t)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        val stereoOrMono16 = pcmBytes.toByteArray()
        if (stereoOrMono16.isEmpty()) {
            Log.w(TAG, "Decoded PCM is empty")
            return null
        }
        val mono16 = if (channels <= 1) stereoOrMono16 else downmixInterleavedTo16Mono(stereoOrMono16, channels)
        val wav = pcm16MonoToWav(mono16, sampleRate)

        // cache に書き出して file:// URI を返す (MessageMediaStore.toUri / MessageMediaStore.persistUriIfNeeded と両立)
        return try {
            val dir = File(context.cacheDir, "video_audio").apply { mkdirs() }
            val out = File(dir, "va_${UUID.randomUUID()}.wav")
            FileOutputStream(out).use { it.write(wav) }
            Uri.fromFile(out).toString()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist extracted audio", t)
            null
        }
    }

    /** インターリーブされた 16-bit PCM を mono に平均化する。 */
    private fun downmixInterleavedTo16Mono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return pcm
        val frameBytes = 2 * channels
        val frames = pcm.size / frameBytes
        val out = ByteArray(frames * 2)
        var oi = 0
        var pi = 0
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val lo = pcm[pi].toInt() and 0xff
                val hi = pcm[pi + 1].toInt() // signed
                acc += (hi shl 8) or lo
                pi += 2
            }
            val avg = (acc / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[oi] = (avg and 0xff).toByte()
            out[oi + 1] = ((avg shr 8) and 0xff).toByte()
            oi += 2
        }
        return out
    }

    /** 16-bit mono PCM を WAV バイト列へ。 */
    private fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val totalDataLen = pcm.size + 36
        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)          // PCM
        writeShort(header, 22, 1)          // mono
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, 2)          // block align
        writeShort(header, 34, 16)         // bits per sample
        // data chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, pcm.size)
        val out = ByteArray(header.size + pcm.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(pcm, 0, out, header.size, pcm.size)
        return out
    }

    private fun writeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xff).toByte()
        buf[off + 1] = ((v shr 8) and 0xff).toByte()
        buf[off + 2] = ((v shr 16) and 0xff).toByte()
        buf[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun writeShort(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xff).toByte()
        buf[off + 1] = ((v shr 8) and 0xff).toByte()
    }
}
