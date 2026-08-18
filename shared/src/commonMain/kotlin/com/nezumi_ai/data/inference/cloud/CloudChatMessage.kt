package com.nezumi_ai.data.inference.cloud

/** プロバイダ非依存の共通チャットメッセージ (commonMain 版、画像は JPEG バイト列)。 */
data class CloudChatMessage(
    val role: Role,
    val text: String,
    val images: List<ByteArray> = emptyList()
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

/** 連結済みプロンプトを粗く role 分解するヘルパ (commonMain 版、ロジック同一)。 */
object CloudPromptSplitter {
    fun splitOptionalSystem(prompt: String): Pair<String?, String> {
        val trimmedStart = prompt.trimStart()
        val markers = listOf("System:\n", "System: ", "SYSTEM:\n", "SYSTEM: ")
        for (marker in markers) {
            if (trimmedStart.startsWith(marker)) {
                val body = trimmedStart.removePrefix(marker)
                val idx = body.indexOf("\n\n")
                return if (idx >= 0) body.substring(0, idx).trim() to body.substring(idx + 2).trimStart()
                else body.trim() to ""
            }
        }
        return null to prompt
    }
}
