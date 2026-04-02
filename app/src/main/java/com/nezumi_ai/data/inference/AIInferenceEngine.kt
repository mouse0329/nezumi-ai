package com.nezumi_ai.data.inference

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * AI推論エンジンの基本インターフェース
 */
interface AIInferenceEngine {
    
    /**
     * モデルをロード
     */
    suspend fun loadModel(modelName: String, config: InferenceConfig = InferenceConfig()): Result<Unit>
    
    /**
     * 推論を実行
     * @param sessionId チャットセッションID
     * @param prompt ユーザープロンプト
     * @param temperature 温度パラメータ (0.0-2.0)
     * @return AIレスポンスのFlow
     */
    suspend fun inference(
        sessionId: Long,
        prompt: String,
        temperature: Float = 0.7f
    ): Flow<String>
    
    /**
     * マルチモーダル推論を実行
     * @param sessionId チャットセッションID
     * @param prompt ユーザープロンプト
     * @param images 画像のBitmapリスト
     * @param audioClips 音声のByteArrayリスト
     * @param temperature 温度パラメータ (0.0-2.0)
     * @return AIレスポンスのFlow
     */
    suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        temperature: Float = 0.7f
    ): Flow<String> = inference(sessionId, prompt, temperature)
    
    /**
     * モデルをアンロード
     */
    suspend fun unloadModel(): Result<Unit>
    
    /**
     * 推論エンジンの利用可能性をチェック
     */
    suspend fun isAvailable(): Boolean
}
