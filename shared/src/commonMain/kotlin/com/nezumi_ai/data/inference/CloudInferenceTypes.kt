package com.nezumi_ai.data.inference

/** クラウドエンジンが参照する設定 (app 側 InferenceConfig の必要フィールドのみ)。 */
data class CloudInferenceParams(
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val customStopTokens: List<String>,
    val enableToolCalling: Boolean,
    val contextWindow: Int
)

/** LiteRT ToolCall のプラットフォーム非依存版。 */
data class ParsedToolCall(val name: String, val arguments: Map<String, Any?>)

/** app 側 ToolExecutionResult と同形の非依存版。 */
data class CloudToolExecutionResult(
    val success: Boolean,
    val payload: Map<String, Any?>,
    val modelPayload: Map<String, Any?>? = null
) {
    val payloadForModel: Map<String, Any?> get() = modelPayload ?: payload
}

/** ツール実行ポート (DB/ONNX 依存の実体はプラットフォーム側)。 */
interface CloudToolExecutor {
    suspend fun execute(call: ParsedToolCall): CloudToolExecutionResult
}

/** モデル個別設定 (APIキー/BaseURL) のオーバーライド解決ポート。 */
interface CloudModelConfigProvider {
    fun hasOverride(modelId: String): Boolean
    fun resolveApiKey(modelId: String, providerId: String): String
    fun resolveBaseUrl(modelId: String, providerId: String): String
    fun isConfigured(modelId: String): Boolean
}
