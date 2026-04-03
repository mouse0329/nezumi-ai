package com.nezumi_ai.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * チャットに添付する画像・音声を [Context.filesDir]/message_media に複製し、再起動後も読めるパスを返す。
 * （ピッカーが返す content:// はプロセスをまたぐと読めなくなることがある）
 */
object MessageMediaStore {
    private const val TAG = "MessageMediaStore"
    private const val SUBDIR = "message_media"

    fun mediaDirectory(context: Context): File = File(context.filesDir, SUBDIR)

    /**
     * DB や状態に保存する文字列から [Uri] へ（表示・MediaPlayer・decode 用）
     */
    fun toUri(stored: String): Uri {
        val s = stored.trim()
        return when {
            s.startsWith("content://") -> Uri.parse(s)
            s.startsWith("file://") -> Uri.parse(s)
            else -> {
                val f = File(s)
                if (f.isAbsolute && f.exists()) Uri.fromFile(f) else Uri.parse(s)
            }
        }
    }

    /**
     * ピッカー等の URI 文字列をアプリ内ファイルに複製して絶対パスを返す。既に自アプリ管理下ならコピーしない。
     */
    fun persistUriIfNeeded(context: Context, uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val trimmed = uriString.trim()
        if (isAppManagedMediaFile(context, trimmed)) {
            return runCatching { File(trimmed).canonicalPath }.getOrNull() ?: trimmed
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        return copyToInternalFile(context, uri)
    }

    private fun isAppManagedMediaFile(context: Context, path: String): Boolean {
        return runCatching {
            val f = File(path)
            f.exists() && f.canonicalPath.startsWith(mediaDirectory(context).canonicalPath)
        }.getOrDefault(false)
    }

    private fun copyToInternalFile(context: Context, uri: Uri): String? {
        return try {
            val dir = mediaDirectory(context).apply { mkdirs() }
            val ext = extensionForUri(context, uri)
            val out = File(dir, "${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Log.e(TAG, "openInputStream failed for $uri")
                return null
            }
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist media: $uri", e)
            null
        }
    }

    private fun extensionForUri(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri)
        return when (type) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            else -> {
                val name = queryDisplayName(context, uri)
                val ext = name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }
                ext ?: "bin"
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }

    /** [message_media] 直下に置いたファイルのみ削除（ユーザーが選んだ外部パスは触らない） */
    fun deleteStoredFileIfOwned(context: Context, storedPath: String?) {
        val p = storedPath ?: return
        runCatching {
            val f = File(p)
            if (f.exists() && f.canonicalPath.startsWith(mediaDirectory(context).canonicalPath)) {
                f.delete()
            }
        }.onFailure { Log.w(TAG, "deleteStoredFileIfOwned failed", it) }
    }
}
