package com.nezumi_ai.data.inference.cloud

/**
 * プロバイダ非依存の共通中間チャットメッセージ形式 (commonMain 版)。
 * 画像は JPEG バイト列 ([ByteArray]) で保持する (Bitmap は Android 依存のため)。
 */
data class CloudChatMessage(
    val role: Role,
    val text: String,
    val images: List<ByteArray> = emptyList()
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

/**
 * 連結済みプロンプト文字列を粗く role 別に分解するヘルパ (commonMain 版)。
 * 詳細は app 側 CloudChatMessage.kt のドキュメントを参照 (ロジックは同一)。
 */
object CloudPromptSplitter {

    /**
     * プロンプト先頭が「System: ...\n\n」の形なら (SYSTEM_TEXT, REMAINING) を返す。
     * それ以外は (null, prompt)。
     */
    fun splitOptionalSystem(prompt: String): Pair<String?, String> {
        val trimmedStart = prompt.trimStart()
        val markers = listOf("System:\n", "System: ", "SYSTEM:\n", "SYSTEM: ")
        for (marker in markers) {
            if (trimmedStart.startsWith(marker)) {
                val body = trimmedStart.removePrefix(marker)
                val idx = body.indexOf("\n\n")
                return if (idx >= 0) {
                    body.substring(0, idx).trim() to body.substring(idx + 2).trimStart()
                } else {
                    body.trim() to ""
                }
            }
        }
        return null to prompt
    }
}
