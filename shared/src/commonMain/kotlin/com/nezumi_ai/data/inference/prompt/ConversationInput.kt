package com.nezumi_ai.data.inference.prompt

/**
 * プロンプト構築レイヤの正規入力型 (計画書 2.4)。
 *
 * これまで各経路 (GGUF / LiteRT-LM / Cloud) がそれぞれ独自の引数群
 * (messages / systemPrompt / enableThinking / ...) を引き回していたのを、
 * この単一のイミュータブル値に集約する。
 *
 * ## 各フィールドの責務
 *
 * - [systemInstruction] : メモリブロック / ユーザー名 / システムプロンプト連結後の最終文字列。
 *   ツール定義 (tools ブロック) や Gemma4 の `<|think|>` トリガはここに **含めない**。
 *   ツール定義は [toolsBlock]、thinking 制御は [enableThinking] とレンダラー側で扱う。
 *
 * - [history]     : 会話履歴 (現ターンを含む)。上位層でバリアント選択 / エラー除外 /
 *                    再生成対象除外 / サニタイズ / 画像件数集計まで済ませた確定列。
 *                    各レンダラーはこれをそのまま順に描画するだけ。
 *
 * - [currentTurnId] : 現ターン (= 最新の user ターン) の [ConversationTurn.id]。
 *                     マルチモーダル入力の同梱判定や、現ターンだけ特別扱いする
 *                     レンダラー向けのヒント。`null` の場合、レンダラーは
 *                     末尾 user ターンを現ターンとみなす。
 *
 * - [toolsBlock]  : Gemma4 公式 / 汎用 `<tool_call>` 形式のツール定義ブロック (整形済み文字列)。
 *                   `enableToolCalling && skills 非空` の時のみ非 null。
 *                   レンダラーは [systemInstruction] とツール定義の結合方法だけを知っていればよい。
 *
 * - [enableThinking]    : ユーザー設定の Thinking ON/OFF。
 * - [enableToolCalling] : ツール呼び出し ON/OFF。thinking と共存させるのが現行方針。
 *
 * - [contextBudgetChars]  : レンダラーが履歴を古い側から間引く際の予算 (文字数換算)。
 *                            `0 以下` なら間引き無効 (呼び出し側で管理する場合)。
 */
data class ConversationInput(
    val systemInstruction: String,
    val history: List<ConversationTurn>,
    val currentTurnId: Long? = null,
    val toolsBlock: String? = null,
    val enableThinking: Boolean = false,
    val enableToolCalling: Boolean = false,
    val contextBudgetChars: Int = 0,
) {
    /**
     * [history] のうち現ターンとみなす user ターン。
     * `currentTurnId` が指す ID がなければ末尾の user ターンを返す。
     */
    fun currentTurnOrNull(): ConversationTurn? {
        if (currentTurnId != null) {
            history.lastOrNull { it.id == currentTurnId && it.role == ConversationTurn.Role.USER }
                ?.let { return it }
        }
        return history.lastOrNull { it.role == ConversationTurn.Role.USER }
    }

    /** 現ターンを除いた履歴 (initialMessages に載せる想定)。 */
    fun historyExcludingCurrent(): List<ConversationTurn> {
        val current = currentTurnOrNull() ?: return history
        return history.filter { it.id == null || it.id != current.id }
    }
}
