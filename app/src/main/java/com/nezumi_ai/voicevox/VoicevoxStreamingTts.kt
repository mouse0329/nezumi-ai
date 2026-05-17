package com.nezumi_ai.voicevox

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * VoiceVox 用ストリーミング TTS ヘルパー
 * - テキストを句読点（。.,!?、など）で区切って順次合成
 * - 合成した音声をある程度バッファリングして AudioTrack で滑らかに再生する
 *
 * 使い方:
 * val helper = VoicevoxStreamingTts(voicevoxManager)
 * helper.speakStreaming(scope, text)
 * helper.stop()
 */
class VoicevoxStreamingTts(
    private val voicevoxManager: VoicevoxManager
) {
    companion object {
        private const val TAG = "VoicevoxStreamingTts"
        private const val DEFAULT_BUFFER_MS = 400
    }

    @Volatile
    private var playJob: Job? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    fun stop() {
        playJob?.cancel()
        playJob = null
        audioTrack?.let { track ->
            try {
                track.pause()
            } catch (_: Exception) {}
            try {
                track.flush()
            } catch (_: Exception) {}
            try {
                track.stop()
            } catch (_: Exception) {}
            try {
                track.release()
            } catch (_: Exception) {}
        }
        audioTrack = null
    }

    fun speakStreaming(
        scope: CoroutineScope,
        text: String,
        onChunkStart: ((String) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ): Job {
        stop()

        playJob = scope.launch(Dispatchers.Default) {
            var track: AudioTrack? = null
            val queue = ArrayDeque<ByteArray>()
            var currentSampleRate = 24000
            var currentChannels = 1
            val bytesPerSample = 2
            var bufferedBytes = 0
            var hasStarted = false

            try {
                val chunks = splitToChunks(text)
                if (chunks.isEmpty()) {
                    onComplete?.invoke()
                    return@launch
                }

                val startThresholdBytes = (currentSampleRate * currentChannels * bytesPerSample * DEFAULT_BUFFER_MS / 1000)
                var totalBufferedDurationMs = 0L

                for (chunk in chunks) {
                    if (!isActive) break
                    onChunkStart?.invoke(chunk)

                    val audioData = withContext(Dispatchers.IO) {
                        voicevoxManager.synthesize(chunk)
                    }

                    if (audioData == null || audioData.isEmpty()) {
                        Log.w(TAG, "Empty audio for chunk: $chunk")
                        continue
                    }

                    val wavData = parseWav(audioData)
                    if (wavData == null) {
                        Log.w(TAG, "Invalid WAV data from VoiceVox for chunk: $chunk")
                        continue
                    }

                    if (track == null) {
                        track = createAudioTrack(wavData.sampleRate, wavData.channels)
                        audioTrack = track
                        currentSampleRate = wavData.sampleRate
                        currentChannels = wavData.channels
                    }

                    queue.addLast(wavData.pcmData)
                    bufferedBytes += wavData.pcmData.size
                    totalBufferedDurationMs += wavData.pcmData.size.toLong() / (currentChannels * bytesPerSample) * 1000 / currentSampleRate

                    if (!hasStarted && bufferedBytes >= startThresholdBytes) {
                        track?.play()
                        hasStarted = true
                    }

                    while (hasStarted && queue.isNotEmpty() && isActive) {
                        val chunkBytes = queue.removeFirst()
                        writeAudioTrack(track, chunkBytes)
                        bufferedBytes -= chunkBytes.size
                    }
                }

                if (!isActive) return@launch

                if (track != null && !hasStarted) {
                    track.play()
                    hasStarted = true
                    while (queue.isNotEmpty() && isActive) {
                        val chunkBytes = queue.removeFirst()
                        writeAudioTrack(track, chunkBytes)
                        bufferedBytes -= chunkBytes.size
                    }
                }

                if (track != null && hasStarted) {
                    val finalDurationMs = totalBufferedDurationMs.coerceAtLeast(DEFAULT_BUFFER_MS.toLong())
                    val waitMs = finalDurationMs + 200
                    kotlinx.coroutines.delay(waitMs)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Streaming TTS failed", e)
                onError?.invoke(e)
            } finally {
                try {
                    track?.pause()
                } catch (_: Exception) {}
                try {
                    track?.flush()
                } catch (_: Exception) {}
                try {
                    track?.stop()
                } catch (_: Exception) {}
                try {
                    track?.release()
                } catch (_: Exception) {}
                if (audioTrack === track) audioTrack = null
                onComplete?.invoke()
            }
        }

        return playJob!!
    }

    private fun writeAudioTrack(track: AudioTrack?, data: ByteArray) {
        if (track == null) return
        var offset = 0
        while (offset < data.size) {
            val written = try {
                track.write(data, offset, data.size - offset)
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack write failed", e)
                break
            }
            if (written <= 0) break
            offset += written
        }
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int): AudioTrack {
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private data class WavData(
        val sampleRate: Int,
        val channels: Int,
        val pcmData: ByteArray
    )

    private fun parseWav(bytes: ByteArray): WavData? {
        if (bytes.size < 44) return null
        val stream = ByteArrayInputStream(bytes)
        val header = ByteArray(12)
        if (stream.read(header) != 12) return null
        if (!header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) return null
        if (!header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) return null

        var sampleRate = 24000
        var channels = 1
        var dataOffset = -1
        var dataSize = -1

        while (stream.available() >= 8) {
            val chunkHeader = ByteArray(8)
            if (stream.read(chunkHeader) != 8) break
            val chunkId = String(chunkHeader.copyOfRange(0, 4), Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

            if (chunkId == "fmt ") {
                val fmtBytes = ByteArray(chunkSize)
                if (stream.read(fmtBytes) != chunkSize) return null
                val fmtBuffer = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                fmtBuffer.position(2)
                channels = fmtBuffer.short.toInt()
                sampleRate = fmtBuffer.int
            } else if (chunkId == "data") {
                dataOffset = bytes.size - stream.available()
                dataSize = chunkSize.coerceAtMost(stream.available())
                break
            } else {
                stream.skip(chunkSize.toLong())
            }
        }

        if (dataOffset < 0 || dataSize <= 0) return null
        val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        return WavData(sampleRate, channels, pcm)
    }

    private fun splitToChunks(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val regex = Regex("(?<=[。！？!?.,，、\u3000])\\s*")
        return text.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
