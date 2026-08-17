package com.nezumi_ai.presentation.viewmodel.usecase

import android.content.Context
import android.util.Log
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.PromptBuilder
import org.json.JSONObject

/**
 * クラスタ C (プロンプト構築) を ChatViewModel から切り出した純粋ロジック層。
 *
 * Android 依存は [Context] を受け取る既存ビルダー ([PromptBuilder]) への橋渡しに限定し、
 * 文字列処理そのものはここに集約する。commonMain 化の際には Context 依存部分を
 * インターフェース越しに差し替える想定。
 * (ツール定義注入クラスタは今回の分割対象外のため、呼び出し元のインラインロジックは
 *  ChatViewModel 側に据え置きとする)
 */
class PromptBuildingUseCase {

    /** 1トークン ≈ 4文字の換算比率 (ChatViewModel から移管) */
    val tokenToCharRatio: Int = TOKEN_TO_CHAR_RATIO

    // ---- テキスト整形 / サニタイズ (純粋ロジック) ----

    fun trimPromptToWindow(prompt: String, contextWindowTokens: Int): String {
        val maxChars = contextWindowTokens * TOKEN_TO_CHAR_RATIO
        if (prompt.length <= maxChars) return prompt
        val trimmed = prompt.takeLast(maxChars)
        Log.d(TAG, "TRIM_PROMPT: contextWindow=$contextWindowTokens tokens (~${maxChars} chars) | original=${prompt.length} -> trimmed=${trimmed.length} chars")
        return trimmed
    }

    fun isGgufEngineModel(engineModelName: String): Boolean =
        engineModelName.lowercase().endsWith(".gguf")

    fun isLikelyMarkdownTable(content: String): Boolean {
        if (!content.contains('|')) return false
        val lines = content.lines()
        if (lines.size < 2) return false
        return lines.zipWithNext().any { (a, b) ->
            a.contains('|') && (b.contains("|---") || b.contains("| :") || b.contains("|-"))
        }
    }

    fun mergeStreamingChunk(current: String, chunk: String): String {
        if (chunk.isEmpty()) return current
        if (current.isEmpty()) return chunk
        if (chunk == current) return current

        // 累積全文が届くケース
        if (chunk.startsWith(current)) return chunk
        // 既に反映済みの重複delta。短い chunk は通常単語にも出るので捨てない。
        if (chunk.length >= 8 && current.endsWith(chunk)) return current
        // 巻き戻った累積全文らしきケースは現状維持。
        if (chunk.length >= 32 && chunk.length >= current.length / 2 && current.startsWith(chunk)) {
            return current
        }

        // 保守的な重複検出
        val overlap = suffixPrefixOverlapConservative(current, chunk)
        if (overlap > 0) {
            val merged = current + chunk.substring(overlap)
            if (merged.length >= current.length) {
                return merged
            }
        }
        return current + chunk
    }

    private fun suffixPrefixOverlapConservative(left: String, right: String): Int {
        val maxCheckSize = minOf(left.length, right.length, 50)
        val minCheckSize = 8
        if (maxCheckSize < minCheckSize) return 0

        for (size in maxCheckSize downTo minCheckSize) {
            if (left.regionMatches(left.length - size, right, 0, size, ignoreCase = false)) {
                return size
            }
        }
        return 0
    }

    /** 可視本文用に <think>...</think> ブロックだけを取り除く。 */
    fun stripThinkSectionsForDisplay(raw: String): String {
        if (raw.isEmpty()) return raw
        var text = raw
        while (true) {
            val start = text.indexOf("<think>")
            if (start < 0) break
            val end = text.indexOf("</think>", start)
            text = if (end >= 0) {
                text.removeRange(start, end + "</think>".length)
            } else {
                text.substring(0, start)
            }
        }
        return text.trim()
    }

