package com.nezumi_ai.data.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

/**
 * マルチモーダル AI 推論向け Audio ヘルパークラス
 *
 * - WAV 音声ファイルの読み込みと前処理
 * - 16bit/16kHz Mono への標準化
 * - ByteBuffer 直接操作による GC 圧力削減
 */
class LlmMultimodalAudioHelper {
    
    companion object {
        private const val TAG = "LlmMultimodalAudioHelper"
        
        // WAV フォーマット定数
        private const val SAMPLE_RATE_KHZ = 16_000
        private const val MONO_CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    }

    /**
     * URI から WAV 音声を読み込み、16bit/16kHz Mono に標準化
     *
     * ByteBuffer を直接操作して JVM Heap の GC 圧力を削減します。
     *
     * @param context Android Context
     * @param audioUri URI (content://, file://, など)
     * @return 標準化された WAV データ（ByteArray）、失敗時は null
     */
    fun loadAndNormalizeAudio(context: Context, audioUri: Uri): ByteArray? {
        return try {
            val inputStream = context.contentResolver.openInputStream(audioUri)
                ?: return null.also { Log.e(TAG, "Failed to open audio URI: $audioUri") }
            
            inputStream.use { stream ->
                // WAV ファイルのデコード
                val fullData = stream.readBytes()
                
                // MediaExtractor で音声デコード
                val decoder = MediaExtractor()
                val tempFile = File.createTempFile("audio_temp", ".wav", context.cacheDir)
                tempFile.outputStream().use { it.write(fullData) }
                
                decoder.setDataSource(tempFile.absolutePath)
                val audioTrackIndex = decoder.findTrackIndex()
                
                if (audioTrackIndex < 0) {
                    Log.w(TAG, "No audio track found in $audioUri")
                    tempFile.delete()
                    return null
                }
                
                decoder.selectTrack(audioTrackIndex)
                val format = decoder.getTrackFormat(audioTrackIndex)
                
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                
                Log.d(TAG, "Audio format: sampleRate=$sampleRate channels=$channels")
                
                // MediaCodec でデコード
                val mediaCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: "audio/raw")
                mediaCodec.configure(format, null, null, 0)
                mediaCodec.start()
                
                val result = decodeAudioWithMediaCodec(mediaCodec, decoder)
                
                mediaCodec.stop()
                mediaCodec.release()
                decoder.release()
                tempFile.delete()
                
                // 16kHz Mono に再サンプリング＆リサンプリング
                if (sampleRate != SAMPLE_RATE_KHZ || channels != MONO_CHANNELS) {
                    resampleAudio(result, sampleRate, channels, SAMPLE_RATE_KHZ, MONO_CHANNELS)
                } else {
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load and normalize audio", e)
            null
        }
    }

    /**
     * MediaCodec を使用して WAV ファイルをデコード
     *
     * @param codec 初期化済みの MediaCodec インスタンス
     * @param extractor 初期化済みの MediaExtractor インスタンス
     * @return デコード済み PCM データ（ByteArray）
     */
    private fun decodeAudioWithMediaCodec(
        codec: MediaCodec,
        extractor: MediaExtractor
    ): ByteArray {
        val outputBuffer = ByteBuffer.allocate(512 * 1024)  // 512KB のバッファ
        val pcmData = mutableListOf<ByteArray>()
        
        val info = MediaCodec.BufferInfo()
        var inputEOS = false
        
        while (!inputEOS) {
            // 入力バッファにデータを書き込み
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                    ?: continue
                
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEOS = true
                } else {
                    codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            
            // 出力バッファからデータを読み込み
            var outputIndex = codec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                val outBuffer = codec.getOutputBuffer(outputIndex)
                if (outBuffer != null) {
                    val pcmBytes = ByteArray(info.size)
                    outBuffer.get(pcmBytes)
                    pcmData.add(pcmBytes)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
        
        // すべての PCM データを 1 つの ByteArray に統合
        val totalSize = pcmData.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in pcmData) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        
        return result
    }

    /**
     * Audio をリサンプリング（16kHz Mono へ変換）
     *
     * ByteBuffer を直接操作して GC 圧力を削減します。
     *
     * @param pcmData 元の PCM データ
     * @param srcSampleRate 元のサンプルレート
     * @param srcChannels 元のチャンネル数
     * @param dstSampleRate 変換先サンプルレート (SAMPLE_RATE_KHZ = 16000)
     * @param dstChannels 変換先チャンネル数 (MONO_CHANNELS = 1)
     * @return リサンプリング済み PCM データ
     */
    private fun resampleAudio(
        pcmData: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int,
        dstSampleRate: Int,
        dstChannels: Int
    ): ByteArray {
        // 簡易リサンプリング: 線形補間
        val srcBuffer = ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        val ratio = dstSampleRate.toDouble() / srcSampleRate
        val dstSampleCount = (srcBuffer.capacity() / srcChannels * ratio).toInt()
        
        // Direct ByteBuffer を使用して JVM Heap を回避
        val dstBuffer = ByteBuffer.allocateDirect(dstSampleCount * dstChannels * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        for (i in 0 until dstSampleCount) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt() * srcChannels
            val nextIdx = (srcPos.toInt() + 1) * srcChannels
            
            if (nextIdx < srcBuffer.capacity()) {
                val frac = srcPos - srcPos.toInt()
                for (ch in 0 until dstChannels) {
                    val sample1 = srcBuffer[srcIdx + (ch % srcChannels)].toInt()
                    val sample2 = srcBuffer[nextIdx + (ch % srcChannels)].toInt()
                    val interpolated = (sample1 * (1 - frac) + sample2 * frac).toInt().toShort()
                    dstBuffer.put(interpolated)
                }
            } else {
                for (ch in 0 until dstChannels) {
                    dstBuffer.put(srcBuffer[srcIdx + (ch % srcChannels)])
                }
            }
        }
        
        dstBuffer.flip()
        
        // ShortBuffer を ByteArray に変換
        val byteBuffer = dstBuffer.asReadOnlyBuffer()
        val result = ByteArray(byteBuffer.limit() * 2)  // Short は 2 bytes
        for (i in 0 until byteBuffer.limit()) {
            val short = byteBuffer.get(i)
            result[i * 2] = (short.toInt() and 0xFF).toByte()
            result[i * 2 + 1] = ((short.toInt() shr 8) and 0xFF).toByte()
        }
        
        Log.d(TAG, "Audio resampled: ${srcSampleRate}Hz ${srcChannels}ch → ${dstSampleRate}Hz ${dstChannels}ch")
        
        return result
    }

    /**
     * ByteArray を Mono 16bit/16kHz WAV に変換（ファイル生成用）
     *
     * @param pcmData PCM データ
     * @param sampleRate サンプルレート
     * @param channels チャンネル数
     * @return WAV ファイルフォーマットの ByteArray
     */
    fun createWavFile(
        pcmData: ByteArray,
        sampleRate: Int = SAMPLE_RATE_KHZ,
        channels: Int = MONO_CHANNELS
    ): ByteArray {
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val blockAlign = channels * BYTES_PER_SAMPLE
        
        val headerBuffer = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        // RIFF header
        headerBuffer.put("RIFF".toByteArray())
        headerBuffer.putInt(36 + pcmData.size)  // ファイルサイズ - 8
        headerBuffer.put("WAVE".toByteArray())
        
        // fmt sub-chunk
        headerBuffer.put("fmt ".toByteArray())
        headerBuffer.putInt(16)  // Subchunk1Size
        headerBuffer.putShort(1.toShort())  // AudioFormat (1 = PCM)
        headerBuffer.putShort(channels.toShort())
        headerBuffer.putInt(sampleRate)
        headerBuffer.putInt(byteRate)
        headerBuffer.putShort(blockAlign.toShort())
        headerBuffer.putShort(BITS_PER_SAMPLE.toShort())
        
        // data sub-chunk
        headerBuffer.put("data".toByteArray())
        headerBuffer.putInt(pcmData.size)
        
        val wav = ByteArray(44 + pcmData.size)
        System.arraycopy(headerBuffer.array(), 0, wav, 0, 44)
        System.arraycopy(pcmData, 0, wav, 44, pcmData.size)
        
        return wav
    }

    /**
     * MediaExtractor から音声トラックのインデックスを取得
     */
    private fun MediaExtractor.findTrackIndex(): Int {
        for (i in 0 until trackCount) {
            val format = getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }
}
