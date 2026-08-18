package com.nezumi_ai.data.inference.cloud

/**
 * クラウドプロバイダ経由のモデルを表現するモデル ID の命名規約 (commonMain 版)。
 *
 * 形式: `cloud:{providerId}:{modelName}`
 * 後方互換としてレガシー識別子 `gemini_api` / `claude_api` を各社デフォルトへ
 * マッピングする。PresetConstants への依存を避けるため同値の定数をここに持つ。
 */
object CloudModelId {

    private const val PREFIX = "cloud:"

    /** レガシー識別子 (PresetConstants.MODEL_CLAUDE_API / MODEL_GEMINI_API と同値)。 */
    private const val LEGACY_CLAUDE_API = "claude_api"
    private const val LEGACY_GEMINI_API = "gemini_api"

    /** 各種文字列 modelId がクラウド系かを判定する。 */
    fun isCloud(modelId: String): Boolean {
        val trimmed = modelId.trim()
        if (trimmed.startsWith(PREFIX)) return true
        return trimmed == LEGACY_CLAUDE_API || trimmed == LEGACY_GEMINI_API
    }

    /** `cloud:provider:model` を組み立てる。 */
    fun build(provider: CloudApiKeyStore.Provider, modelName: String): String {
        val cleanedName = modelName.trim()
        return "$PREFIX${provider.id}:$cleanedName"
    }

    data class Parsed(
        val provider: CloudApiKeyStore.Provider,
        val modelName: String
    )

    /** 文字列 modelId を [Parsed] に分解する。認識不能・未対応なら null。 */
    fun parse(modelId: String): Parsed? {
        val trimmed = modelId.trim()

        when (trimmed) {
            LEGACY_CLAUDE_API ->
                return Parsed(CloudApiKeyStore.Provider.CLAUDE, "claude-3-5-haiku-latest")
            LEGACY_GEMINI_API ->
                return Parsed(CloudApiKeyStore.Provider.GEMINI, "gemini-2.5-flash")
        }

        if (!trimmed.startsWith(PREFIX)) return null
        val body = trimmed.removePrefix(PREFIX)
        val parts = body.split(":", limit = 3)
        if (parts.size < 2) return null

        val providerId = parts[0]
        val modelName = when (parts.size) {
            2 -> parts[1]
            else -> parts.drop(1).joinToString(":")
        }
        if (modelName.isBlank()) return null

        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return null
        return Parsed(provider, modelName)
    }

    /** 一覧・ラベル表示用の短縮ラベルを返す。 */
    fun displayLabel(modelId: String): String {
        val parsed = parse(modelId) ?: return modelId
        val providerLabel = when (parsed.provider) {
            CloudApiKeyStore.Provider.CLAUDE -> "Claude"
            CloudApiKeyStore.Provider.GEMINI -> "Gemini"
            CloudApiKeyStore.Provider.OPENAI -> "OpenAI"
            CloudApiKeyStore.Provider.OLLAMA_LOCAL -> "Ollama (Local)"
            CloudApiKeyStore.Provider.OLLAMA_REMOTE -> "Ollama (Cloud)"
            CloudApiKeyStore.Provider.LM_STUDIO -> "LM Studio"
        }
        return "$providerLabel · ${parsed.modelName}"
    }
}