    fun sanitizeAssistantOutputForModel(engineModelName: String, text: String): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ""
        if (!engineModelName.lowercase().endsWith(".gguf")) return normalized
        val noLoop = stripSyntheticRoleLoopTail(normalized)
        return noLoop.replace(
            Regex("^(?i)(?:Assistant|アシスタント)\\s*[:：]\\s*"),
            ""
        ).trim()
    }

    private val userTurnMarkerRegex = Regex("(?i)(?:^|[\\s\\n\\r])(?:User|ユーザー)\\s*[:：]")
    private val assistantTurnMarkerRegex = Regex("(?i)(?:^|[\\s\\n\\r])(?:Assistant|アシスタント)\\s*[:：]")
    private val roleTurnMarkerRegex =
        Regex("(?i)(?:^|[\\s\\n\\r])(?:User|Assistant|ユーザー|アシスタント)\\s*[:：]")

    fun stripSyntheticRoleLoopTail(text: String): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ""

        val markers = roleTurnMarkerRegex.findAll(normalized).take(16).toList()
        if (markers.size < 2) return normalized

        val first = markers.first()
        val cutIndex = if (first.range.first <= 2) {
            markers.getOrNull(1)?.range?.first ?: return normalized
        } else {
            first.range.first
        }
        if (cutIndex <= 0) return normalized

        val tail = normalized.substring(cutIndex)
        val hasUserTurn = userTurnMarkerRegex.containsMatchIn(tail)
        val hasAssistantTurn = assistantTurnMarkerRegex.containsMatchIn(tail)
        if (!hasUserTurn && !hasAssistantTurn) return normalized

        val clipped = normalized.substring(0, cutIndex).trimEnd().trimEnd(':', '：')
        if (clipped.isEmpty()) return normalized
        return clipped
    }

    fun sanitizeMessageContentForPrompt(
        msg: MessageEntity,
        isGgufEngine: Boolean = false,
        isCurrentTurn: Boolean = false
    ): String {
        val normalized = msg.content.trim()
        if (msg.role == "assistant") {
            if (normalized.isEmpty()) return ""
            val visibleOnly = Gemma4ThinkingParser.answerOnlyForModelContext(normalized)
            if (visibleOnly.isEmpty()) return ""
            return stripSyntheticRoleLoopTail(visibleOnly)
                .replace(Regex("^(?i)(?:Assistant|アシスタント)\\s*[:：]\\s*"), "")
                .trim()
        } else {
            // nezumi://videoframes / nezumi://txtfile マーカーは「画像」ではないので枚数に数えない
            val imageCount = msg.imageUri
                ?.split(",")
                ?.map { it.trim() }
                ?.count {
                    it.isNotEmpty() &&
                        !com.nezumi_ai.data.media.VideoAttachmentEncoding.isMarker(it) &&
                        !com.nezumi_ai.data.media.TextFileAttachmentEncoding.isMarker(it)
                }
                ?: 0
            val imageTokens: String = when {
                imageCount <= 0 -> ""
                isGgufEngine && isCurrentTurn ->
                    List(imageCount) { "<__media__>" }.joinToString(separator = "\n")
                else ->
                    msg.imageDescription?.takeIf { it.isNotBlank() }
                        ?: "(image x$imageCount)"
            }
            return when {
                imageTokens.isNotEmpty() && normalized.isNotEmpty() -> "$imageTokens\n$normalized"
                imageTokens.isNotEmpty() -> imageTokens
                else -> normalized
            }
        }
    }

    /** Lambda adapter for sanitizeMessageContentForPrompt */
    fun makeSanitizer(
        isGgufEngine: Boolean,
        currentTurnMessageId: Long?
    ): (MessageEntity) -> String = { msg ->
        sanitizeMessageContentForPrompt(
            msg,
            isGgufEngine = isGgufEngine,
            isCurrentTurn = (msg.id == currentTurnMessageId && msg.role == "user")
        )
    }

    // ---- 圧縮サマリー関連 (純粋ロジック) ----

    fun compactCompressionSummary(summary: String, maxChars: Int): String {
        val compact = summary
            .replace(Regex("[\\r\\n]+"), "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (compact.length <= maxChars) return compact
        return compact.take(maxChars).trimEnd() + "..."
    }

    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    fun parseCompressionJson(raw: String): Pair<String, List<String>>? {
        val jsonText = extractJsonObject(raw) ?: return null
        return runCatching {
            val obj = JSONObject(jsonText)
            val summary = obj.optString("summary").trim()
            if (summary.isBlank()) return null
            val keywords = mutableListOf<String>()
            val arr = obj.optJSONArray("keywords")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val kw = arr.optString(i).trim()
                    if (kw.isNotBlank()) keywords += kw
                }
            }
            val normalized = keywords.distinct().take(8)
            Pair(summary, if (normalized.isNotEmpty()) normalized else listOf("要点"))
        }.getOrNull()
    }

    fun recentMessageCountForWindow(contextWindow: Int): Int {
        return when {
            contextWindow <= 2048 -> 4
            contextWindow <= 4096 -> 6
            else -> 8
        }
    }

    fun buildCompressedSummaryFallback(messages: List<MessageEntity>): String {
        if (messages.isEmpty()) return "（圧縮対象なし）"
        return messages.takeLast(24).mapNotNull { msg ->
            val role = if (msg.role == "assistant") "A" else "U"
            val text = sanitizeMessageContentForPrompt(msg)
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .let { if (it.length > 80) it.take(80).trimEnd() + "..." else it }
            if (text.isBlank()) return@mapNotNull null
            "[$role] $text"
        }.joinToString(separator = "\n")
    }

    fun isAssistantErrorLikeMessage(content: String): Boolean {
        val t = content.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("エラー:", ignoreCase = true)) return true
        return t.contains("Status Code:", ignoreCase = true) ||
            t.contains("Failed to invoke the compiled model", ignoreCase = true) ||
            t.contains("モデルがロードされていません", ignoreCase = true) ||
            t.contains("応答開始がタイムアウト", ignoreCase = true) ||
            t.contains("生成を停止しました", ignoreCase = true) ||
            t.contains("応答を生成できませんでした", ignoreCase = true) ||
            t.contains("マルチモーダル推論を行うには「mmproj」", ignoreCase = true) ||
            t.contains("指定した mmproj がこのベース GGUF", ignoreCase = true) ||
            t.contains("本文が得られませんでした", ignoreCase = true) ||
            t.contains("ビジョンを初期化", ignoreCase = true)
    }

    fun shouldExcludeFromModelContext(msg: MessageEntity): Boolean {
        if (msg.role != "assistant") return false
        if (msg.isStreaming) return true
        return isAssistantErrorLikeMessage(msg.content)
    }

    fun appendMemoryBlockToSystemPrompt(systemPrompt: String, memoryBlock: String?): String {
        if (memoryBlock.isNullOrBlank()) return systemPrompt
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
                append("\n\n")
            }
            append(memoryBlock)
        }
    }

    fun buildMemorySearchQuery(messages: List<MessageEntity>): String {
        return messages.takeLast(4)
            .mapNotNull { msg ->
                val text = msg.content.trim().takeIf { it.isNotBlank() }
                when {
                    text.isNullOrBlank() -> null
                    msg.role == "assistant" -> "AI: $text"
                    msg.role == "user" -> "ユーザー: $text"
                    else -> null
                }
            }
            .joinToString(separator = "\n")
    }

    fun generateImageDescription(imageUris: List<String>): String {
        val count = imageUris.size
        val fileNames = imageUris.take(3)
            .mapNotNull { it.substringAfterLast("/").takeIf { name -> name.isNotEmpty() } }
            .joinToString(", ")
        return "Image: $fileNames (total $count image(s) shared)"
    }

    // ---- 部分終端補完 (停止時の表示崩れ防止) ----

    fun closePartialAssistantContent(content: String): String {
        if (content.isBlank()) return content
        var result = content
        val codeFenceCount = Regex("```").findAll(result).count()
        if (codeFenceCount % 2 == 1) {
            if (!result.endsWith("\n")) result += "\n"
            result += "```"
        }
        return result
    }

    fun closePartialThinking(thinking: String?): String? {
        if (thinking.isNullOrBlank()) return thinking
        var result = thinking
        val openCount = Regex("(?i)<think>").findAll(result).count()
        val closeCount = Regex("(?i)</think>").findAll(result).count()
        if (openCount > closeCount) {
            result += "</think>"
        }
        val openCount2 = Regex("<\\|think\\|>").findAll(result).count()
        val closeCount2 = Regex("<\\|/think\\|>").findAll(result).count()
        if (openCount2 > closeCount2) {
            result += "<|/think|>"
        }
        return result
    }

    fun detectGgufFormat(
        engineModelName: String,
        appContext: Context?
    ): PromptBuilder.GgufPromptFormat = PromptBuilder.detectGgufFormat(engineModelName, appContext)

    fun buildForGguf(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        format: PromptBuilder.GgufPromptFormat,
        enableThinking: Boolean,
        modelPath: String,
        sanitizeMessageContent: (MessageEntity) -> String,
        appContext: Context?
    ): String = PromptBuilder.buildForGguf(
        messages = messages,
        systemPrompt = systemPrompt,
        compressedSummary = compressedSummary,
        format = format,
        enableThinking = enableThinking,
        modelPath = modelPath,
        sanitizeMessageContent = sanitizeMessageContent,
        appContext = appContext
    )

    fun buildForLiteRt(
        messages: List<MessageEntity>,
        systemPrompt: String,
        injectGemmaThinkTrigger: Boolean,
        compressedSummary: String?,
        sanitizeMessageContent: (MessageEntity) -> String,
        appContext: Context?,
        modelPath: String
    ): String = PromptBuilder.buildForLiteRt(
        messages = messages,
        systemPrompt = systemPrompt,
        injectGemmaThinkTrigger = injectGemmaThinkTrigger,
        compressedSummary = compressedSummary,
        sanitizeMessageContent = sanitizeMessageContent,
        appContext = appContext,
        modelPath = modelPath
    )

    fun isGemma4Model(engineModelName: String): Boolean = PromptBuilder.isGemma4Model(engineModelName)

    fun usesAssistantThinkingPrefill(engineModelName: String): Boolean =
        PromptBuilder.usesAssistantThinkingPrefill(engineModelName)

    fun resolveModelNameForGemmaCheck(engineModelName: String): String =
        PromptBuilder.resolveModelNameForGemmaCheck(engineModelName)

    companion object {
        private const val TAG = "PromptBuildingUseCase"
        private const val TOKEN_TO_CHAR_RATIO = 4
    }
}
