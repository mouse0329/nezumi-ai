package com.nezumi_ai.data.inference.prompt

/**
 * プロンプト構築レイヤ全体で共通に扱う「チャット1ターン」(計画書 2.4 の ConversationTurn)。
 *
 * `ChatViewModel` / `PromptBuildingUseCase` などの上位層で作成し、
 * GGUF / LiteRT-LM / Cloud の各レンダラー実装 (Phase 3〜) に渡す。
 *
 * これまで各エンジン経路が個別に `MessageEntity` 由来のロール文字列 ("user"/"assistant"/"model")
 * や sanitizer を持ち回していたのを、この 3 値 enum + サニタイズ済み本文 (`content`) に
 * 統一する。`imageDescription` は画像を含むメッセージについての軽量な説明で、
 * マルチモーダル未対応エンジン (クラウド Ollama など) や履歴経路のフォールバックとして使う。
 *
 * `role` の値は上位層でエンジン非依存に決まる:
 *   - USER      : ユーザー発話 (現ターン含む)
 *   - ASSISTANT : モデル応答 (LiteRT-LM 側では "model" に射影される)
 *   - SYSTEM    : 一部レンダラー (Cloud 系) がシステムメッセージを履歴に混在させるための予約枠。
 *                 GGUF / LiteRT では通常 [ConversationInput.systemInstruction] を使う。
 */
data class ConversationTurn(
    /** 上位の MessageEntity 由来の安定 ID (履歴同期・診断用途)。合成ターンなら null。 */
    val id: Long? = null,
    val role: Role,
    val content: String,
    /**
     * 画像付きメッセージに付与される軽量な説明。
     * マルチモーダル未対応レンダラーではこの文字列を content 前置きに使うことで、
     * 「画像があった」という文脈だけでもモデルに伝える。
     */
    val imageDescription: String? = null,
    /**
     * このターンに紐づく画像の枚数 (メタ情報)。実バイト列は別経路 (エンジン API) で送る。
     * `imageDescription` の生成やコンテキスト予算調整のヒントに使う。
     */
    val imageCount: Int = 0,
    /**
     * GGUF の現ターンかどうか (マルチモーダル `<__media__>` トークン注入の判定用)。
     * 上位層 (ChatViewModel) で currentTurnMessageId と一致する user ターンにのみ true を立てる。
     */
    val isCurrentTurn: Boolean = false,
) {
    enum class Role { SYSTEM, USER, ASSISTANT }

    companion object {
        fun user(
            content: String,
            id: Long? = null,
            imageCount: Int = 0,
            imageDescription: String? = null,
            isCurrentTurn: Boolean = false
        ): ConversationTurn =
            ConversationTurn(
                id = id,
                role = Role.USER,
                content = content,
                imageCount = imageCount,
                imageDescription = imageDescription,
                isCurrentTurn = isCurrentTurn
            )

        fun assistant(content: String, id: Long? = null): ConversationTurn =
            ConversationTurn(id = id, role = Role.ASSISTANT, content = content)

        fun system(content: String): ConversationTurn =
            ConversationTurn(id = null, role = Role.SYSTEM, content = content)
    }
}
