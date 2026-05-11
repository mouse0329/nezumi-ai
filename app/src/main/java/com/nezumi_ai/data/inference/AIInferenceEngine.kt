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
     * @param prompt ユーザープロンプト（コンテキスト込みの全文）
     * @param config 温度・トップK・シンキング等
     * @return 本文デルタと [InferenceStreamProtocol] の think/final チャンクの Flow
     */
    suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String>
    
    /**
     * マルチモーダル推論を実行
     */
    suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        config: InferenceConfig
    ): Flow<String> = inference(sessionId, prompt, config)
    
    /**
     * モデルをアンロード
     */
    suspend fun unloadModel(): Result<Unit>

    /**
     * 推論をキャンセル（Gallery 方式：cancelProcess() のみ）
     */
    suspend fun cancelInference()
    
    /**
     * 推論エンジンの利用可能性をチェック
     */
    suspend fun isAvailable(): Boolean
}
