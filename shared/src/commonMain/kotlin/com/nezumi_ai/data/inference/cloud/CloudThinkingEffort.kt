package com.nezumi_ai.data.inference.cloud

/**
 * クラウド各サービスの思考強度 (low / medium / high)。
 *
 * サービスが強度パラメータを持たない場合は空リストを返す。
 * UI はそのときセグメントを出さない。LiteRT-LM はここを通らない。
 */
object CloudThinkingEffort {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    val GRADED = listOf(LOW, MEDIUM, HIGH)

    fun levelsFor(provider: CloudApiKeyStore.Provider): List<String> = when (provider) {
        // Ollama native API の think: "low"|"medium"|"high"
        CloudApiKeyStore.Provider.OLLAMA_LOCAL,
        CloudApiKeyStore.Provider.OLLAMA_REMOTE,
        // Gemini thinkingConfig.thinkingLevel
        CloudApiKeyStore.Provider.GEMINI,
        // OpenAI reasoning_effort (reasoning 系モデル)
        CloudApiKeyStore.Provider.OPENAI,
        // Claude output_config.effort / extended thinking budget
        CloudApiKeyStore.Provider.CLAUDE -> GRADED
        // LM Studio は強度 API を持たない
        CloudApiKeyStore.Provider.LM_STUDIO -> emptyList()
    }

    fun levelsForModel(modelId: String): List<String> {
        val parsed = CloudModelId.parse(modelId) ?: return emptyList()
        return levelsFor(parsed.provider)
    }

    fun normalize(effort: String?, levels: List<String> = GRADED): String {
        if (levels.isEmpty()) return LOW
        val normalized = effort?.trim()?.lowercase().orEmpty()
        return if (normalized in levels) normalized else levels.first()
    }

    /**
     * Ollama `/api/chat` の `think`。
     * OFF は false (既定で思考するモデルでも止める)。ON は強度文字列。
     * true だけだと gpt-oss 系が強度を無視するため、ON 時は必ずレベルを送る。
     */
    fun ollamaThinkValue(enableThinking: Boolean, effort: String?): Any =
        if (!enableThinking) false else normalize(effort, GRADED)

    fun geminiSupportsThinkingLevel(model: String): Boolean {
        val name = model.lowercase()
        return "gemini-2.5" in name || "gemini-3" in name || "thinking" in name
    }

    fun openaiSupportsReasoningEffort(model: String): Boolean {
        val name = model.lowercase()
        return name.contains("o1") || name.contains("o3") || name.contains("o4") ||
            name.contains("gpt-5") || name.contains("gpt-6") || name.contains("reasoning")
    }

    /** output_config.effort を受ける世代。それ以外は budget_tokens にマップする。 */
    fun claudeSupportsEffort(model: String): Boolean {
        val name = model.lowercase()
        return "fable" in name || "mythos" in name ||
            "sonnet-5" in name || "opus-5" in name ||
            "opus-4.7" in name || "opus-4.8" in name ||
            "opus-4-7" in name || "opus-4-8" in name
    }

    /** Claude extended thinking の budget。max_tokens 未満に収める。 */
    fun claudeBudgetTokens(effort: String?, maxTokens: Int): Int {
        val desired = when (normalize(effort, GRADED)) {
            HIGH -> 16384
            MEDIUM -> 4096
            else -> 1024
        }
        return desired.coerceAtMost((maxTokens - 1).coerceAtLeast(1))
    }
}
