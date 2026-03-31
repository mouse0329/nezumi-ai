package com.nezumi_ai.data.inference

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
     * モデルをアンロード
     */
    suspend fun unloadModel(): Result<Unit>
    
    /**
     * 推論エンジンの利用可能性をチェック
     */
    suspend fun isAvailable(): Boolean
}
