package com.nezumi_ai.presentation.ui.helper

import java.io.File

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
     * モデルパスから実際に使用されるエンジンを判定
     * @return "llama.cpp" または "LiteRT-LM"
     */
    fun inferenceEngineForModel(path: String): String {
        val trimmed = path.trim()
        val lowered = trimmed.lowercase()
        // .gguf で絶対パスの場合のみ llama.cpp を使用
        val isAbsoluteGguf = lowered.endsWith(".gguf") && File(trimmed).isAbsolute
        return if (isAbsoluteGguf) "llama.cpp" else "LiteRT-LM"
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
