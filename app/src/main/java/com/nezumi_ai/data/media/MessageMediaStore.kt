package com.nezumi_ai.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

object MessageMediaStore {
    private const val AUTHORITY_SUFFIX = ".fileprovider"  // AndroidManifest.xml の FileProvider authority と統一
    private const val MEDIA_DIR_NAME = "message_media"
    private const val TAG = "MessageMediaStore"
    
    /**
     * メディアディレクトリを取得（ファイルベースの永続化用）
     */
    fun getMediaDir(context: Context): File {
        val dir = File(context.filesDir, MEDIA_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Convert a URI string back to a Uri object
     */
    fun toUri(uriString: String?): Uri? {
        return if (uriString != null && uriString.isNotEmpty()) {
            try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * チェック: URI がまだ有効であるかどうか
     * - file:// スキーム: ファイルの存在確認
     * - content:// スキーム: ContentResolver でアクセス試行
     * - その他: false
     */
    fun isUriValid(context: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        return when (uri.scheme) {
            "file" -> {
                try {
                    val file = File(uri.path ?: return false)
                    file.exists() && file.canRead()
                } catch (e: Exception) {
                    false
                }
            }
            "content" -> {
                try {
                    context.contentResolver.openInputStream(uri)?.use {
                        true
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }
            else -> false
        }
    }

    /**
     * Persist a URI if needed (for content that needs to be stored)
     * 
     * アプリ再起動後も有効な URI として保存するため、以下の処理を実施：
     * 1. 既に file:// スキームで、アプリ filesDir 配下にある場合 → そのまま返す
     * 2. content:// または data: の場合 → 画像ファイルを filesDir/message_media にコピーし、
     *    FileProvider 経由の content:// URI に変換して返す
     * 3. 無効な URI → null 返す
     * 
     * Returns: 永続化された URI 文字列、または null
     */
    fun persistUriIfNeeded(context: Context, uri: Uri?): String? {
        if (uri == null) return null

        return when (uri.scheme) {
            "file" -> {
                // ローカルファイルが既にアプリ filesDir 配下か確認
                try {
                    val file = File(uri.path ?: return null)
                    val mediaDir = getMediaDir(context)
                    
                    if (file.canonicalPath.startsWith(mediaDir.canonicalPath)) {
                        // 既にアプリメディアディレクトリにある
                        Log.d(TAG, "File already in app storage: ${file.path}")
                        uri.toString()
                    } else {
                        // 外部ストレージのファイル → メディアディレクトリにコピー
                        copyMediaToAppStorage(context, uri)?.toString()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking file URI: ${e.message}")
                    null
                }
            }
            "content" -> {
                // content:// URI → ファイルにコピーして返す
                try {
                    copyMediaToAppStorage(context, uri)?.toString()
                } catch (e: Exception) {
                    Log.w(TAG, "Error persisting content URI: ${e.message}")
                    null
                }
            }
            else -> {
                // data: など他のスキームは保存できない
                Log.w(TAG, "Unsupported URI scheme: ${uri.scheme}")
                null
            }
        }
    }

    /**
     * Persist a URI string if needed
     */
    fun persistUriIfNeeded(context: Context, uriString: String?): String? {
        return if (!uriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(uriString)
                persistUriIfNeeded(context, uri)
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing URI string: $uriString, ${e.message}")
                null
            }
        } else {
            null
        }
    }

    /**
     * メディアをアプリストレージにコピー
     * 
     * 処理：
     * 1. URI からバイナリデータを読み込む
     * 2. UUIDを使った一意なファイル名で filesDir/message_media に保存
     * 3. FileProvider で content:// URI に変換して返す
     * 
     * Returns: 永続化されたコンテンツURI（FileProvider経由）、またはnull
     */
    private fun copyMediaToAppStorage(context: Context, uri: Uri): Uri? {
        return try {
            val mediaDir = getMediaDir(context)
            val fileName = "img_${UUID.randomUUID()}.jpg"  // 画像形式に固定（必要に応じて拡張）
            val destFile = File(mediaDir, fileName)

            // InputStream から destFile にコピー
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Media copied to storage: ${destFile.path}")

            // FileProvider で content:// URI に変換
            val authority = context.packageName + AUTHORITY_SUFFIX
            val contentUri = FileProvider.getUriForFile(context, authority, destFile)
            
            Log.d(TAG, "Generated content URI: $contentUri")
            contentUri
        } catch (e: Exception) {
            Log.e(TAG, "Error copying media to app storage: ${e.message}", e)
            null
        }
    }

    /**
     * Delete a stored file if it's owned by this app
     */
    fun deleteStoredFileIfOwned(context: Context, uri: Uri?) {
        if (uri == null) return
        
        try {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: return)
                    if (isFileOwnedByApp(context, file)) {
                        if (file.delete()) {
                            Log.d(TAG, "Deleted file: ${file.path}")
                        }
                    }
                }
                "content" -> {
                    // FileProvider の content:// URI の場合、元のファイルを削除
                    try {
                        val path = getPathFromUri(context, uri)
                        if (path != null) {
                            val file = File(path)
                            if (isFileOwnedByApp(context, file) && file.delete()) {
                                Log.d(TAG, "Deleted file from content URI: ${file.path}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete file from content URI: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting stored file: ${e.message}")
        }
    }

    /**
     * Delete a stored file if it's owned by this app (from URI string)
     */
    fun deleteStoredFileIfOwned(context: Context, uriString: String?) {
        if (!uriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(uriString)
                deleteStoredFileIfOwned(context, uri)
            } catch (e: Exception) {
                // Silently fail if we can't delete
            }
        }
    }


    /**
     * Check if a file is owned by this app
     */
    private fun isFileOwnedByApp(context: Context, file: File): Boolean {
        val cacheDir = context.cacheDir
        val filesDir = context.filesDir
        val mediaDir = getMediaDir(context)
        
        return try {
            val filePath = file.canonicalPath
            val cachePath = cacheDir.canonicalPath
            val filesPath = filesDir.canonicalPath
            val mediaPath = mediaDir.canonicalPath
            
            filePath.startsWith(cachePath) || filePath.startsWith(filesPath) || filePath.startsWith(mediaPath)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking file ownership: ${e.message}")
            false
        }
    }

    /**
     * Get file path from content URI
     */
    private fun getPathFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex("_data")
                    if (column >= 0) {
                        cursor.getString(column)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting path from content URI: ${e.message}")
            null
        }
    }
}
