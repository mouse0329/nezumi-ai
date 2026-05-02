package com.nezumi_ai.data.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object MessageMediaStore {
    private const val AUTHORITY_SUFFIX = ".message_media"
    
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
     * Persist a URI if needed (for content that needs to be stored)
     * Returns the persistent URI string, or the original string if already persistent
     */
    fun persistUriIfNeeded(context: Context, uri: Uri?): String? {
        return if (uri != null) {
            uri.toString()
        } else {
            null
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
                uriString
            }
        } else {
            null
        }
    }

    /**
     * Delete a stored file if it's owned by this app
     */
    fun deleteStoredFileIfOwned(context: Context, uri: Uri?) {
        if (uri != null) {
            try {
                val file = when (uri.scheme) {
                    "file" -> File(uri.path ?: return)
                    "content" -> {
                        // Try to get the file from content URI
                        val path = getPathFromUri(context, uri)
                        if (path != null) File(path) else null
                    }
                    else -> null
                }

                if (file != null && isFileOwnedByApp(context, file)) {
                    file.delete()
                }
            } catch (e: Exception) {
                // Silently fail if we can't delete
            }
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
        
        return try {
            val filePath = file.canonicalPath
            val cachePath = cacheDir.canonicalPath
            val filesPath = filesDir.canonicalPath
            
            filePath.startsWith(cachePath) || filePath.startsWith(filesPath)
        } catch (e: Exception) {
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
                    if (column >= 0) cursor.getString(column) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
