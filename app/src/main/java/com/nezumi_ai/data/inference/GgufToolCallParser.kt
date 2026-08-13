package com.nezumi_ai.data.inference

import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GGUF / llama.rn 推論出力からツール呼び出しを抽出する。
 *
 * 対応する形式:
 *   1. 汎用 (Qwen 系など): `<tool_call>{"name":..,"arguments":..}</tool_call>` および
 *      裸の JSON ブロック `{"name":..,"arguments":..}`。
 *   2. Gemma 4 系: `<|tool_call>call:NAME{args-json}<tool_call|>` の Google 公式仕様。
 *
 * 閉じタグ・JSON 完結性のパターン整理:
 *   a. 閉じタグ有り + JSON 有効           → 正常に [ToolCall] を組み立てて実行
 *   b. 閉じタグ無し + JSON バランス OK    → 「閉じタグ忘れ」として救済し、[ToolCall] を組み立てて実行
 *   c. 閉じタグ無し + JSON バランス NG    → 「トークン切れ」として未完扱い。実行はせず、呼び出し元に
 *      [ParseResult.hadTruncatedToolCall] = true で通知する。呼び出し元 (GgufInferenceEngine) は
 *      失敗ステータスの ToolResultCard を合成し、閉じタグを補完してモデルに戻す。
 *
 * Gemma 4 のパースを有効にするには、呼び出し元で [PromptBuilder.isGemma4Model] の判定結果を
 * `isGemma4` として渡す。UI 描画向けの [parseSegments] / [parseToolResponseCards] /
 * [stripToolResponseBlocks] は「メッセージ本文がどちらの形式で生成されたか」を復元できないため、
 * 常に両方を同時に走査する (パラメータを取らない) 仕様。
 */
object GgufToolCallParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- 汎用 (Qwen 等) 形式のパターン ----
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

    private val openToolCallTag = Regex("(?is)<tool_call>")
    private val closeToolCallTag = Regex("(?is)</tool_call>")

    // ---- Gemma 4 (Google 公式) 形式のパターン ----
    //
    // 仕様: `<|tool_call>call:NAME{"arg":..}<tool_call|>`
    //   - 開きタグ: `<|tool_call>` (通常のタグ文字とは別トークン)
    //   - 中身: `call:` プレフィックス → ツール名 → `{` から始まる JSON 引数
    //   - 閉じタグ: `<tool_call|>` (Gemma 4 は稀に閉じタグを吐かずに終端することがある)
    private val openGemma4ToolCallTag = Regex("(?is)<\\|tool_call>")
    private val closeGemma4ToolCallTag = Regex("(?is)<tool_call\\|>")

    /**
     * Gemma 4 タグ内本文から `call:NAME{...}` を抽出するための正規表現。
     * ツール名は英数・アンダースコア・ハイフンを許容。
     */
    private val gemma4CallBodyPattern = Regex(
        """(?is)\s*call\s*:\s*([A-Za-z_][A-Za-z0-9_\-]*)\s*(\{[\s\S]*\})\s*"""
    )

    /**
     * `parse()` の返却値。
     *
     * @param toolCalls 実行対象として確定したツール呼び出し (正常完了 + 閉じタグ忘れ救済分)。
     * @param textBeforeTools 先頭のツールコール開きタグより前の本文。
     * @param textAfterTools 末尾のツールコールより後の本文 (トークン切れ時は空文字)。
     * @param hadTruncatedToolCall 本文末尾に「JSON が途中で切れた未完タグ」があったかどうか。
     *   true のとき、呼び出し元は失敗ステータスの [ToolResultCard] を合成して閉じタグを補完し、
     *   モデルに `<tool_response>` として実行失敗を返す責務を負う。
     * @param truncatedTagIsGemma4 トークン切れした未完タグが Gemma 4 形式 (`<|tool_call>`) だったか。
     *   閉じタグ補完 (`</tool_call>` vs `<tool_call|>`) の判断に使う。
     * @param truncatedToolName トークン切れ時に、名前だけでも読み取れたら埋める (無理なら null)。
     *   モデルに返す `<tool_response>` の name として使う。
     */
    data class ParseResult(
        val toolCalls: List<ToolCall>,
        val textBeforeTools: String,
        val textAfterTools: String,
        val hadTruncatedToolCall: Boolean = false,
        val truncatedTagIsGemma4: Boolean = false,
        val truncatedToolName: String? = null
    )

    /**
     * 本文を本文セグメントと tool_call セグメントに順序保存で分割したリスト。
     * UI のインラインカード描画で使う。
     */
    sealed class Segment {
        /** 普通の本文 (Markdown 可) */
        data class TextSegment(val text: String) : Segment()

        /**
         * `<tool_call>...</tool_call>` (汎用) または `<|tool_call>...<tool_call|>` (Gemma 4) の
         * 位置に対応するセグメント。
         * @param index 本文中での 0 律基づきの出現順。ツール実行結果の順と揃えて使う。
         * @param toolCall パース完了の ToolCall (未完なら null)
         * @param rawJson タグの中身の生テキスト。UI アコーディオン展開時のフォールバック表示に使う。
         * @param isComplete 閉じタグまで届いているかどうか。ストリーミング中の未完タグでは false となり、
         *   カードは Running 表示となる。
         */
        data class ToolCallSegment(
            val index: Int,
            val toolCall: ToolCall?,
            val rawJson: String,
            val isComplete: Boolean
        ) : Segment()
    }

    /**
     * 本文を `<tool_call>...</tool_call>` / `<|tool_call>...<tool_call|>` の位置でセグメント化する。
     * - 汎用形式・Gemma4 形式の両方を同時に走査し、本文中で最初に来た開きタグを優先する。
     * - 閉じタグがまだ来ていないストリーミング中の未完タグは、末尾の Running カードとして 1 件分割する。
     * - タグ中身のパースに失敗しても `ToolCallSegment(toolCall=null, isComplete=true)` として保持する。
     * - `<tool_response>...</tool_response>` は履歴コンテキストには残すが、UI では非表示にするため
     *   TextSegment 生成前に除去する。
     */
    fun parseSegments(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val segments = mutableListOf<Segment>()
        var cursor = 0
        var toolIndex = 0
        while (cursor < text.length) {
            val openGeneric = openToolCallTag.find(text, cursor)
            val openGemma4 = openGemma4ToolCallTag.find(text, cursor)
            // 本文中で先に出現する形式を採用する。
            val useGemma4 = when {
                openGeneric == null && openGemma4 == null -> break
                openGeneric == null -> true
                openGemma4 == null -> false
                else -> openGemma4.range.first < openGeneric.range.first
            }
            val open = if (useGemma4) openGemma4!! else openGeneric!!
            val before = stripToolResponseBlocks(text.substring(cursor, open.range.first))
            if (before.isNotEmpty()) {
                segments += Segment.TextSegment(before)
            }
            val payloadStart = open.range.last + 1
            val close = if (useGemma4) {
                closeGemma4ToolCallTag.find(text, payloadStart)
            } else {
                closeToolCallTag.find(text, payloadStart)
            }
            if (close == null) {
                val rawJson = text.substring(payloadStart)
                val (salvagedCall, isCompleteSalvage) = if (useGemma4) {
                    salvageGemma4Payload(rawJson)
                } else {
                    salvageGenericPayload(rawJson)
                }
                segments += Segment.ToolCallSegment(
                    index = toolIndex,
                    toolCall = salvagedCall,
                    rawJson = rawJson.trim(),
                    // 名前 + JSON が揃っていれば「閉じタグ忘れ」でも確定扱い (isComplete=true)。
                    // JSON が途中で切れた「トークン切れ」ケースはストリーミング中 Running のまま
                    // (isComplete=false) にして、UI 上は「実行中/失敗待ち」表示にする。
                    // 実際の失敗マーカー化は GgufInferenceEngine 側で行う。
                    isComplete = isCompleteSalvage
                )
                if (isCompleteSalvage) toolIndex++
                cursor = text.length
                break
            }
            val rawJson = text.substring(payloadStart, close.range.first).trim()
            val parsedCall = if (useGemma4) {
                parseGemma4CallPayload(rawJson)
            } else {
                parseToolCallPayload(rawJson)
            }
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

    /**
     * 出力からツール呼び出しを抽出する (推論エンジンからの呼び出し用)。
     *
     * @param isGemma4 モデルが Gemma 4 系かどうか。Gemma 4 のときは Google 公式仕様
     *   (`<|tool_call>call:NAME{...}<tool_call|>`) を優先し、汎用形式へは fallback しない。
     *   Gemma 4 以外のときは従来通り `<tool_call>` タグ + 裸 JSON を見に行く。
     */
    fun parse(text: String, isGemma4: Boolean = false): ParseResult {
        if (isGemma4) return parseGemma4(text)
        return parseGeneric(text)
    }

    /**
     * 汎用 (`<tool_call>...</tool_call>`) 形式のパース。
     * 閉じタグ忘れ (JSON バランス OK) の救済と、トークン切れ (JSON 途中打ち切り) の
     * 失敗マーカー通知に対応する。
     */
    private fun parseGeneric(text: String): ParseResult {
        val toolCalls = mutableListOf<ToolCall>()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        var hadTruncated = false
        var truncatedName: String? = null
        // Step 1: <tool_call>...</tool_call> の完全ペアを順に消化する。
        while (cursor < text.length) {
            val open = openToolCallTag.find(text, cursor) ?: break
            val payloadStart = open.range.last + 1
            val close = closeToolCallTag.find(text, payloadStart)
            if (close != null) {
                val payload = text.substring(payloadStart, close.range.first).trim()
                parseToolCallPayload(payload)?.let {
                    toolCalls += it
                    ranges += open.range.first..close.range.last
                }
                cursor = close.range.last + 1
            } else {
                // 閉じタグ無し。JSON バランスが取れていれば救済、そうでなければトークン切れとして通知。
                val payload = text.substring(payloadStart)
                val (salvaged, isComplete) = salvageGenericPayload(payload)
                if (salvaged != null && isComplete) {
                    toolCalls += salvaged
                    ranges += open.range.first..(text.length - 1)
                } else {
                    // JSON が途中で切れている = トークン切れの実行失敗マーカー。
                    hadTruncated = true
                    truncatedName = extractGenericToolName(payload)
                }
                break
            }
        }
        // Step 2: <tool_call> タグが 1 件もなく、かつ裸 JSON があれば従来通り拾う。
        if (toolCalls.isEmpty() && !hadTruncated) {
            val bareMatches = bareToolCallJsonPattern.findAll(text).toList()
            if (bareMatches.isNotEmpty()) {
                bareMatches.forEach { m ->
                    parseToolCallPayload(m.value.trim())?.let {
                        toolCalls += it
                        ranges += m.range
                    }
                }
            }
        }
        if (toolCalls.isEmpty() && !hadTruncated) {
            return ParseResult(emptyList(), text, "")
        }
        val firstStart = ranges.firstOrNull()?.first ?: text.length
        val lastEnd = ranges.lastOrNull()?.let { it.last + 1 } ?: text.length
        return ParseResult(
            toolCalls = toolCalls,
            textBeforeTools = text.substring(0, firstStart).trimEnd(),
            textAfterTools = if (lastEnd <= text.length) text.substring(lastEnd).trimStart() else "",
            hadTruncatedToolCall = hadTruncated,
            truncatedTagIsGemma4 = false,
            truncatedToolName = truncatedName
        )
    }

    private fun parseGemma4(text: String): ParseResult {
        if (text.isEmpty()) return ParseResult(emptyList(), text, "")
        val toolCalls = mutableListOf<ToolCall>()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        var hadTruncated = false
        var truncatedName: String? = null
        while (cursor < text.length) {
            val open = openGemma4ToolCallTag.find(text, cursor) ?: break
            val payloadStart = open.range.last + 1
            val close = closeGemma4ToolCallTag.find(text, payloadStart)
            if (close != null) {
                val payload = text.substring(payloadStart, close.range.first)
                parseGemma4CallPayload(payload)?.let {
                    toolCalls += it
                    ranges += open.range.first..close.range.last
                }
                cursor = close.range.last + 1
            } else {
                // 閉じタグ忘れの救済: `call:NAME{...}` の JSON が完結していれば確定扱い。
                // 完結していなければトークン切れとして呼び出し元に通知する。
                val payload = text.substring(payloadStart)
                val (salvaged, isComplete) = salvageGemma4Payload(payload)
                if (salvaged != null && isComplete) {
                    toolCalls += salvaged
                    ranges += open.range.first..(text.length - 1)
                } else {
                    hadTruncated = true
                    truncatedName = extractGemma4ToolName(payload)
                }
                break
            }
        }
        if (toolCalls.isEmpty() && !hadTruncated) {
            return ParseResult(emptyList(), text, "")
        }
        val firstStart = ranges.firstOrNull()?.first ?: text.length
        val lastEnd = ranges.lastOrNull()?.let { it.last + 1 } ?: text.length
        return ParseResult(
            toolCalls = toolCalls,
            textBeforeTools = text.substring(0, firstStart).trimEnd(),
            textAfterTools = if (lastEnd <= text.length) text.substring(lastEnd).trimStart() else "",
            hadTruncatedToolCall = hadTruncated,
            truncatedTagIsGemma4 = true,
            truncatedToolName = truncatedName
        )
    }

    /**
     * 本文中にツール呼び出しタグが 1 つでも存在するかを返す。
     * `isGemma4` を渡すと Gemma 4 用のタグも同時に判定対象になる。
     */
    fun hasToolCalls(text: String, isGemma4: Boolean = false): Boolean {
        val generic = toolCallTagPattern.containsMatchIn(text) ||
            bareToolCallJsonPattern.containsMatchIn(text) ||
            openToolCallTag.containsMatchIn(text)
        if (generic) return true
        return isGemma4 && openGemma4ToolCallTag.containsMatchIn(text)
    }

    fun stripToolResponseBlocks(text: String): String {
        if (text.isEmpty()) return text
        return toolResponseTagPattern.replace(text, "")
    }

    /**
     * ツール実行結果を、次ラウンド送信用の `<tool_response>` ブロック文字列にフォーマットする。
     *
     * 現状は汎用形式・Gemma 4 系ともに `<tool_response>` タグでモデルに戻している。
     * Gemma 4 の公式チャットテンプレートも `<tool_response>` を受理する構造のため、
     * 呼び出し元でモデル種別を渡す必要は現状ない。将来 Gemma 4 側で
     * `<|tool_response>...<tool_response|>` を要求するようになった場合は [isGemma4] で分岐する。
     */
    fun formatToolResults(
        results: List<Pair<ToolCall, ToolExecutionResult>>,
        @Suppress("UNUSED_PARAMETER") isGemma4: Boolean = false
    ): String {
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

    /**
     * トークン切れした未完タグ用の閉じタグを返す。
     * 呼び出し元 (GgufInferenceEngine) が本文末尾に append して、以降の履歴/プロンプトの
     * タグ整合を保つのに使う。
     */
    fun closingTagFor(isGemma4: Boolean): String = if (isGemma4) "<tool_call|>" else "</tool_call>"

    /**
     * トークン切れした未完タグ用の実行失敗 [ToolResultCard] を合成する。
     * `<tool_response>` としてモデルに戻すことで、モデルが「今のツールコールは失敗した」と
     * 認識して自然に立て直せるようにする。
     *
     * @param toolName 名前だけでも読み取れた場合はそれを、無理なら "unknown" を渡す。
     */
    fun buildTruncatedFailureCard(toolName: String?): ToolResultCard {
        val name = toolName?.takeIf { it.isNotBlank() }?.lowercase() ?: "unknown"
        return ToolResultCard(
            toolName = name,
            success = false,
            payload = mapOf(
                "success" to kotlinx.serialization.json.JsonPrimitive(false),
                "error" to kotlinx.serialization.json.JsonPrimitive("truncated"),
                "message" to kotlinx.serialization.json.JsonPrimitive(
                    "Tool call was cut off before completion (token budget exhausted). " +
                        "Please retry with a shorter arguments payload."
                )
            )
        )
    }

    /**
     * トークン切れした未完タグ用の、モデル送信用 `<tool_response>` テキストを合成する。
     * `formatToolResults` と同じ形式で、成功=false / error=truncated を明示する。
     */
    fun formatTruncatedFailureResponse(toolName: String?): String {
        val name = toolName?.takeIf { it.isNotBlank() } ?: "unknown"
        val escapedName = name.replace("\"", "\\\"")
        return buildString {
            appendLine()
            appendLine("<tool_response>")
            appendLine(
                """{"name":"$escapedName","content":{"success":false,"error":"truncated",""" +
                    """"message":"Tool call was cut off before completion (token budget exhausted). """ +
                    """Please retry with a shorter arguments payload."}}"""
            )
            appendLine("</tool_response>")
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

    /**
     * Gemma 4 タグ内本文 `call:NAME{...}` から [ToolCall] を作る。
     * 名前部分・JSON 部分ともにトリム前提。JSON パースに失敗しても引数空で確定させる
     * (`call:list_alarms{}` のような 0 引数呼び出しは有効)。
     */
    private fun parseGemma4CallPayload(payload: String): ToolCall? {
        val match = gemma4CallBodyPattern.matchEntire(payload.trim()) ?: run {
            // matchEntire が失敗する場合は「余分な空白/改行付き」ケースを find で救済。
            gemma4CallBodyPattern.find(payload) ?: return null
        }
        val name = match.groupValues[1]
        val jsonPart = match.groupValues[2]
        if (name.isBlank()) return null
        val args = runCatching {
            json.parseToJsonElement(jsonPart).jsonObject.entries.associate { (k, v) ->
                k to parseJsonValue(v)
            }
        }.getOrDefault(emptyMap())
        return ToolCall(name = name, arguments = args)
    }

    /**
     * 汎用形式 (`<tool_call>...`) で閉じタグが来ていない未完ペイロードに対する救済パース。
     *   - JSON `{...}` が波括弧レベルで完結していれば確定扱いで [ToolCall] と `true` を返す。
     *   - JSON が途中で切れている (バランス NG) 場合は `null, false`。
     */
    private fun salvageGenericPayload(payload: String): Pair<ToolCall?, Boolean> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null to false
        // 汎用形式は「JSON オブジェクト全体で name / arguments を持つ」形。
        // `{` の開始位置から最後まで見て波括弧バランスが取れているかで判定する。
        val startIdx = trimmed.indexOf('{')
        if (startIdx < 0) return null to false
        val jsonPart = trimmed.substring(startIdx)
        if (!bracesBalanced(jsonPart)) return null to false
        val call = parseToolCallPayload(jsonPart) ?: return null to false
        return call to true
    }

    /**
     * Gemma 4 で閉じタグが来ていない未完タグに対する救済パース。
     *   - 名前と JSON (`{...}`) がバランス取れていれば確定扱いで [ToolCall] と `true` を返す。
     *   - 名前だけで `{` が来ていない (= 名前自体が生成途中の可能性) 場合は `null, false`。
     *   - JSON が途中で切れている (バランス NG) 場合も `null, false`。
     */
    private fun salvageGemma4Payload(payload: String): Pair<ToolCall?, Boolean> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null to false
        val match = gemma4CallBodyPattern.find(trimmed) ?: return null to false
        val name = match.groupValues[1]
        val jsonPart = match.groupValues[2]
        if (name.isBlank()) return null to false
        // ブレースがバランスしていれば「JSON が完結している」と見なす。
        if (!bracesBalanced(jsonPart)) return null to false
        val args = runCatching {
            json.parseToJsonElement(jsonPart).jsonObject.entries.associate { (k, v) ->
                k to parseJsonValue(v)
            }
        }.getOrDefault(emptyMap())
        return ToolCall(name = name, arguments = args) to true
    }

    /**
     * トークン切れした汎用形式ペイロードから、可能な限り `"name":"..."` を読み取る。
     * `<tool_response>` に返す name として使う (失敗時は null)。
     */
    private fun extractGenericToolName(payload: String): String? {
        val m = Regex("""(?is)"name"\s*:\s*"([^"\\]+)"""").find(payload) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * トークン切れした Gemma 4 形式ペイロードから、可能な限りツール名を読み取る。
     * `call:NAME` の NAME 部分が読み取れたらそれを返す (失敗時は null)。
     */
    private fun extractGemma4ToolName(payload: String): String? {
        val m = Regex("""(?is)call\s*:\s*([A-Za-z_][A-Za-z0-9_\-]*)""").find(payload) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 文字列内の `{}` が (文字列リテラル内の `{`/`}` を無視した上で) バランスしているかを判定する。
     * JSON パースを試みる前段の軽量チェック。
     */
    private fun bracesBalanced(text: String): Boolean {
        var depth = 0
        var inString = false
        var escape = false
        for (ch in text) {
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                when (ch) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }

    private fun parseJsonValue(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is JsonObject -> element.entries.associate { (k, v) -> k to parseJsonValue(v) }
            else -> runCatching { element.jsonPrimitive.content }.getOrNull()
        }
    }
}
