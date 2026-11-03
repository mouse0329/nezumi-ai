package com.nezumi_ai.data.inference.cloud

/**
 * クラウドモデルが画像・音声を扱えるか。
 *
 * チャットの添付可否とモデル管理の表示はここを見る。
 * Ollama は `/api/show` の capabilities を優先し、未取得のときはモデル名から推定する。
 * それ以外はモデル名から推定する。
 */
object CloudMediaCapability {

    data class State(val image: Boolean, val audio: Boolean)

    private val ollama = mutableMapOf<String, State>()

    fun put(baseUrl: String, model: String, state: State) {
        val bare = CloudModelId.bareModelName(model).trim().lowercase()
        if (bare.isEmpty()) return
        val url = baseUrl.trim().trimEnd('/')
        if (url.isNotEmpty()) ollama["$url|$bare"] = state
        ollama[bare] = state
    }

    fun forModel(modelId: String): State {
        val parsed = CloudModelId.parse(modelId) ?: return State(image = false, audio = false)
        val bare = CloudModelId.bareModelName(parsed.modelName)
        if (parsed.provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL ||
            parsed.provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE
        ) {
            cached(parsed.modelName, bare)?.let { return it }
        }
        return heuristic(parsed.provider, bare)
    }

    private fun cached(modelName: String, bare: String): State? {
        val key = bare.trim().lowercase()
        val endpoint = CloudModelId.endpointOf(modelName)?.trimEnd('/')
        if (!endpoint.isNullOrEmpty()) ollama["$endpoint|$key"]?.let { return it }
        return ollama[key]
    }

    fun heuristic(provider: CloudApiKeyStore.Provider, modelName: String): State {
        val name = CloudModelId.bareModelName(modelName).trim().lowercase()
        return when (provider) {
            CloudApiKeyStore.Provider.GEMINI -> State(
                image = name.startsWith("gemini"),
                audio = name.startsWith("gemini-1.5") ||
                    name.startsWith("gemini-2") ||
                    name.startsWith("gemini-3") ||
                    "live" in name
            )
            CloudApiKeyStore.Provider.CLAUDE -> State(
                image = "claude-3" in name || "claude-4" in name || "claude-sonnet" in name ||
                    "claude-opus" in name || "claude-haiku" in name,
                audio = false
            )
            CloudApiKeyStore.Provider.OPENAI -> State(
                image = visionOpenAi(name),
                audio = false
            )
            CloudApiKeyStore.Provider.OLLAMA_LOCAL,
            CloudApiKeyStore.Provider.OLLAMA_REMOTE,
            CloudApiKeyStore.Provider.LM_STUDIO -> State(
                image = visionLocal(name),
                audio = audioLocal(name)
            )
        }
    }

    private fun visionOpenAi(name: String): Boolean {
        if ("gpt-3.5" in name || name == "gpt-4" || name.startsWith("gpt-4-0")) return false
        return "gpt-4o" in name || "gpt-4.1" in name || "gpt-4-turbo" in name ||
            "gpt-4-vision" in name || "gpt-5" in name || "chatgpt" in name ||
            name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4")
    }

    private fun visionLocal(name: String): Boolean {
        val keys = listOf(
            "vision", "llava", "bakllava", "moondream", "gemma3", "gemma-3", "gemma4", "gemma-4",
            "qwen2-vl", "qwen-vl", "qwen2.5-vl", "qwen2.5vl", "minicpm-v", "pixtral", "internvl",
            "llama3.2-vision", "phi-3-vision", "phi3-vision", "cogvlm", "yi-vl"
        )
        return keys.any { it in name }
    }

    private fun audioLocal(name: String): Boolean {
        val keys = listOf("audio", "ultravox", "qwen2-audio", "qwen-audio")
        return keys.any { it in name }
    }
}
