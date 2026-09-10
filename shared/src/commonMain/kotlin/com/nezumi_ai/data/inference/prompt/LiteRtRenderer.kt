package com.nezumi_ai.data.inference.prompt

/**
 * LiteRT-LM (Conversation API) 向けレンダラー (計画書 Phase 4)。
 *
 * [ConversationInput] を system / history / current の3フィールドに分解するだけで、
 * チャットテンプレートのタグ (`<start_of_turn>` 等) は一切書かない。
 * テンプレート適用は LiteRT-LM エンジン側 (モデル同梱の chat template) に委ねる
 * (計画書 2.2 の StructuredPayload、旧 `ChatViewModel.buildLiteRtStructuredPayload` の移行先)。
 *
 * ツール定義 ([ConversationInput.toolsBlock]) は system instruction 文字列内に埋め込む
 * (増減してもモデル再ロードが不要な既存要件の維持)。
 *
 * thinking 制御はこの層では行わない。`ConversationConfig.thinkingConfig`
 * (正規 API) でエンジンに伝えるのは app 側 `LiteRtLmEngine` の責務
 * (計画書 1.2b / Phase 4)。
 */
object LiteRtRenderer {

    /** LiteRT-LM Conversation API へ渡す構造化ペイロード。 */
    data class LiteRtPayload(
        /** system instruction (ツール定義ブロック注入済みの最終文字列)。空の場合あり。 */
        val systemInstruction: String,
        /** 現ターンを除いた会話履歴 (initialMessages 相当)。 */
        val history: List<ConversationTurn>,
        /** 現ターン (最新 user) の本文。画像等の添付はエンジン API 経由で別送する。 */
        val currentText: String,
        /** 現ターンの安定 ID (診断用途)。 */
        val currentTurnId: Long?,
    )

    /**
     * [ConversationInput] を構造化ペイロードへマッピングする。
     *
     * - system = systemInstruction + toolsBlock (改行区切りで連結)
     * - history = 現ターンを除いた USER / ASSISTANT ターン (SYSTEM ロールは
     *   [ConversationInput.systemInstruction] に一本化されている前提のため除外)
     * - current = 現ターン (最新 user) の本文
     */
    fun render(input: ConversationInput): LiteRtPayload {
        val systemText = buildString {
            if (input.systemInstruction.isNotBlank()) append(input.systemInstruction.trim())
            if (!input.toolsBlock.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(input.toolsBlock)
            }
        }
        val current = input.currentTurnOrNull()
        return LiteRtPayload(
            systemInstruction = systemText,
            history = input.historyExcludingCurrent()
                .filter { it.role != ConversationTurn.Role.SYSTEM && it.content.isNotBlank() },
            currentText = current?.content.orEmpty(),
            currentTurnId = current?.id,
        )
    }
}
