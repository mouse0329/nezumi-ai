package com.nezumi_ai.data.inference.cloud

/**
 * クラウド各サービスの思考 ON/OFF と強度。
 *
 * プロバイダ単位で low/medium/high を出さない。モデルが実際に受ける値だけを返す。
 * UI は [ThinkingControl.showToggle] が false かつ [ThinkingControl.effortLevels] が空なら
 * シンキング欄自体を出さない。
 *
 * Ollama はモデルごとに `/api/show` の `thinking.values` が違う。
 * 未取得のあいだは強度セグメントを出さず、ON/OFF だけにする
 * (強度文字列を送ると qwen 系は think を無視する)。
 */
object CloudThinkingEffort {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val MAX = "max"
    const val MINIMAL = "minimal"

    val GRADED = listOf(LOW, MEDIUM, HIGH)

    enum class GeminiThinkingMode { NONE, LEVEL, BUDGET }

    data class ThinkingControl(
        val showToggle: Boolean,
        val canDisable: Boolean,
        val effortLevels: List<String>
    ) {
        companion object {
            val HIDDEN = ThinkingControl(showToggle = false, canDisable = true, effortLevels = emptyList())
        }
    }

    /**
     * `/api/show` の thinking オブジェクト。
     * values は "true" / "false" または "low" などの文字列。
     */
    data class OllamaThinkingSpec(
        val values: List<String>,
        val default: String? = null
    ) {
        val allowsTrue: Boolean get() = "true" in values
        val allowsFalse: Boolean get() = "false" in values
        val levelValues: List<String> get() = values.filter { it != "true" && it != "false" }

        fun toControl(): ThinkingControl {
            if (values.isEmpty() || values == listOf("false")) return ThinkingControl.HIDDEN
            val levels = levelValues
            val canDisable = allowsFalse || levels.isEmpty()
            return ThinkingControl(
                showToggle = true,
                canDisable = canDisable,
                effortLevels = levels
            )
        }
    }

    fun levelsFor(provider: CloudApiKeyStore.Provider): List<String> = when (provider) {
        // モデル名が無いときは強度を出さない (プロバイダ全体に同じセグメントを出すと誤表示になる)
        CloudApiKeyStore.Provider.LM_STUDIO,
        CloudApiKeyStore.Provider.OLLAMA_LOCAL,
        CloudApiKeyStore.Provider.OLLAMA_REMOTE,
        CloudApiKeyStore.Provider.GEMINI,
        CloudApiKeyStore.Provider.OPENAI,
        CloudApiKeyStore.Provider.CLAUDE -> emptyList()
    }

    fun levelsForModel(modelId: String): List<String> = controlForModel(modelId).effortLevels

    fun controlForModel(modelId: String): ThinkingControl {
        val parsed = CloudModelId.parse(modelId) ?: return ThinkingControl.HIDDEN
        val bare = CloudModelId.bareModelName(parsed.modelName)
        return when (parsed.provider) {
            CloudApiKeyStore.Provider.OLLAMA_LOCAL,
            CloudApiKeyStore.Provider.OLLAMA_REMOTE -> ollamaControl(modelId, bare)
            CloudApiKeyStore.Provider.GEMINI -> geminiControl(bare)
            CloudApiKeyStore.Provider.CLAUDE -> claudeControl(bare)
            CloudApiKeyStore.Provider.OPENAI -> openaiControl(bare)
            CloudApiKeyStore.Provider.LM_STUDIO -> ThinkingControl.HIDDEN
        }
    }

    fun normalize(effort: String?, levels: List<String> = GRADED): String {
        if (levels.isEmpty()) return LOW
        val normalized = effort?.trim()?.lowercase().orEmpty()
        if (normalized in levels) return normalized
        // 他モデルの max / xhigh を、その段が無いモデルへ流さない。一番強い対応値に寄せる。
        if (normalized == MAX || normalized == "xhigh") return levels.last()
        return levels.first()
    }

    fun ollamaControl(modelId: String, bareName: String): ThinkingControl {
        OllamaThinkingCache.get(modelId)?.let { return it.toControl() }
        OllamaThinkingCache.getByName(bareName)?.let { return it.toControl() }
        return ollamaHeuristic(bareName)
    }

    /** キャッシュが無いときの Ollama。仕様が分かるまで強度も ON/OFF も出さない。 */
    fun ollamaHeuristic(modelName: String): ThinkingControl {
        val name = modelName.lowercase()
        if ("gpt-oss" in name) {
            return ThinkingControl(showToggle = true, canDisable = false, effortLevels = GRADED)
        }
        return ThinkingControl.HIDDEN
    }

