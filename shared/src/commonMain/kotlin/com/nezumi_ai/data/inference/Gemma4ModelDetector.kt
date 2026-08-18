package com.nezumi_ai.data.inference

/** モデル名から Gemma 4 系かを判定する純粋ロジック (PromptBuilder と同一規則)。 */
object Gemma4ModelDetector {

    fun isGemma4Model(modelIdOrPath: String): Boolean {
        val raw = modelIdOrPath.trim()
        if (raw.isEmpty()) return false
        return isGemma4ModelName(resolveModelNameForGemmaCheck(raw).lowercase())
    }

    fun resolveModelNameForGemmaCheck(modelIdOrPath: String): String {
        val trimmed = modelIdOrPath.trim()
        if (trimmed.startsWith("cloud:", ignoreCase = true)) {
            val body = trimmed.substringAfter(":")
            val parts = body.split(":", limit = 2)
            if (parts.size >= 2 && parts[1].isNotBlank()) return parts[1]
            return trimmed
        }
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        if (slash >= 0 && slash < trimmed.lastIndex) return trimmed.substring(slash + 1)
        return trimmed
    }

    private fun isGemma4ModelName(loweredName: String): Boolean {
        if ("gemma" !in loweredName) return false
        if (Regex("gemma[\\-_ .]?4(?![0-9])").containsMatchIn(loweredName)) return true
        if (Regex("(^|[^a-z0-9])(e2b|e4b|e8b|e12b)([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        if (Regex("(^|[^a-z0-9])(12b|26b|31b|46b)[\\-_]?a4b([^a-z0-9]|$)").containsMatchIn(loweredName)) return true
        if (Regex("gemma[\\-_ .]?4b(?![0-9])").containsMatchIn(loweredName)) return true
        return false
    }
}
