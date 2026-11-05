package com.nezumi_ai.data.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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
        val json = buildJsonObject {
            put("system", systemInstruction)
            put("history", buildJsonArray {
                history.forEach { turn ->
                    add(buildJsonObject {
                        put("id", turn.id)
                        put("role", turn.role)
                        put("content", turn.content)
                    })
                }
            })
            currentMessageId?.let { put("currentId", it) }
            put("current", currentText)
        }
        return PREFIX + json.toString()
    }

    /** マーカーなし / パース失敗時は null (呼び出し側は従来の平文プロンプトとして扱う)。 */
    fun decode(prompt: String): Payload? {
        if (!isStructured(prompt)) return null
        return runCatching {
            val json = Json.parseToJsonElement(prompt.removePrefix(PREFIX)).jsonObject
            val history = json["history"]?.jsonArray.orEmpty().map { element ->
                val obj = element.jsonObject
                HistoryTurn(
                    id = obj["id"]?.jsonPrimitive?.longOrNull ?: -1L,
                    role = obj["role"]?.jsonPrimitive?.content ?: "user",
                    content = obj["content"]?.jsonPrimitive?.content ?: ""
                )
            }
            Payload(
                systemInstruction = json["system"]?.jsonPrimitive?.content ?: "",
                history = history,
                currentMessageId = json["currentId"]?.jsonPrimitive?.longOrNull,
                currentText = json["current"]?.jsonPrimitive?.content ?: ""
            )
        }.getOrNull()
    }
}
