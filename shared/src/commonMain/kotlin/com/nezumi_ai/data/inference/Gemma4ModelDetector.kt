package com.nezumi_ai.data.inference

/**
 * モデル名から Gemma 4 系かどうかを判定する純粋ロジック。
 *
 * app 側 `PromptBuilder.isGemma4Model` / `isGemma4ModelName` と同じ判定規則を
 * commonMain に抽出したもの (クラウドエンジンがツールコール形式選択に使う)。
 * クラウドモデル ID (`cloud:provider:modelName`) なら実モデル名だけを見て判定する。
 */
object Gemma4ModelDetector {

    fun isGemma4Model(modelIdOrPath: String): Boolean {
        val raw = modelIdOrPath.trim()
        if (raw.isEmpty()) return false
        val nameForCheck = resolveModelNameForGemmaCheck(raw)
        return isGemma4ModelName(nameForCheck.lowercase())
    }

    /**
     * ツールコール形式の選択用に、判定対象のモデル名を正規化する。
     * - `cloud:provider:modelName` → modelName
     * - ローカルパス / 識別子 → ファイル名部分 (パスの場合)
     */
    fun resolveModelNameForGemmaCheck(modelIdOrPath: String): String {
        val trimmed = modelIdOrPath.trim()
        if (trimmed.startsWith("cloud:", ignoreCase = true)) {
            val body = trimmed.substringAfter(":")
            val parts = body.split(":", limit = 2)
            if (parts.size >= 2) {
                val modelName = parts[1]
                if (modelName.isNotBlank()) return modelName
            }
            return trimmed
        }
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        if (slash >= 0 && slash < trimmed.lastIndex) {
            return trimmed.substring(slash + 1)
        }
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
