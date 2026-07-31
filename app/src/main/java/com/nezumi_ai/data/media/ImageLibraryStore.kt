package com.nezumi_ai.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

data class StoredLibraryImage(
    val bitmap: Bitmap,
    val prompt: String,
    val timestamp: Long,
    val negativePrompt: String? = null,
    val steps: Int? = null,
    val seed: Long? = null,
    val modelName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val cfg: Float? = null,
    val scheduler: String? = null
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

    /**
     * 画像をライブラリに保存する（フルメタデータ対応）。
     * ImageGenFragment.saveImageToLibrary と同じ JSON 形式で metadata.txt に書き込む。
     * 旧形式 (timestamp|prompt) との下位互換は load() 側で吸収する。
     *
     * Bug fix: ツール経由画像生成でプロンプトしか保存されない問題を修正。
     *   旧実装は save(context, bitmap, prompt) のみでネガティブプロンプト等の
     *   メタデータが保存されず、手動生成経路 (ImageGenFragment.saveImageToLibrary)
     *   と保存内容に差があった。本メソッドをフルメタデータ対応に統一する。
     */
    fun save(
        context: Context,
        bitmap: Bitmap,
        prompt: String,
        negativePrompt: String? = null,
        steps: Int? = null,
        seed: Long? = null,
        modelName: String? = null,
        width: Int? = null,
        height: Int? = null,
        cfg: Float? = null,
        scheduler: String? = null
    ): Long {
        val libraryDir = getLibraryDir(context)
        val timestamp = System.currentTimeMillis()
        val imageFile = File(libraryDir, "img_$timestamp.jpg")

        imageFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }

        // ImageGenFragment.saveImageToLibrary と同じ JSON 形式で保存
        val json = org.json.JSONObject().apply {
            put("timestamp", timestamp)
            put("prompt", prompt)
            put("negativePrompt", negativePrompt ?: "")
            put("steps", steps ?: 0)
            put("seed", seed ?: -1L)
            if (!modelName.isNullOrEmpty()) put("modelName", modelName)
            if (width != null && width > 0) put("width", width)
            if (height != null && height > 0) put("height", height)
            if (cfg != null) put("cfg", cfg.toDouble())
            if (!scheduler.isNullOrEmpty()) put("scheduler", scheduler)
        }
        File(libraryDir, METADATA_FILE_NAME).appendText(json.toString() + "\n")
        Log.d(TAG, "Saved image to app library: ${imageFile.absolutePath} (with full metadata)")
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
                // JSON 形式 (新形式)
                if (line.startsWith("{")) {
                    try {
                        val obj = org.json.JSONObject(line)
                        val timestamp = obj.getLong("timestamp")
                        val imageFile = File(libraryDir, "img_$timestamp.jpg")
                        val bitmap = if (imageFile.exists()) {
                            BitmapFactory.decodeFile(imageFile.absolutePath)
                        } else null ?: return@mapNotNull null
                        StoredLibraryImage(
                            bitmap = bitmap,
                            prompt = obj.getString("prompt"),
                            timestamp = timestamp,
                            negativePrompt = obj.optString("negativePrompt").takeIf { it.isNotEmpty() },
                            steps = obj.optInt("steps").takeIf { it > 0 },
                            seed = obj.optLong("seed").takeIf { it != -1L },
                            modelName = obj.optString("modelName").takeIf { it.isNotEmpty() },
                            width = obj.optInt("width").takeIf { it > 0 },
                            height = obj.optInt("height").takeIf { it > 0 },
                            cfg = if (obj.has("cfg")) obj.optDouble("cfg").toFloat() else null,
                            scheduler = obj.optString("scheduler").takeIf { it.isNotEmpty() }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse JSON metadata line", e)
                        null
                    }
                } else {
                    // 旧形式 (timestamp|prompt)
                    val parts = line.split("|", limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val imageFile = File(libraryDir, "img_$timestamp.jpg")
                    val bitmap = if (imageFile.exists()) {
                        BitmapFactory.decodeFile(imageFile.absolutePath)
                    } else null ?: return@mapNotNull null
                    StoredLibraryImage(bitmap, parts[1], timestamp)
                }
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
                .filterNot { line ->
                    if (line.startsWith("{")) {
                        try {
                            org.json.JSONObject(line).optLong("timestamp") == timestamp
                        } catch (e: Exception) {
                            false
                        }
                    } else {
                        line.startsWith("$timestamp|")
                    }
                }
                .toList()
            metadataFile.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
        }
    }

    private fun sanitizePrompt(prompt: String): String =
        prompt.replace("\r", " ").replace("\n", " ").trim()
}
