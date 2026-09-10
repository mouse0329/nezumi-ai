package com.nezumi_ai.data.inference.prompt

import com.nezumi_ai.data.inference.cloud.CloudChatMessage

/**
 * クラウド (Claude / Gemini / OpenAI / LM Studio / Ollama) 向けレンダラー (計画書 Phase 5)。
 *
 * [ConversationInput] を role 付きメッセージ配列 [CloudChatMessage] に変換するだけで、
 * チャットテンプレートのタグは一切書かない (role 構造は各クラウド API が解釈する)。
 *
 * これにより旧経路の以下のバグ (計画書 1.2) を構造的に解消する:
 *   - クラウドモデルが `isGgufEngineModel()==false` により Gemma 固定の
 *     `PromptBuilder.buildForLiteRt` に流れ、Gemma ChatML タグ込みの平文が
 *     `CloudPromptSplitter` (「System:\n」マーカーしか知らない) で分解できず、
 *     全文が user ロール1個に潰されて送信されていた。
 *   - 複数ターンの会話履歴が構造化されず平文連結で送られていた。
 *
 * ツール定義 ([ConversationInput.toolsBlock]) は system メッセージへの注入を維持する
 * (API レベルの tools フィールドを持たない簡易実装のため、既存と同じく system 内連結)。
 */
object CloudRenderer {

    /**
     * [ConversationInput] をクラウド API 用メッセージ配列に変換する。
     *
     * - 先頭に SYSTEM メッセージ (systemInstruction + toolsBlock)。両方空なら生成しない。
     * - 続いて履歴ターン (USER / ASSISTANT のみ。SYSTEM ロールターンは先頭 SYSTEM に
     *   一本化されている前提のため、履歴中の SYSTEM は別 SYSTEM メッセージとして保持せず
     *   連結する — ただし現行上位層は履歴に SYSTEM を入れない設計)。
     * - 画像は現ターン (末尾 USER) にのみ同梱する想定 (呼び出し側が ConversationTurn の
     *   imageCount / エンジン API 経由で別送するため、ここではテキストのみ扱う)。
     */
    fun render(input: ConversationInput): List<CloudChatMessage> {
        val messages = mutableListOf<CloudChatMessage>()

        val systemText = buildString {
            if (input.systemInstruction.isNotBlank()) append(input.systemInstruction.trim())
            if (!input.toolsBlock.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(input.toolsBlock)
            }
        }
        if (systemText.isNotBlank()) {
            messages += CloudChatMessage(CloudChatMessage.Role.SYSTEM, systemText)
        }

        input.history.forEach { turn ->
            if (turn.content.isBlank()) return@forEach
            val role = when (turn.role) {
                ConversationTurn.Role.SYSTEM -> {
                    // 履歴中の SYSTEM は先頭 SYSTEM に連結できない位置にあるため、
                    // クラウド API 互換のため USER として送る (system ロールを
                    // 履歴中に混在させると Claude 等で 400 になるため)。
                    messages += CloudChatMessage(CloudChatMessage.Role.USER, turn.content)
                    return@forEach
                }
                ConversationTurn.Role.USER -> CloudChatMessage.Role.USER
                ConversationTurn.Role.ASSISTANT -> CloudChatMessage.Role.ASSISTANT
            }
            messages += CloudChatMessage(role, turn.content)
        }
        return messages
    }
}
