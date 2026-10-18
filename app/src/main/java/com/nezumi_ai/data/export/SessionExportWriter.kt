package com.nezumi_ai.data.export

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.data.media.VideoAttachmentEncoding
import com.nezumi_ai.data.repository.MessageRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * チャットセッションを JSON ファイルへ書き出す。
 * Thinking / ツール結果カード / 本文 / 画像・音声・テキスト・PDF 等の添付 (Base64) を含める。
 * 複数セッションは 1 ファイルにまとめる。
 */
object SessionExportWriter {
    private const val TAG = "SessionExportWriter"
    private const val FORMAT = "nezumi-session-export"
    private const val VERSION = 1
    /** 1 添付あたりの上限 (約 25MB)。超えたら省略して note を付ける。 */
    private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024

    data class Result(
        val file: File,
        val sessionCount: Int,
        val messageCount: Int
    )

    suspend fun exportToCacheFile(
        context: Context,
        sessions: List<ChatSessionEntity>,
        messageRepository: MessageRepository
    ): Result {
        require(sessions.isNotEmpty()) { "sessions is empty" }
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put(
            "exportedAtIso",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        )

        val sessionsArr = JSONArray()
        var messageCount = 0
        for (session in sessions) {
            val messages = messageRepository.getMessagesForSessionOnce(session.id)
            messageCount += messages.size
            sessionsArr.put(sessionToJson(context, session, messages))
        }
        root.put("sessions", sessionsArr)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = if (sessions.size == 1) {
            val safe = sessions[0].name.ifBlank { "untitled" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(40)
            "nezumi_${safe}_$stamp.nzai"
        } else {
            "nezumi_sessions_${sessions.size}_$stamp.nzai"
        }
        val file = File(dir, name)
        file.writeText(root.toString(2), Charsets.UTF_8)
        Log.d(TAG, "Exported ${sessions.size} session(s), $messageCount messages -> ${file.absolutePath}")
        return Result(file = file, sessionCount = sessions.size, messageCount = messageCount)
    }

    private fun sessionToJson(
        context: Context,
        session: ChatSessionEntity,
        messages: List<MessageEntity>
    ): JSONObject {
        val obj = JSONObject()
        obj.put("id", session.id)
        obj.put("name", session.name)
        obj.put("createDate", session.createDate)
        obj.put("lastUpdated", session.lastUpdated)
        obj.put("selectedModel", session.selectedModel)
        obj.put("isPinned", session.isPinned)
        obj.put("isIncognito", session.isIncognito)

        val msgsArr = JSONArray()
        for (m in messages) {
            msgsArr.put(messageToJson(context, m))
        }
        obj.put("messages", msgsArr)
        return obj
    }

    private fun messageToJson(context: Context, m: MessageEntity): JSONObject {
        val obj = JSONObject()
        obj.put("id", m.id)
        obj.put("role", m.role)
        obj.put("content", m.content)
        obj.put("timestamp", m.timestamp)
        if (!m.thinkingContent.isNullOrBlank()) {
            obj.put("thinking", m.thinkingContent)
        }
        // ツールコール結果カード (JSON 配列文字列のまま / パース可能なら配列)
        if (!m.toolResultsJson.isNullOrBlank()) {
            try {
                obj.put("toolCalls", JSONArray(m.toolResultsJson))
            } catch (_: Exception) {
                obj.put("toolCallsRaw", m.toolResultsJson)
            }
        }
        if (!m.imageDescription.isNullOrBlank()) {
            obj.put("imageDescription", m.imageDescription)
        }
        m.generationTps?.let { obj.put("generationTps", it.toDouble()) }
        m.generationTimeMs?.let { obj.put("generationTimeMs", it) }
        m.ttftMs?.let { obj.put("ttftMs", it) }
        m.parentUserMessageId?.let { obj.put("parentUserMessageId", it) }
        if (m.variantIndex != 0) obj.put("variantIndex", m.variantIndex)

        val attachments = JSONArray()
        collectAttachments(context, m, attachments)
        if (attachments.length() > 0) {
            obj.put("attachments", attachments)
        }
        return obj
    }

    private fun collectAttachments(
        context: Context,
        m: MessageEntity,
        out: JSONArray
    ) {
        // テキスト / PDF 等 (nezumi://txtfile マーカー)
        for (entry in TextFileAttachmentEncoding.extract(m.imageUri)) {
            val att = encodeUriAttachment(
                context = context,
                uriString = entry.uri,
                kind = if (entry.isConvertedDocument || TextFileAttachmentEncoding.isDocumentFile(entry.name)) {
                    "document"
                } else {
                    "file"
                },
                displayName = entry.name
            )
            out.put(att)
        }

        // 動画フレーム列 + 元動画 / 抽出音声
        val (videoMeta, frameUris) = VideoAttachmentEncoding.split(m.imageUri)
        if (videoMeta != null) {
            videoMeta.originalVideoUri?.let { vUri ->
                out.put(encodeUriAttachment(context, vUri, "video", null))
            }
            videoMeta.audioUri?.let { aUri ->
                out.put(encodeUriAttachment(context, aUri, "audio", null))
            }
            frameUris.forEachIndexed { index, frameUri ->
                out.put(encodeUriAttachment(context, frameUri, "image", "frame_$index"))
            }
        } else {
            // 通常の画像 URI (カンマ区切り)。txtfile / video マーカーは除外済み。
            m.imageUri?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.filter { !TextFileAttachmentEncoding.isMarker(it) }
                ?.filter { !VideoAttachmentEncoding.isMarker(it) }
                ?.forEach { uriStr ->
                    out.put(encodeUriAttachment(context, uriStr, "image", null))
                }
        }

        if (!m.audioUri.isNullOrBlank()) {
            out.put(encodeUriAttachment(context, m.audioUri, "audio", null))
        }
    }

    private fun encodeUriAttachment(
        context: Context,
        uriString: String,
        kind: String,
        displayName: String?
    ): JSONObject {
        val att = JSONObject()
        att.put("kind", kind)
        att.put("uri", uriString)
        val name = displayName
            ?: uriString.substringAfterLast('/').substringBefore('?').ifBlank { kind }
        att.put("name", name)

        val uri = MessageMediaStore.toUri(uriString)
        if (uri == null) {
            att.put("error", "invalid_uri")
            return att
        }
        val mime = context.contentResolver.getType(uri)
        if (!mime.isNullOrBlank()) att.put("mime", mime)

        try {
            val bytes = readUriBytes(context, uri, MAX_ATTACHMENT_BYTES + 1)
            if (bytes == null) {
                att.put("error", "read_failed")
            } else if (bytes.size > MAX_ATTACHMENT_BYTES) {
                att.put("error", "too_large")
                att.put("sizeBytes", bytes.size)
                att.put("note", "Attachment exceeded ${MAX_ATTACHMENT_BYTES} bytes; base64 omitted")
            } else {
                att.put("sizeBytes", bytes.size)
                att.put("encoding", "base64")
                att.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            Log.w(TAG, "encodeUriAttachment failed for $uriString: ${e.message}")
            att.put("error", e.message ?: "exception")
        }
        return att
    }

    private fun readUriBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                total += n
                if (total > maxBytes) {
                    out.write(buffer, 0, n)
                    // 上限超過を呼び出し側で判定できるよう、少し多めに読んで返す
                    return out.toByteArray()
                }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }
}
