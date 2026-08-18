package com.nezumi_ai.data.inference.cloud

/** クラウドモデル ID 命名規約 (commonMain 版)。PresetConstants 依存を避けるため同値定数を保持。 */
object CloudModelId {

    private const val PREFIX = "cloud:"
    private const val LEGACY_CLAUDE_API = "claude_api"
    private const val LEGACY_GEMINI_API = "gemini_api"

    fun isCloud(modelId: String): Boolean {
        val t = modelId.trim()
        if (t.startsWith(PREFIX)) return true
        return t == LEGACY_CLAUDE_API || t == LEGACY_GEMINI_API
    }

    fun build(provider: CloudApiKeyStore.Provider, modelName: String): String =
        "$PREFIX${provider.id}:${modelName.trim()}"

    data class Parsed(val provider: CloudApiKeyStore.Provider, val modelName: String)

    fun parse(modelId: String): Parsed? {
        val t = modelId.trim()
        when (t) {
            LEGACY_CLAUDE_API -> return Parsed(CloudApiKeyStore.Provider.CLAUDE, "claude-3-5-haiku-latest")
            LEGACY_GEMINI_API -> return Parsed(CloudApiKeyStore.Provider.GEMINI, "gemini-2.5-flash")
        }
        if (!t.startsWith(PREFIX)) return null
        val body = t.removePrefix(PREFIX)
        val parts = body.split(":", limit = 3)
        if (parts.size < 2) return null
        val providerId = parts[0]
        val modelName = when (parts.size) { 2 -> parts[1]; else -> parts.drop(1).joinToString(":") }
        if (modelName.isBlank()) return null
        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return null
        return Parsed(provider, modelName)
    }

    fun displayLabel(modelId: String): String {
        val parsed = parse(modelId) ?: return modelId
        val label = when (parsed.provider) {
            CloudApiKeyStore.Provider.CLAUDE -> "Claude"
            CloudApiKeyStore.Provider.GEMINI -> "Gemini"
            CloudApiKeyStore.Provider.OPENAI -> "OpenAI"
            CloudApiKeyStore.Provider.OLLAMA_LOCAL -> "Ollama (Local)"
            CloudApiKeyStore.Provider.OLLAMA_REMOTE -> "Ollama (Cloud)"
            CloudApiKeyStore.Provider.LM_STUDIO -> "LM Studio"
        }
        return "$label · ${parsed.modelName}"
    }
}
