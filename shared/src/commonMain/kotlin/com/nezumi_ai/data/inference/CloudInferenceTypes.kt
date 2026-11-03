package com.nezumi_ai.data.inference

/** クラウドエンジンが参照する設定 (app 側 InferenceConfig の必要フィールドのみ)。 */
data class CloudInferenceParams(
    /** ローカル推論の最大トークン数。null の場合はクラウド API の既定値を使用する。 */
    val maxTokens: Int? = null,
    val temperature: Float,
    val topP: Float,
    val customStopTokens: List<String>,
    val enableToolCalling: Boolean,
    /** 1 応答あたりのツール周回上限。enableToolCalling=false 時はエンジン側で 1。 */
    val maxToolRounds: Int = 5,
    val contextWindow: Int,
    /** チャットの思考 ON/OFF。クラウドはサービス別パラメータへマップする。 */
    val enableThinking: Boolean = false,
    /** low / medium / high。非対応サービスでは無視する。 */
    val thinkingEffort: String = "low"
) {
    companion object {
        /** Claude API は max_tokens が必須のため、クラウド既定値として使用する。 */
        const val DEFAULT_CLAUDE_MAX_TOKENS = 8192
    }
}

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
