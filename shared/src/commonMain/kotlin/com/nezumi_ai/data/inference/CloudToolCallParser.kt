package com.nezumi_ai.data.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** ツール呼び出し抽出 (commonMain 版、ParsedToolCall を返す)。実行用 parse/formatToolResults のみ。 */
object CloudToolCallParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ParseResult(val toolCalls: List<ParsedToolCall>, val hadTruncatedToolCall: Boolean = false)

    private val toolCallTagPattern = Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val bareToolCallJsonPattern = Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"arguments"\s*:\s*(\{[\s\S]*?\}|"[\s\S]*?")\s*\}""")
    private val openToolCallTag = Regex("(?is)<tool_call>")
    private val closeToolCallTag = Regex("(?is)</tool_call>")
    private val openGemma4ToolCallTag = Regex("(?is)<\\|tool_call>")
    private val closeGemma4ToolCallTag = Regex("(?is)<tool_call\\|>")
    private val gemma4CallBodyPattern = Regex("""(?is)\s*call\s*:\s*([A-Za-z_][A-Za-z0-9_\-]*)\s*(\{[\s\S]*\})\s*""")
    private val gemma4StringTokenPattern = Regex("<\\|\"\\|>((?:(?!<\\|\"\\|>)[\\s\\S])*)<\\|\"\\|>")

    private fun normalizeGemma4Json(raw: String): String {
        val quotesRestored = gemma4StringTokenPattern.replace(raw) { m -> "\"${m.groupValues[1].replace("\"", "\\\"")}\"" }
        return Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)""").replace(quotesRestored) { m ->
            "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
        }
    }

    fun parse(text: String, isGemma4: Boolean = false): ParseResult {
        val primary = if (isGemma4) parseGemma4(text) else parseGeneric(text)
        if (primary.toolCalls.isNotEmpty() || primary.hadTruncatedToolCall) return primary
        val alternate = if (isGemma4) parseGeneric(text) else parseGemma4(text)
        if (alternate.toolCalls.isEmpty() && !alternate.hadTruncatedToolCall) return primary
        return alternate
    }

    private fun parseGeneric(text: String): ParseResult {
        val toolCalls = mutableListOf<ParsedToolCall>()
        var cursor = 0
        var hadTruncated = false
        while (cursor < text.length) {
            val open = openToolCallTag.find(text, cursor) ?: break
            val payloadStart = open.range.last + 1
            val close = closeToolCallTag.find(text, payloadStart)
            if (close != null) {
                parseToolCallPayload(text.substring(payloadStart, close.range.first).trim())?.let { toolCalls += it }
                cursor = close.range.last + 1
            } else {
                val (salvaged, complete) = salvageGenericPayload(text.substring(payloadStart))
                if (salvaged != null && complete) toolCalls += salvaged else hadTruncated = true
                break
            }
        }
        if (toolCalls.isEmpty() && !hadTruncated) {
            bareToolCallJsonPattern.findAll(text).forEach { m -> parseToolCallPayload(m.value.trim())?.let { toolCalls += it } }
        }
        return if (toolCalls.isEmpty()) ParseResult(emptyList(), hadTruncated) else ParseResult(toolCalls, hadTruncated)
    }

    private fun parseGemma4(text: String): ParseResult {
        if (text.isEmpty()) return ParseResult(emptyList())
        val toolCalls = mutableListOf<ParsedToolCall>()
        var cursor = 0
        var hadTruncated = false
        while (cursor < text.length) {
            val open = openGemma4ToolCallTag.find(text, cursor) ?: break
            val payloadStart = open.range.last + 1
            val close = closeGemma4ToolCallTag.find(text, payloadStart)
            if (close != null) {
                parseGemma4CallPayload(text.substring(payloadStart, close.range.first))?.let { toolCalls += it }
                cursor = close.range.last + 1
            } else {
                val (salvaged, complete) = salvageGemma4Payload(text.substring(payloadStart))
                if (salvaged != null && complete) toolCalls += salvaged else hadTruncated = true
                break
            }
        }
        return if (toolCalls.isEmpty()) ParseResult(emptyList(), hadTruncated) else ParseResult(toolCalls, hadTruncated)
    }

    fun formatToolResults(results: List<Pair<ParsedToolCall, CloudToolExecutionResult>>): String {
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

    private fun resultPayloadJson(result: CloudToolExecutionResult): String {
        return runCatching {
            val entries = result.payloadForModel.entries.joinToString(",") { (k, v) -> "\"$k\":${valueToJson(v)}" }
            "{$entries}"
        }.getOrElse { """{"success":${result.success}}""" }
    }

    private fun valueToJson(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> "\"${value.toString().replace("\"", "\\\"")}\""
    }

    private fun parseToolCallPayload(payload: String): ParsedToolCall? {
        return runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content
                ?: obj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            if (name.isNullOrBlank()) return null
            val argsElement = obj["arguments"] ?: obj["function"]?.jsonObject?.get("arguments")
            val args = when (argsElement) {
                is JsonObject -> argsElement.entries.associate { (k, v) -> k to parseJsonValue(v) }
                else -> {
                    val raw = argsElement?.jsonPrimitive?.content.orEmpty()
                    if (raw.isBlank()) emptyMap()
                    else runCatching { json.parseToJsonElement(raw).jsonObject.entries.associate { (k, v) -> k to parseJsonValue(v) } }.getOrDefault(emptyMap())
                }
            }
            ParsedToolCall(name = name, arguments = args)
        }.getOrNull()
    }

    private fun parseGemma4CallPayload(payload: String): ParsedToolCall? {
        val match = gemma4CallBodyPattern.matchEntire(payload.trim()) ?: gemma4CallBodyPattern.find(payload) ?: return null
        val name = match.groupValues[1]
        val jsonPart = normalizeGemma4Json(match.groupValues[2])
        if (name.isBlank()) return null
        val args = runCatching { json.parseToJsonElement(jsonPart).jsonObject.entries.associate { (k, v) -> k to parseJsonValue(v) } }.getOrDefault(emptyMap())
        return ParsedToolCall(name = name, arguments = args)
    }

    private fun salvageGenericPayload(payload: String): Pair<ParsedToolCall?, Boolean> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null to false
        val startIdx = trimmed.indexOf('{')
        if (startIdx < 0) return null to false
        val jsonPart = trimmed.substring(startIdx)
        if (!bracesBalanced(jsonPart)) return null to false
        val call = parseToolCallPayload(jsonPart) ?: return null to false
        return call to true
    }

    private fun salvageGemma4Payload(payload: String): Pair<ParsedToolCall?, Boolean> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null to false
        val match = gemma4CallBodyPattern.find(trimmed) ?: return null to false
        val name = match.groupValues[1]
        if (!bracesBalanced(match.groupValues[2])) return null to false
        val jsonPart = normalizeGemma4Json(match.groupValues[2])
        if (name.isBlank()) return null to false
        val args = runCatching { json.parseToJsonElement(jsonPart).jsonObject.entries.associate { (k, v) -> k to parseJsonValue(v) } }.getOrDefault(emptyMap())
        return ParsedToolCall(name = name, arguments = args) to true
    }

    private fun bracesBalanced(text: String): Boolean {
        var depth = 0; var inString = false; var escape = false
        for (ch in text) {
            if (escape) { escape = false; continue }
            if (inString) { when (ch) { '\\' -> escape = true; '"' -> inString = false }; continue }
            when (ch) { '"' -> inString = true; '{' -> depth++; '}' -> { depth--; if (depth < 0) return false } }
        }
        return depth == 0 && !inString
    }

    private fun parseJsonValue(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is JsonObject -> element.entries.associate { (k, v) -> k to parseJsonValue(v) }
        else -> runCatching { element.jsonPrimitive.content }.getOrNull()
    }
}