    /**
     * Ollama `/api/chat` の `think`。
     * 仕様未取得のときはモデル名のヒューリスティック。取得済みなら values 以外を送らない。
     * 対応しない値は null (フィールド自体を付けない)。
     */
    fun ollamaThinkValue(
        enableThinking: Boolean,
        effort: String?,
        spec: OllamaThinkingSpec? = null,
        modelName: String = ""
    ): Any? {
        if (spec != null && (spec.values.isEmpty() || spec.values == listOf("false"))) return null
        if (spec == null) return ollamaHeuristicValue(enableThinking, effort, modelName)
        val levels = spec.levelValues
        if (!enableThinking) {
            if (spec.allowsFalse) return false
            if (levels.isNotEmpty()) return normalize(effort, levels)
            return false
        }
        if (levels.isNotEmpty()) return normalize(effort, levels)
        if (spec.allowsTrue) return true
        return true
    }

    private fun ollamaHeuristicValue(enableThinking: Boolean, effort: String?, modelName: String): Any? {
        val control = ollamaHeuristic(modelName)
        if (!control.showToggle) return null
        if (control.effortLevels.isNotEmpty() && (enableThinking || !control.canDisable)) {
            return normalize(effort, control.effortLevels)
        }
        return enableThinking
    }

    fun geminiMode(model: String): GeminiThinkingMode {
        val name = model.lowercase()
        if (!name.contains("gemini")) return GeminiThinkingMode.NONE
        if (name.contains("embed") || name.contains("imagen") || name.contains("veo") || name.contains("aqa")) {
            return GeminiThinkingMode.NONE
        }
        if (name.contains("gemini-3") || name.contains("gemini-4")) return GeminiThinkingMode.LEVEL
        if (name.contains("gemini-2.5") || name.contains("gemini-2-5")) return GeminiThinkingMode.BUDGET
        return GeminiThinkingMode.NONE
    }

    fun geminiSupportsThinkingLevel(model: String): Boolean = geminiMode(model) == GeminiThinkingMode.LEVEL

    private fun geminiControl(model: String): ThinkingControl = when (geminiMode(model)) {
        GeminiThinkingMode.NONE -> ThinkingControl.HIDDEN
        GeminiThinkingMode.LEVEL -> {
            val name = model.lowercase()
            val flash = "flash" in name
            val levels = when {
                flash && ("gemini-3" in name || "gemini-4" in name) -> listOf(MINIMAL, LOW, MEDIUM, HIGH)
                "pro" in name && "gemini-3" in name && "3.1" !in name && "3-1" !in name -> listOf(LOW, HIGH)
                else -> GRADED
            }
            ThinkingControl(showToggle = true, canDisable = false, effortLevels = levels)
        }
        GeminiThinkingMode.BUDGET -> {
            val name = model.lowercase()
            val canDisable = "pro" !in name
            ThinkingControl(showToggle = true, canDisable = canDisable, effortLevels = GRADED)
        }
    }

    /** Gemini 2.5 の thinkingBudget。0 は Flash 系の思考オフ。Pro は 128 未満にしない。 */
    fun geminiBudgetTokens(model: String, enableThinking: Boolean, effort: String?): Int {
        val control = geminiControl(model)
        if (!enableThinking && control.canDisable) return 0
        val desired = when (normalize(effort, control.effortLevels.ifEmpty { GRADED })) {
            HIGH -> 16384
            MEDIUM -> 4096
            else -> 1024
        }
        val floor = when {
            !control.canDisable -> 128
            "lite" in model.lowercase() && desired > 0 -> 512
            else -> 0
        }
        return if (desired == 0) 0 else desired.coerceAtLeast(floor)
    }

    fun openaiSupportsReasoningEffort(model: String): Boolean {
        val name = model.lowercase()
        if (name.contains("embed") || name.contains("tts") || name.contains("whisper") ||
            name.contains("dall-e") || name.contains("moderation") || name.contains("realtime") ||
            name.contains("transcribe") || name.contains("image")
        ) return false
        return name.contains("o1") || name.contains("o3") || name.contains("o4") ||
            name.contains("gpt-5") || name.contains("gpt-6") || name.contains("reasoning")
    }

    private fun openaiControl(model: String): ThinkingControl {
        if (!openaiSupportsReasoningEffort(model)) return ThinkingControl.HIDDEN
        return ThinkingControl(showToggle = true, canDisable = true, effortLevels = GRADED)
    }

    /** output_config.effort を受ける世代。Opus 4.5 は budget と併用。4.6 以降は adaptive。 */
    fun claudeSupportsEffort(model: String): Boolean {
        val name = model.lowercase()
        return claudeUsesAdaptive(model) || claudeAlwaysOn(model) ||
            "opus-4.5" in name || "opus-4-5" in name
    }

