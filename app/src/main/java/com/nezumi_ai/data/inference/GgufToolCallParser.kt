package com.nezumi_ai.data.inference

import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GGUF / llama.rn 推論出力からツール呼び出しを抽出する。
 * Qwen / Gemma 等の `<tool_call>` タグ形式と JSON ブロックに対応。
 */
object GgufToolCallParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val toolCallTagPattern = Regex(
        "<tool_call>\\s*(.+?)\\s*</tool_call>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val bareToolCallJsonPattern = Regex(
        """\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"arguments"\s*:\s*(\{[\s\S]*?\}|"[\s\S]*?")\s*\}"""
    )

    private val toolResponseTagPattern = Regex(
        "<tool_response>\\s*(.+?)\\s*</tool_response>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    data class ParseResult(
        val toolCalls: List<ToolCall>,
        val textBeforeTools: String,
        val textAfterTools: String
    )

    /**
     * 本文を本文セグメントと tool_call セグメントに順序保存で分割したリスト。
     * UI のインラインカード描画で使う。
     */
    sealed class Segment {
        /** 普通の本文 (Markdown 可) */
        data class TextSegment(val text: String) : Segment()

        /**
         * `<tool_call>...</tool_call>` タグの位置に対応するセグメント。
         * @param index 本文中での 0 律基づきの出現順。ツール実行結果の順と揃えて使う。
         * @param toolCall パース完了の ToolCall (未完なら null)
         * @param rawJson タグの中身の生テキスト。UI アコーディオン健开時のフォールバック表示に使う。
         * @param isComplete 閉じタグ `</tool_call>` まで届いているかどうか。ストリーミング中の未完タグでは false となり、カードは Running 表示となる。
         */
        data class ToolCallSegment(
            val index: Int,
            val toolCall: ToolCall?,
            val rawJson: String,
            val isComplete: Boolean
        ) : Segment()
    }

    private val openToolCallTag = Regex("(?is)<tool_call>")
    private val closeToolCallTag = Regex("(?is)</tool_call>")

    /**
     * 本文を `<tool_call>...</tool_call>` の位置でセグメント化する。
     * - 閉じタグがまだ来ていないストリーミング中の未完タグは、末尾の Running カードとして 1件分割する。
     * - 閉じタグが先で開きタグがさきにない孤儿タグは本文として描画される。
     * - タグ中身の JSON パースに失敗しても `ToolCallSegment(toolCall=null, isComplete=true)` としてセグメントは保持する。
     * - `<tool_response>...</tool_response>` は履歴コンテキストには残すが、UI ではカード本文に生表示しないため、
     *   TextSegment 生成前に除去する。
     */
    fun parseSegments(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val segments = mutableListOf<Segment>()
        var cursor = 0
        var toolIndex = 0
        while (cursor < text.length) {
            val open = openToolCallTag.find(text, cursor) ?: break
            val before = stripToolResponseBlocks(text.substring(cursor, open.range.first))
            if (before.isNotEmpty()) {
                segments += Segment.TextSegment(before)
            }
            val payloadStart = open.range.last + 1
            val close = closeToolCallTag.find(text, payloadStart)
            if (close == null) {
                // 未完タグ → 末尾 Running セグメント
                val rawJson = text.substring(payloadStart)
                segments += Segment.ToolCallSegment(
                    index = toolIndex,
                    toolCall = null,
                    rawJson = rawJson.trim(),
                    isComplete = false
                )
                cursor = text.length
                break
            }
            val rawJson = text.substring(payloadStart, close.range.first).trim()
            val parsedCall = parseToolCallPayload(rawJson)
            segments += Segment.ToolCallSegment(
                index = toolIndex,
                toolCall = parsedCall,
                rawJson = rawJson,
                isComplete = true
            )
            toolIndex++
            cursor = close.range.last + 1
        }
        if (cursor < text.length) {
            val tail = stripToolResponseBlocks(text.substring(cursor))
            if (tail.isNotEmpty()) {
                segments += Segment.TextSegment(tail)
            }
        }
        return segments
    }

    /**
     * 本文に埋め込まれた `<tool_response>` を UI 用のカード一覧として取り出す。
     * DB の live `toolResultsJson` がまだ未反映でも、本文内の tool_response から
     * 直近ラウンドの結果をカードへ反映できるようにする。
     */
    fun parseToolResponseCards(text: String): List<ToolResultCard> {
        if (text.isEmpty()) return emptyList()
        return toolResponseTagPattern.findAll(text).mapNotNull { match ->
            runCatching {
                val obj = json.parseToJsonElement(match.groupValues[1].trim()).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content?.lowercase().orEmpty()
                if (name.isBlank()) return@runCatching null
                val content = obj["content"]
                val payload = when (content) {
                    is JsonObject -> content.toMap()
                    null -> emptyMap()
                    else -> mapOf("value" to content)
                }
                val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: true
                ToolResultCard(
                    toolName = name,
                    success = success,
                    payload = payload
                )
            }.getOrNull()
        }.toList()
    }

    fun parse(text: String): ParseResult {
        val matches = toolCallTagPattern.findAll(text).toList()
        val toolCalls = if (matches.isNotEmpty()) {
            matches.mapNotNull { match ->
                parseToolCallPayload(match.groupValues[1].trim())
            }
        } else {
            bareToolCallJsonPattern.findAll(text).mapNotNull { match ->
                parseToolCallPayload(match.value.trim())
            }.toList()
        }
        if (toolCalls.isEmpty()) {
            return ParseResult(emptyList(), text, "")
        }
        val matchRanges = if (matches.isNotEmpty()) {
            matches.map { it.range }
        } else {
            bareToolCallJsonPattern.findAll(text).map { it.range }.toList()
        }
        val firstStart = matchRanges.first().first
        val lastEnd = matchRanges.last().last + 1
        return ParseResult(
            toolCalls = toolCalls,
            textBeforeTools = text.substring(0, firstStart).trimEnd(),
            textAfterTools = text.substring(lastEnd).trimStart()
        )
    }

    fun hasToolCalls(text: String): Boolean =
        toolCallTagPattern.containsMatchIn(text) || bareToolCallJsonPattern.containsMatchIn(text)

    fun stripToolResponseBlocks(text: String): String {
        if (text.isEmpty()) return text
        return toolResponseTagPattern.replace(text, "")
    }

    fun formatToolResults(results: List<Pair<ToolCall, ToolExecutionResult>>): String {
        if (results.isEmpty()) return ""
        return buildString {
            appendLine()
            results.forEach { (call, result) ->
                appendLine("<tool_response>")
                appendLine("""{"name":"${call.name}","content":${resultPayloadJson(result)}}""")
                appendLine("</tool_response>")
            }
        }
    }

    private fun resultPayloadJson(result: ToolExecutionResult): String {
        return runCatching {
            val entries = result.payload.entries.joinToString(",") { (k, v) ->
                """"$k":${valueToJson(v)}"""
            }
            "{$entries}"
        }.getOrElse { """{"success":${result.success}}""" }
    }

    private fun valueToJson(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> "\"${value.toString().replace("\"", "\\\"")}\""
    }

    private fun parseToolCallPayload(payload: String): ToolCall? {
        return runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content
                ?: obj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            if (name.isNullOrBlank()) return null
            val argsElement = obj["arguments"]
                ?: obj["function"]?.jsonObject?.get("arguments")
            val args = when (argsElement) {
                is JsonObject -> argsElement.entries.associate { (k, v) ->
                    k to parseJsonValue(v)
                }
                else -> {
                    val raw = argsElement?.jsonPrimitive?.content.orEmpty()
                    if (raw.isBlank()) emptyMap()
                    else runCatching {
                        json.parseToJsonElement(raw).jsonObject.entries.associate { (k, v) ->
                            k to parseJsonValue(v)
                        }
                    }.getOrDefault(emptyMap())
                }
            }
            ToolCall(name = name, arguments = args)
        }.getOrNull()
    }

    private fun parseJsonValue(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is JsonObject -> element.entries.associate { (k, v) -> k to parseJsonValue(v) }
            else -> runCatching { element.jsonPrimitive.content }.getOrNull()
        }
    }
}
