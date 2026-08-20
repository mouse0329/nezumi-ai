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

    // タグ literal は [ToolCallTags] に集約してある。
    // ここでは逆に regex の中に埋め込むパターンを作るためにエイリアスを取る。
    private const val TOOL_CALL_OPEN = ToolCallTags.TOOL_CALL_OPEN
    private const val TOOL_CALL_CLOSE = ToolCallTags.TOOL_CALL_CLOSE
    private const val GEMMA4_TOOL_CALL_OPEN = ToolCallTags.GEMMA4_TOOL_CALL_OPEN
    private const val GEMMA4_TOOL_CALL_CLOSE = ToolCallTags.GEMMA4_TOOL_CALL_CLOSE
    private const val TOOL_RESPONSE_OPEN = ToolCallTags.TOOL_RESPONSE_OPEN
    private const val TOOL_RESPONSE_CLOSE = ToolCallTags.TOOL_RESPONSE_CLOSE

    // ---- 汎用 (Qwen / Hermes-Pro / DeepSeek-R1 tool 等) 形式のパターン ----
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
     * Gemma 4 公式の文字列トークン `<|"|>...<|"|>` を検出するための正規表現。
     * キー名にはクォートが付かない (`key:value` 形式) ため、値側のこのトークンのみを
     * 通常の JSON ダブルクォートに変換すれば `kotlinx.serialization` でパースできる。
     */
    private val gemma4StringTokenPattern = Regex("<\\|\"\\|>((?:(?!<\\|\"\\|>)[\\s\\S])*)<\\|\"\\|>")

    /**
     * Gemma 4 公式表記の JSON 風引数 (`{location:<|"|>London<|"|>}`) を、
     * 通常の JSON (`{"location":"London"}`) に正規化する。
     *
     * 変換ルール:
     *   1. 文字列トークン `<|"|>...<|"|>` → `"..."` (中身に含まれる `"` はエスケープ)
     *   2. 裸のキー名 `key:` → `"key":` (英数・アンダースコアのみ、値側の `:` は対象外)
     *
     * 既に通常の JSON (`"key":"value"`) で来た場合はこの変換で実質変化しないため、
     * モデル側の出力揺れ (公式トークン形式 / 素の JSON 形式のどちらでも) を吸収できる。
     */
    private fun normalizeGemma4Json(raw: String): String {
        val quotesRestored = gemma4StringTokenPattern.replace(raw) { m ->
            "\"${m.groupValues[1].replace("\"", "\\\"")}\""
        }
        return Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)""").replace(quotesRestored) { m ->
            "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
        }
    }

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
     * @param fellBackToAlternateFormat モデル判定と逆のツールコール形式を救済パースで採用したか。
     *   true のとき、[truncatedTagIsGemma4] は「実際に本文中に出現した形式」を指す
     *   (閉じタグ補完 [closingTagFor] が正しいタグを選べるようにするため)。
     */
    data class ParseResult(
        val toolCalls: List<ToolCall>,
        val textBeforeTools: String,
        val textAfterTools: String,
        val hadTruncatedToolCall: Boolean = false,
        val truncatedTagIsGemma4: Boolean = false,
        val truncatedToolName: String? = null,
        val fellBackToAlternateFormat: Boolean = false
    )

    /**
     * 本文を本文セグメントと tool_call セグメントに順序保存で分割したリスト。
     * UI のインラインカード描画で使う。
     */
    sealed class Segment {
        /** 普通の本文 (Markdown 可) */
        data class TextSegment(val text: String) : Segment()

        /**
         * ツールコールセグメントの完了状態。
         *
         * UI 表示のチェックマーク (完了) は [COMPLETE] のときのみ付けてよい。
         *
         * [PENDING] は「開きタグは来たが閉じタグをまだ観測していない」ストリーミング中の
         * 状態全般 (JSON がまだバランスしていない書きかけの引数も含む)。モデルが追加の
         * 引数やテキストをまだ書いている可能性があるため、実行対象にも確定完了表示にも
         * せず、UI は Running (実行中/生成中) として表示する。ここでツール名や部分的な
         * 引数を rawJson から拾えれば参考表示に使ってよいが、判定の主目的ではない。
         *
         * [TRUNCATED] は [parseSegments] (ストリーミング表示用、まだ生成が続いている
         * 可能性がある文字列に対して都度呼ばれる) では使用しない。真のトークン切れ判定は
         * ストリームそのものが終了した後でなければ行えないため、[GgufInferenceEngine] が
         * [parse] の `ParseResult.hadTruncatedToolCall` を見て最終確定させる責務を持つ。
         */
        enum class CompletionStatus { COMPLETE, PENDING, TRUNCATED }

        /**
         * `<tool_call>...</tool_call>` (汎用) または `<|tool_call>...<tool_call|>` (Gemma 4) の
         * 位置に対応するセグメント。
         * @param index 本文中での 0 律基づきの出現順。ツール実行結果の順と揃えて使う。
         * @param toolCall パース完了の ToolCall (未完/壊れている場合は null)
         * @param rawJson タグの中身の生テキスト。UI アコーディオン展開時のフォールバック表示に使う。
         * @param status この呼び出しセグメントの完了状態 ([CompletionStatus] 参照)。
         * @param isComplete 後方互換用プロパティ。`status == COMPLETE` の場合のみ true。
         *   新規コードは [status] を直接見ること。
         */
        data class ToolCallSegment(
            val index: Int,
            val toolCall: ToolCall?,
            val rawJson: String,
            val status: CompletionStatus
        ) : Segment() {
            val isComplete: Boolean get() = status == CompletionStatus.COMPLETE
        }
    }

    /**
     * 本文を `<tool_call>...</tool_call>` / `<|tool_call>...<tool_call|>` の位置でセグメント化する。
     * - 汎用形式・Gemma4 形式の両方を同時に走査し、本文中で最初に来た開きタグを優先する。
     * - 閉じタグを実際に観測できたセグメントのみ [Segment.CompletionStatus.COMPLETE] として
     *   確定表示 (チェックマーク) にする。web_search / web_fetch のように末尾の文字列引数
     *   (query, url) が長く、閉じタグの前に JSON だけがバランスしてしまう瞬間があるため、
     *   「JSON バランス OK」を完了の判定基準にしてはならない。
     * - 閉じタグ無しで JSON バランスが OK な場合は [Segment.CompletionStatus.PENDING]
     *   (保留中・実行前) として表示する。モデルがまだ書き続けている可能性があるため、
     *   実行もチェックマーク表示もしない。閉じタグが来て初めて COMPLETE に確定する。
     * - JSON バランスが崩れている (トークン切れ) 場合は [Segment.CompletionStatus.TRUNCATED]
     *   として、壊れたことが分かる形で保持する。
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
                // 閉じタグ未観測。この時点ではまだモデルが書き続けている最中であり、
                // 「JSON がまだ波括弧レベルでバランスしていない」のはストリーミング中は
                // ごく普通の状態 (例: `<tool_call>\n{"name":"web_search",` の時点でここを通る)。
                //
                // 以前はここで「バランス未達 = TRUNCATED (トークン切れ)」として扱っていたが、
                // これは「まだ書いている途中」と「本当に生成が途切れて二度と続かない」を
                // 混同していた。トークン切れの真の判定はストリームそのものが終了した後で
                // なければ行えず、逐次呼ばれる parseSegments (表示用、まだ生成が続いている
                // 可能性がある文字列に対して都度呼ばれる) の中では判定できない。
                // 実際のトークン切れ検出・失敗マーカー化は GgufInferenceEngine 側で
                // GgufToolCallParser.parse() の hadTruncatedToolCall を見て行う (そちらは
                // ストリームが実際に終わった後の最終テキストに対して呼ばれるので正しく判定できる)。
                //
                // よって parseSegments は閉じタグが来るまでは常に PENDING (=Running 表示) とし、
                // ツールを開いた瞬間から閉じタグが来るまでの間、UI に何も進行感が出ない/
                // 唐突にエラー表示になる問題を解消する。
                val rawJson = text.substring(payloadStart)
                val (salvagedCall, _) = if (useGemma4) {
                    salvageGemma4Payload(rawJson)
                } else {
                    salvageGenericPayload(rawJson)
                }
                segments += Segment.ToolCallSegment(
                    index = toolIndex,
                    // salvagedCall は JSON がバランスしていれば参考表示用に埋まる。
                    // バランスしていなければ null (rawJson だけで「実行中…」表示させる)。
                    toolCall = salvagedCall,
                    rawJson = rawJson.trim(),
                    status = Segment.CompletionStatus.PENDING
                )
                // PENDING はまだ確定していないため toolIndex は進めない。
                // 閉じタグが来て COMPLETE になったときに初めて次のインデックスへ進む。
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
                status = Segment.CompletionStatus.COMPLETE
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
     *   (`<|tool_call>call:NAME{...}<tool_call|>`) を優先する。
     *
     * クロスフォーマット救済:
     *   モデルは学習データの影響で、指定された形式と逆のツールコール形式を出すことがある
     *   (Gemma 4 が汎用 `<tool_call>` を出す / Qwen 等が `<|tool_call>` を出す)。
     *   優先形式で 1 件も確定できなかった場合に限り、もう一方の形式にも fallback して
     *   「形式違いでツールが一切実行されない」事態を防ぐ。優先形式で何らかの結果
     *   (確定ツール or トークン切れ検知) が得られた場合は従来通り fallback しない。
     */
    fun parse(text: String, isGemma4: Boolean = false): ParseResult {
        val primary = if (isGemma4) parseGemma4(text) else parseGeneric(text)
        if (primary.toolCalls.isNotEmpty() || primary.hadTruncatedToolCall) {
            return primary
        }
        // 優先形式で何も拾えなかったときだけ、反対側の形式を試す。
        // 反対側でも何もなければ優先側の結果 (全文を textBeforeTools に入れたもの) を返し、
        // 呼び出し元の「ツールなし = 通常回答」分岐を変えない。
        val alternate = if (isGemma4) parseGeneric(text) else parseGemma4(text)
        if (alternate.toolCalls.isEmpty() && !alternate.hadTruncatedToolCall) {
            return primary
        }
        return alternate.copy(fellBackToAlternateFormat = true)
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
        // モデルが判定と逆の形式を出すケース (クロスフォーマット) でも検知できるよう、
        // Gemma 4 開きタグはモデル種別に関係なく判定対象に含める。
        return openGemma4ToolCallTag.containsMatchIn(text)
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
                appendLine(TOOL_RESPONSE_OPEN)
                appendLine("""{"name":"${call.name}","content":${resultPayloadJson(result)}}""")
                appendLine(TOOL_RESPONSE_CLOSE)
            }
        }
    }

    /**
     * トークン切れした未完タグ用の閉じタグを返す。
     * 呼び出し元 (GgufInferenceEngine) が本文末尾に append して、以降の履歴/プロンプトの
     * タグ整合を保つのに使う。
     */
    fun closingTagFor(isGemma4: Boolean): String =
        if (isGemma4) GEMMA4_TOOL_CALL_CLOSE else TOOL_CALL_CLOSE

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
            appendLine(TOOL_RESPONSE_OPEN)
            appendLine(
                """{"name":"$escapedName","content":{"success":false,"error":"truncated",""" +
                    """"message":"Tool call was cut off before completion (token budget exhausted). """ +
                    """Please retry with a shorter arguments payload."}}"""
            )
            appendLine(TOOL_RESPONSE_CLOSE)
        }
    }

    private fun resultPayloadJson(result: ToolExecutionResult): String {
        return runCatching {
            // モデルへ送り返すのは payload ではなく payloadForModel。
            // convert_md_to_document のように UI カードにはフル本文が必要でも、
            // モデルにはその全文を再送する必要がないツールは、ここで軽量な
            // 要約ペイロードに差し替わる (payloadForModel の doc コメント参照)。
            val entries = result.payloadForModel.entries.joinToString(",") { (k, v) ->
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
        val jsonPart = normalizeGemma4Json(match.groupValues[2])
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
        // ブレースのバランス判定は <|"|> 変換前の生テキストで行う (トークンは { } を含まないため
        // 判定結果は変わらないが、変換処理そのものが正規表現ベースで文字列境界を前提にしており、
        // 波括弧が閉じていない = 文字列トークンも閉じていない可能性があるため生テキストが安全)。
        if (!bracesBalanced(match.groupValues[2])) return null to false
        val jsonPart = normalizeGemma4Json(match.groupValues[2])
        if (name.isBlank()) return null to false
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
