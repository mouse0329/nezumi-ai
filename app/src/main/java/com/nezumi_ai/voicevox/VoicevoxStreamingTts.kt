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

/**
 * VoiceVox 用ストリーミング TTS ヘルパー
 *
 * 動作モデル (この改修で変更した点):
 * - テキストを句読点で区切って「文単位」で合成するのは従来通り
 * - ただし再生は「全ての文の合成が終わってからまとめて開始する」
 *   これにより、生成に時間がかかった場合の再生ブツ切れや、合成中の音飛びを回避する
 * - 例外発生・キャンセル・正常終了、いずれの経路でも必ず onComplete を呼ぶ
 *   (これまで onComplete が呼ばれない経路があり、UI 側でスピナーが永遠に回っていた)
 */
class VoicevoxStreamingTts(
    private val voicevoxManager: VoicevoxManager
) {
    companion object {
        private const val TAG = "VoicevoxStreamingTts"
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
            var completeInvoked = false

            fun invokeCompleteOnce() {
                if (!completeInvoked) {
                    completeInvoked = true
                    try {
                        onComplete?.invoke()
                    } catch (e: Throwable) {
                        Log.w(TAG, "onComplete threw", e)
                    }
                }
            }

            try {
                val chunks = splitToChunks(text)
                if (chunks.isEmpty()) {
                    // 空文字などの即時完了。onComplete は finally で呼ぶ
                    return@launch
                }

                // フェーズ1: 全チャンクを順に合成し、PCM だけをバッファに溜める。
                // 再生はまだ始めない。
                val pcmChunks = ArrayList<ByteArray>(chunks.size)
                var sampleRate = 24000
                var channels = 1

                for (chunk in chunks) {
                    if (!isActive) return@launch
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

                    // 最初に確定した format を使う (全チャンクで同一想定)
                    if (pcmChunks.isEmpty()) {
                        sampleRate = wavData.sampleRate
                        channels = wavData.channels
                    }
                    pcmChunks.add(wavData.pcmData)
                }

                if (!isActive) return@launch
                if (pcmChunks.isEmpty()) return@launch

                // フェーズ2: まとめて再生する。
                val bytesPerSample = 2
                track = createAudioTrack(sampleRate, channels)
                audioTrack = track
                track.play()

                var totalBytes = 0L
                for (data in pcmChunks) {
                    if (!isActive) return@launch
                    writeAudioTrack(track, data)
                    totalBytes += data.size
                }

                // 全書き込み後、AudioTrack の実際の再生位置をポーリングして完了を厳密に待つ。
                //
                // 以前は `delay(totalDurationMs + 200)` だけで待っていたが、
                //   ・ write 完了を基準にしていたためカーネルバッファ内の未再生 PCM を見ていない
                //   ・ 結果として「声は鍵れているのに Job だけ先に終了 or 逆に鍵れてもスピナーが回り続ける」が発生していた。
                // getPlaybackHeadPosition() はハードウェアが実際に何サンプル鍵らしたかを返すので、これを監視すれば一致する。
                val totalFrames = totalBytes / (channels * bytesPerSample)
                var lastHead = 0
                var stallLoops = 0
                while (isActive) {
                    val head = try {
                        track.playbackHeadPosition
                    } catch (_: Exception) {
                        break
                    }
                    if (head >= totalFrames) break
                    // 鍵り切ったのにハードが進まないとき (underrun やクリップ ズレ後のタイミング) は
                    // 最大 1 秒で打ち切る。stallLoops は 20 回 × 50ms で 1 秒。
                    if (head == lastHead) {
                        stallLoops++
                        if (stallLoops >= 20) break
                    } else {
                        stallLoops = 0
                        lastHead = head
                    }
                    kotlinx.coroutines.delay(50)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Streaming TTS failed", e)
                try {
                    onError?.invoke(e)
                } catch (inner: Throwable) {
                    Log.w(TAG, "onError threw", inner)
                }
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
                // 正常終了・例外・キャンセルの全経路で onComplete を必ず一度だけ呼ぶ
                invokeCompleteOnce()
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
