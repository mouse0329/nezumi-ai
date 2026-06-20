package com.nezumi_ai.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

data class StoredLibraryImage(
    val bitmap: Bitmap,
    val prompt: String,
    val timestamp: Long
)

object ImageLibraryStore {
    private const val TAG = "ImageLibraryStore"
    private const val LIBRARY_DIR_NAME = "library"
    private const val METADATA_FILE_NAME = "metadata.txt"

    fun getLibraryDir(context: Context): File {
        val dir = File(context.filesDir, LIBRARY_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun save(context: Context, bitmap: Bitmap, prompt: String): Long {
        val libraryDir = getLibraryDir(context)
        val timestamp = System.currentTimeMillis()
        val imageFile = File(libraryDir, "img_$timestamp.jpg")

        imageFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }

        File(libraryDir, METADATA_FILE_NAME).appendText("$timestamp|${sanitizePrompt(prompt)}\n")
        Log.d(TAG, "Saved image to app library: ${imageFile.absolutePath}")
        return timestamp
    }

    fun load(context: Context): List<StoredLibraryImage> {
        val libraryDir = getLibraryDir(context)
        val metadataFile = File(libraryDir, METADATA_FILE_NAME)
        if (!metadataFile.exists()) return emptyList()

        return metadataFile.readText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
                val imageFile = File(libraryDir, "img_$timestamp.jpg")
                val bitmap = if (imageFile.exists()) {
                    BitmapFactory.decodeFile(imageFile.absolutePath)
                } else {
                    null
                } ?: return@mapNotNull null
                StoredLibraryImage(bitmap, parts[1], timestamp)
            }
            .toList()
            .asReversed()
    }

    fun delete(context: Context, timestamp: Long) {
        val libraryDir = getLibraryDir(context)
        File(libraryDir, "img_$timestamp.jpg").takeIf { it.exists() }?.delete()

        val metadataFile = File(libraryDir, METADATA_FILE_NAME)
        if (metadataFile.exists()) {
            val remaining = metadataFile.readText()
                .lineSequence()
                .filter { it.isNotBlank() }
                .filterNot { it.startsWith("$timestamp|") }
                .toList()
            metadataFile.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
        }
    }

    private fun sanitizePrompt(prompt: String): String =
        prompt.replace("\r", " ").replace("\n", " ").trim()
}
