package com.nezumi_ai.data.inference

import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    data class ParseResult(
        val toolCalls: List<ToolCall>,
        val textBeforeTools: String,
        val textAfterTools: String
    )

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
