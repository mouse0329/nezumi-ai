package com.nezumi_ai.data.inference

import org.json.JSONArray
import org.json.JSONObject

/**
 * LiteRT-LM (Conversation API) 経路の構造化プロンプトペイロード。
 *
 * Bug fix: 以前は LiteRT 経路でも Gemma 3 形式の `<start_of_turn>...` テンプレートを
 * アプリ側で手動構築して 1 テキストとして送っていた。しかし LiteRT-LM の
 * Conversation API はエンジン側がモデル同梱の chat template を適用するため、
 * 「エンジンのテンプレート + アプリの手動テンプレート」の二重適用になり、
 * Gemma 4 系では思考タグやロール境界が本文へ混入する原因になっていた。
 *
 * このペイロードは system / history / current を分離した中間表現で、
 * [PREFIX] マーカーを先頭に付けて String として ViewModel → Engine へ運ぶ。
 * Engine 側でデコードして `ConversationConfig(systemInstruction, initialMessages)` と
 * 現ターンメッセージに分解し、テンプレート適用はエンジンに委ねる。
 *
 * なおクラウドエンジン経路はこの形式を使わず、従来の単一テキストプロンプトのまま
 * (クラウド API へそのまま渡すため)。圧縮サマリー / メモリ抽出などの内部推論も
 * マーカーなしの平文を送るため、エンジン側では「デコードできたか」で本流と内部を分岐する。
 */
object LiteRtStructuredPrompt {

    /** ペイロード識別マーカー。SOH 制御文字を前置して通常テキストとの衝突を防ぐ。 */
    const val PREFIX = "\u0001NEZUMI_LITERT_PROMPT_V1:"

    data class HistoryTurn(
        val id: Long,
        val role: String,
        val content: String
    )

    data class Payload(
        val systemInstruction: String,
        val history: List<HistoryTurn>,
        val currentMessageId: Long?,
        val currentText: String
    ) {
        val historyIds: List<Long> get() = history.map { it.id }
    }

    fun isStructured(prompt: String): Boolean = prompt.startsWith(PREFIX)

    fun encode(
        systemInstruction: String,
        history: List<HistoryTurn>,
        currentMessageId: Long?,
        currentText: String
    ): String {
        val json = JSONObject()
        json.put("system", systemInstruction)
        val arr = JSONArray()
        history.forEach { turn ->
            arr.put(
                JSONObject()
                    .put("id", turn.id)
                    .put("role", turn.role)
                    .put("content", turn.content)
            )
        }
        json.put("history", arr)
        if (currentMessageId != null) json.put("currentId", currentMessageId)
        json.put("current", currentText)
        return PREFIX + json.toString()
    }

    /** マーカーなし / パース失敗時は null (呼び出し側は従来の平文プロンプトとして扱う)。 */
    fun decode(prompt: String): Payload? {
        if (!isStructured(prompt)) return null
        return runCatching {
            val json = JSONObject(prompt.removePrefix(PREFIX))
            val history = mutableListOf<HistoryTurn>()
            val arr = json.optJSONArray("history")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    history += HistoryTurn(
                        id = obj.optLong("id", -1L),
                        role = obj.optString("role", "user"),
                        content = obj.optString("content", "")
                    )
                }
            }
            Payload(
                systemInstruction = json.optString("system", ""),
                history = history,
                currentMessageId = if (json.has("currentId")) json.getLong("currentId") else null,
                currentText = json.optString("current", "")
            )
        }.getOrNull()
    }
}
