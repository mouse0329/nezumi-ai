package com.nezumi_ai.data.inference

/**
 * クラウド推論エンジンが commonMain で使うための設定型。
 *
 * app 側 [InferenceConfig] (data/inference/InferenceConfig.kt) のうち、
 * クラウドエンジンが実際に参照するフィールドだけを切り出したもの。
 * Android 依存 (BuildConfig / Runtime) を持つ normalized() 等はここには持ち込まない。
 */
data class CloudInferenceParams(
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val customStopTokens: List<String>,
    val enableToolCalling: Boolean,
    val contextWindow: Int
)

/** LiteRT の ToolCall に相当する、プラットフォーム非依存のツール呼び出し表現。 */
data class ParsedToolCall(
    val name: String,
    val arguments: Map<String, Any?>
)

/** app 側 ToolExecutionResult と同形の、プラットフォーム非依存の実行結果。 */
data class CloudToolExecutionResult(
    val success: Boolean,
    val payload: Map<String, Any?>,
    val modelPayload: Map<String, Any?>? = null
) {
    /** モデルに実際に送るペイロード。[modelPayload] 未指定なら [payload] (後方互換)。 */
    val payloadForModel: Map<String, Any?> get() = modelPayload ?: payload
}

/**
 * クラウドエンジンからツール実行を委譲するポート。
 * 実体 (DB / ONNX 埋め込み等に依存) はプラットフォーム側に置く。
 * フェーズ2 の SQLDelight 移行を待たずに DB 直接参照を隔離するための境界。
 */
interface CloudToolExecutor {
    suspend fun execute(call: ParsedToolCall): CloudToolExecutionResult
}

/** モデル個別設定 (API キー / Base URL) のオーバーライド解決ポート。 */
interface CloudModelConfigProvider {
    /** モデル ID が個別設定を持つか。 */
    fun hasOverride(modelId: String): Boolean

    /** 個別設定優先で API キーを解決。なければプロバイダ共通値。 */
    fun resolveApiKey(modelId: String, providerId: String): String

    /** 個別設定優先で Base URL を解決。なければプロバイダ共通値。 */
    fun resolveBaseUrl(modelId: String, providerId: String): String

    /** モデルが「利用可能に構成済み」か。個別設定優先で判定する。 */
    fun isConfigured(modelId: String): Boolean
}