    /**
     * thinking.type=adaptive だけを受け、budget_tokens が 400 になる世代。
     * Sonnet 4.5 / Opus 4.5 はここには入れない。
     */
    fun claudeUsesAdaptive(model: String): Boolean {
        if (claudeAlwaysOn(model)) return false
        val name = model.lowercase()
        return "sonnet-5" in name || "opus-5" in name || "haiku-5" in name ||
            "opus-4.6" in name || "opus-4-6" in name ||
            "opus-4.7" in name || "opus-4-7" in name ||
            "opus-4.8" in name || "opus-4-8" in name ||
            "sonnet-4.6" in name || "sonnet-4-6" in name
    }

    /** budget_tokens で思考を切り替える世代 (3.7 / Sonnet 4 / Opus 4.5 まで)。 */
    fun claudeUsesBudget(model: String): Boolean {
        if (claudeUsesAdaptive(model) || claudeAlwaysOn(model)) return false
        val name = model.lowercase()
        if ("haiku" in name) return false
        return "3-7" in name || "3.7" in name ||
            "sonnet-4" in name || "opus-4" in name
    }

    fun claudeAlwaysOn(model: String): Boolean {
        val name = model.lowercase()
        return "fable" in name || "mythos" in name
    }

    /** true なら思考オフは thinking ブロックを付けない (disabled が 400 になる世代)。 */
    fun claudeEffortDisablesByOmit(model: String): Boolean {
        val name = model.lowercase()
        return "opus-4.7" in name || "opus-4-7" in name ||
            "opus-4.8" in name || "opus-4-8" in name ||
            "opus-4.6" in name || "opus-4-6" in name ||
            "sonnet-4.6" in name || "sonnet-4-6" in name
    }

    private fun claudeControl(model: String): ThinkingControl {
        if (claudeAlwaysOn(model)) {
            return ThinkingControl(showToggle = true, canDisable = false, effortLevels = listOf(LOW, MEDIUM, HIGH, MAX))
        }
        // Opus 4.5 の effort は low / medium / high。max は 400 になる。
        if (isOpus45(model)) {
            return ThinkingControl(showToggle = true, canDisable = true, effortLevels = GRADED)
        }
        if (claudeUsesAdaptive(model)) {
            return ThinkingControl(showToggle = true, canDisable = true, effortLevels = listOf(LOW, MEDIUM, HIGH, MAX))
        }
        if (claudeUsesBudget(model)) {
            return ThinkingControl(showToggle = true, canDisable = true, effortLevels = GRADED)
        }
        return ThinkingControl.HIDDEN
    }

    private fun isOpus45(model: String): Boolean {
        val name = model.lowercase()
        return "opus-4.5" in name || "opus-4-5" in name
    }

    /** Claude extended thinking の budget。max_tokens 未満に収める。max は high と同じ上限。 */
    fun claudeBudgetTokens(effort: String?, maxTokens: Int): Int {
        val desired = when (effort?.trim()?.lowercase()) {
            HIGH, MAX -> 16384
            MEDIUM -> 4096
            else -> 1024
        }
        return desired.coerceAtMost((maxTokens - 1).coerceAtLeast(1))
    }
}

/** プロセス内の Ollama thinking 仕様。起動時と送信時の `/api/show` で更新する。 */
object OllamaThinkingCache {
    private val byKey = mutableMapOf<String, CloudThinkingEffort.OllamaThinkingSpec>()
    private val byName = mutableMapOf<String, CloudThinkingEffort.OllamaThinkingSpec>()

    /** UI がトグルと強度を取り直す。仕様が後から届いたときに古い low/medium/high を残さない。 */
    var onUpdated: (() -> Unit)? = null

    fun cacheKey(baseUrl: String, model: String): String =
        baseUrl.trim().trimEnd('/') + "|" + CloudModelId.bareModelName(model)

    fun put(baseUrl: String, model: String, spec: CloudThinkingEffort.OllamaThinkingSpec) {
        val bare = CloudModelId.bareModelName(model)
        val key = cacheKey(baseUrl, bare)
        val changed = byKey[key] != spec
        byKey[key] = spec
        byName[bare] = spec
        if (changed) runCatching { onUpdated?.invoke() }
    }

    fun get(modelId: String): CloudThinkingEffort.OllamaThinkingSpec? {
        val parsed = CloudModelId.parse(modelId) ?: return null
        val bare = CloudModelId.bareModelName(parsed.modelName)
        val endpoint = CloudModelId.endpointOf(parsed.modelName)
        if (endpoint != null) return byKey[cacheKey(endpoint, bare)] ?: byName[bare]
        return byName[bare]
    }

    fun getByName(modelName: String): CloudThinkingEffort.OllamaThinkingSpec? =
        byName[CloudModelId.bareModelName(modelName)]
}
