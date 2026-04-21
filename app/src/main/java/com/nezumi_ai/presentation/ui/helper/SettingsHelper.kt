package com.nezumi_ai.presentation.ui.helper

/**
 * Settings UIで共用するヘルパー関数
 */
object SettingsHelper {
    
    /**
     * インポートされたモデルのファイル種別をラベル化
     */
    fun importedModelKindLabel(path: String): String {
        val lowered = path.lowercase()
        return when {
            lowered.endsWith(".gguf") -> "GGUF / llama.cpp"
            lowered.endsWith(".litertlm") -> "LiteRT-LM (.litertlm)"
            lowered.endsWith(".task") -> "LiteRT-LM (.task)"
            else -> "Custom"
        }
    }

    /**
     * HFトークンをマスク表示
     * 例: "hf_abc...xyz1234" → "hf_****...****1234"
     */
    fun maskToken(token: String): String {
        if (token.length <= 6) return "******"
        val head = token.take(3)
        val tail = token.takeLast(4)
        return "$head${"*".repeat((token.length - 7).coerceAtLeast(6))}$tail"
    }
}
