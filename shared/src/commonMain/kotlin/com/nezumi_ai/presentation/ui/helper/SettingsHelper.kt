package com.nezumi_ai.presentation.ui.helper

/**
 * Settings UIで共用するヘルパー関数。
 *
 * パスの判定はUIから切り離して共通コードで行う。ファイルシステムへのアクセスは
 * 必要ないため、Android/JVM APIに依存しない。
 */
object SettingsHelper {
    private fun modelFileName(path: String): String =
        path.trim().substringAfterLast('/').substringAfterLast('\\').lowercase()

    /**
     * インポートされたモデルのファイル種別をラベル化する。
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
     * モデルパスから実際に使用されるエンジンを判定する。
     *
     * @return "llama.cpp" または "LiteRT-LM"
     */
    fun inferenceEngineForModel(path: String): String {
        val extension = modelFileName(path).substringAfterLast('.', missingDelimiterValue = "")
        return if (extension == "gguf") "llama.cpp" else "LiteRT-LM"
    }

    /**
     * HFトークンをマスク表示する。
     *
     * 例: "hf_abc...xyz1234" -> "hf_****...****1234"
     */
    fun maskToken(token: String): String {
        if (token.length <= 6) return "******"
        val head = token.take(3)
        val tail = token.takeLast(4)
        return "$head${"*".repeat((token.length - 7).coerceAtLeast(6))}$tail"
    }
}
