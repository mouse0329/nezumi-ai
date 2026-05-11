package com.nezumi_ai.presentation.ui.helper

import java.io.File

/**
 * Settings UIで共用するヘルパー関数
 */
object SettingsHelper {
    private fun modelFileName(path: String): String =
        runCatching { File(path.trim()).name }
            .getOrDefault(path.trim())
            .lowercase()

    private fun isAbsoluteModelPath(path: String): Boolean {
        val trimmed = path.trim()
        return File(trimmed).isAbsolute || trimmed.startsWith("/")
    }
    
    /**
     * インポートされたモデルのファイル種別をラベル化
     */
    fun importedModelKindLabel(path: String): String {
        return when (modelFileName(path).substringAfterLast('.', missingDelimiterValue = "")) {
            "gguf" -> "GGUF / llama.cpp"
            "litertlm" -> "LiteRT-LM (.litertlm)"
            "task" -> "LiteRT-LM (.task)"
            else -> "Custom"
        }
    }

    /**
     * モデルパスから実際に使用されるエンジンを判定
     * @return "llama.cpp" または "LiteRT-LM"
     */
    fun inferenceEngineForModel(path: String): String {
        val trimmed = path.trim()
        val extension = modelFileName(trimmed).substringAfterLast('.', missingDelimiterValue = "")
        // 追加モデルでは実ファイル名が .gguf のものだけ llama.cpp を使用する。
        // ★ バグ修正: 拡張子が .gguf のみ llama.cpp、それ以外は LiteRT-LM
        return if (extension == "gguf") "llama.cpp" else "LiteRT-LM"
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
