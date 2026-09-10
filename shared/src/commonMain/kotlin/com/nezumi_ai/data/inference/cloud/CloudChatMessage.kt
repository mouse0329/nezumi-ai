package com.nezumi_ai.data.inference.cloud

/**
 * プロバイダ非依存の共通チャットメッセージ (commonMain 版、画像は JPEG バイト列)。
 *
 * Phase 5 で正式な受け渡し型に昇格。クラウド 5 エンジン (Claude / Gemini / OpenAI /
 * LM Studio / Ollama) はこの配列を受け取り、それぞれの API の role 構造へ直接射影する
 * (旧 `CloudPromptSplitter` による平文の再パースは廃止)。
 *
 * ツール呼び出しマルチターン継続時に [AbstractCloudInferenceEngine] が合成する
 * ASSISTANT / TOOL_RESULT ターンもこの型で表現する。
 */
data class CloudChatMessage(
    val role: Role,
    val text: String,
    val images: List<ByteArray> = emptyList()
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL_RESULT }
}
