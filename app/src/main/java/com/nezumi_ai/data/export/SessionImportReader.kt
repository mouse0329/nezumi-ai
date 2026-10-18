package com.nezumi_ai.data.export

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MessageRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * .nzai (nezumi-session-export) を読み込み、新規セッションとして復元する。
 * Thinking / ツールコール結果 / 本文 / Base64 添付を含む。
 */
object SessionImportReader {
    private const val TAG = "SessionImportReader"
    private const val EXPECTED_FORMAT = "nezumi-session-export"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    data class Result(
        val sessionCount: Int,
        val messageCount: Int,
        val sessionIds: List<Long>
    )

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        sessionRepository: ChatSessionRepository,
        messageRepository: MessageRepository
    ): Result {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("ファイルを読めませんでした")

        val root = JSONObject(text)
        val format = root.optString("format", "")
        if (format != EXPECTED_FORMAT) {
            throw IllegalArgumentException("対応していない形式です (format=$format)")
        }
        val sessionsArr = root.optJSONArray("sessions")
            ?: throw IllegalArgumentException("sessions がありません")
        if (sessionsArr.length() == 0) {
            throw IllegalArgumentException("セッションが空です")
        }

        val importedIds = mutableListOf<Long>()
        var messageCount = 0
        for (i in 0 until sessionsArr.length()) {
            val sObj = sessionsArr.optJSONObject(i) ?: continue
            val sessionId = importOneSession(context, sObj, sessionRepository, messageRepository)
            importedIds.add(sessionId)
            messageCount += sObj.optJSONArray("messages")?.length() ?: 0
        }
        Log.d(TAG, "Imported ${importedIds.size} session(s), $messageCount messages")
        return Result(
            sessionCount = importedIds.size,
            messageCount = messageCount,
            sessionIds = importedIds
        )
    }

    private suspend fun importOneSession(
        context: Context,
        sObj: JSONObject,
        sessionRepository: ChatSessionRepository,
        messageRepository: MessageRepository
    ): Long {
        val name = sObj.optString("name", "").ifBlank { "インポートしたチャット" }
        val selectedModel = sObj.optString("selectedModel", "E2B").ifBlank { "E2B" }
        val isPinned = sObj.optBoolean("isPinned", false)
        val createDate = sObj.optLong("createDate", System.currentTimeMillis())
        val lastUpdated = sObj.optLong("lastUpdated", createDate)

        // createSession は now で作るので、日付・モデル・固定は insert 後に update する
        val newSessionId = sessionRepository.createSession(name, isIncognito = false)
        val created = sessionRepository.getSessionById(newSessionId)
        if (created != null) {
            sessionRepository.updateSessionEntity(
                created.copy(
                    selectedModel = selectedModel,
                    isPinned = isPinned,
                    createDate = createDate,
                    lastUpdated = lastUpdated
                )
            )
        }

        val messagesArr = sObj.optJSONArray("messages") ?: JSONArray()
        // 旧 message id -> 新 message id (parentUserMessageId の付け替え用)
        val idMap = mutableMapOf<Long, Long>()
        // (newMsgId, oldParentId, variantIndex)
        val pendingParent = mutableListOf<Triple<Long, Long, Int>>()

        for (i in 0 until messagesArr.length()) {
            val mObj = messagesArr.optJSONObject(i) ?: continue
            val oldId = mObj.optLong("id", 0L)
            val role = mObj.optString("role", "user")
            val content = mObj.optString("content", "")
            val thinking = mObj.optString("thinking").takeIf { it.isNotBlank() }
            val timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
            val imageDescription = mObj.optString("imageDescription").takeIf { it.isNotBlank() }
            val generationTps = if (mObj.has("generationTps")) mObj.optDouble("generationTps").toFloat() else null
            val generationTimeMs = if (mObj.has("generationTimeMs")) mObj.optLong("generationTimeMs") else null
            val ttftMs = if (mObj.has("ttftMs")) mObj.optLong("ttftMs") else null
            val variantIndex = mObj.optInt("variantIndex", 0)
            val oldParent = if (mObj.has("parentUserMessageId")) mObj.optLong("parentUserMessageId") else null

            val toolResultsJson = when {
                mObj.has("toolCalls") -> mObj.optJSONArray("toolCalls")?.toString()
                mObj.has("toolCallsRaw") -> mObj.optString("toolCallsRaw").takeIf { it.isNotBlank() }
                else -> null
            }

            val (imageUri, audioUri) = restoreAttachments(context, mObj.optJSONArray("attachments"))

            val entity = MessageEntity(
                sessionId = newSessionId,
                role = role,
                content = content,
                thinkingContent = thinking,
                imageUri = imageUri,
                imageDescription = imageDescription,
                audioUri = audioUri,
                timestamp = timestamp,
                isStreaming = false,
                toolResultsJson = toolResultsJson,
                generationTps = generationTps,
                generationTimeMs = generationTimeMs,
                ttftMs = ttftMs,
                parentUserMessageId = null,
                variantIndex = variantIndex
            )
            val newMsgId = messageRepository.insertMessage(entity)
            if (oldId != 0L) idMap[oldId] = newMsgId
            if (oldParent != null && oldParent != 0L) {
                pendingParent.add(Triple(newMsgId, oldParent, variantIndex))
            }
        }

        for ((newMsgId, oldParentId, variantIndex) in pendingParent) {
            val newParent = idMap[oldParentId] ?: continue
            messageRepository.updateParentUserMessageId(newMsgId, newParent, variantIndex)
        }

        return newSessionId
    }

    /**
     * attachments 配列から imageUri (カンマ区切り) と audioUri を復元する。
     * kind=file/document は TextFileAttachmentEncoding マーカーに戻す。
     */
    private fun restoreAttachments(
        context: Context,
        attachments: JSONArray?
    ): Pair<String?, String?> {
        if (attachments == null || attachments.length() == 0) return null to null
        val imageParts = mutableListOf<String>()
        var audioUri: String? = null

        for (i in 0 until attachments.length()) {
            val att = attachments.optJSONObject(i) ?: continue
            val kind = att.optString("kind", "image")
            val name = att.optString("name", kind)
            val b64 = att.optString("base64")
            if (b64.isBlank()) continue
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.w(TAG, "base64 decode failed for $name: ${e.message}")
                continue
            }
            val mime = att.optString("mime").takeIf { it.isNotBlank() }
            val persisted = persistBytes(context, bytes, name, mime, kind) ?: continue
            when (kind) {
                "audio" -> {
                    if (audioUri == null) audioUri = persisted
                    else imageParts.add(persisted)
                }
                "file", "document" -> {
                    val marker = TextFileAttachmentEncoding.encode(
                        TextFileAttachmentEncoding.TextFileEntry(
                            name = name,
                            uri = persisted,
                            isConvertedDocument = kind == "document"
                        )
                    )
                    imageParts.add(marker)
                }
                else -> imageParts.add(persisted) // image / video frames
            }
        }

        val imageUri = imageParts.takeIf { it.isNotEmpty() }?.joinToString(",")
        return imageUri to audioUri
    }

    private fun persistBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mime: String?,
        kind: String
    ): String? {
        return try {
            val mediaDir = MessageMediaStore.getMediaDir(context)
            val ext = guessExtension(displayName, mime, kind)
            val dest = File(mediaDir, "import_${UUID.randomUUID()}$ext")
            dest.writeBytes(bytes)
            val authority = context.packageName + AUTHORITY_SUFFIX
            FileProvider.getUriForFile(context, authority, dest).toString()
        } catch (e: Exception) {
            Log.w(TAG, "persistBytes failed: ${e.message}")
            null
        }
    }

    private fun guessExtension(name: String, mime: String?, kind: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName.isNotBlank() && fromName.length <= 8 && fromName.all { it.isLetterOrDigit() }) {
            return ".$fromName"
        }
        return when {
            mime?.startsWith("image/") == true -> when (mime) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> ".jpg"
            }
            mime?.startsWith("audio/") == true -> ".m4a"
            mime?.startsWith("video/") == true -> ".mp4"
            mime == "application/pdf" -> ".pdf"
            kind == "audio" -> ".m4a"
            kind == "video" -> ".mp4"
            kind == "document" || kind == "file" -> ".bin"
            else -> ".bin"
        }
    }
}
