package com.nezumi_ai.presentation.viewmodel.platform.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.presentation.viewmodel.platform.PlatformKeyValueStore
import com.nezumi_ai.presentation.viewmodel.platform.PlatformMediaLoader
import com.nezumi_ai.presentation.viewmodel.platform.PlatformTtsPlayer
import com.nezumi_ai.presentation.viewmodel.platform.PlatformWakeLock
import com.nezumi_ai.sd.SdModelLayout
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.voicevox.VoicevoxStreamingTts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** [PlatformMediaLoader] の Android 実装。BitmapFactory / ContentResolver / MediaStore を内包する。 */
class AndroidPlatformMediaLoader(private val appContext: Context) : PlatformMediaLoader {

    override suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            withContext(Dispatchers.IO) {
                // file:// URI と content:// URI の両方に対応
                if (uri.scheme == "file") {
                    val path = uri.path ?: return@withContext null
                    val file = File(path)
                    if (!file.exists()) {
                        Log.w(TAG, "Image file not found: $path")
                        return@withContext null
                    }
                    file.inputStream().use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } else {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    } ?: run {
                        Log.w(TAG, "Failed to open stream for URI: $uri")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: $uri", e)
            null
        }
    }

    override suspend fun loadAudioBytesFromUri(uri: Uri): ByteArray? {
        return try {
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audio from URI: $uri", e)
            null
        }
    }

    override fun scaleBitmapTo1024(bitmap: Bitmap): Bitmap {
        val maxSize = 1024
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) {
            return bitmap
        }
        val scale = minOf(
            maxSize.toFloat() / bitmap.width,
            maxSize.toFloat() / bitmap.height
        )
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    override suspend fun saveBitmapToGallery(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val name = "nezumi_chat_sd_${System.currentTimeMillis()}.png"
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(
                            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/NezumiAI"
                        )
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = appContext.contentResolver
                    val uri = resolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                    )
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            values.clear()
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        Log.d(TAG, "Saved to gallery: $uri")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val uriStr = android.provider.MediaStore.Images.Media.insertImage(
                        appContext.contentResolver,
                        bitmap,
                        name,
                        "nezumi-ai SD"
                    )
                    Log.d(TAG, "Saved to gallery (legacy): $uriStr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to gallery", e)
            }
        }
    }

    override fun persistUriIfNeeded(uriString: String?): String? =
        MessageMediaStore.persistUriIfNeeded(appContext, uriString)

    override fun persistUriIfNeeded(uri: Uri?): String? =
        MessageMediaStore.persistUriIfNeeded(appContext, uri)

    override fun deleteStoredFileIfOwned(uriString: String?) =
        MessageMediaStore.deleteStoredFileIfOwned(appContext, uriString)

    override fun deleteMessageAttachments(imageUri: String?, audioUri: String?) =
        MessageMediaStore.deleteMessageAttachments(appContext, imageUri, audioUri)

    override fun toUri(uriString: String?): Uri? = MessageMediaStore.toUri(uriString)

    override fun savePngBitmap(bitmap: Bitmap, baseName: String): String? =
        MessageMediaStore.savePngBitmap(appContext, bitmap, baseName)

    private companion object {
        const val TAG = "AndroidPlatformMediaLoader"
    }
}

/** [PlatformWakeLock] の Android 実装。PowerManager の PARTIAL_WAKE_LOCK をラップする。 */
class AndroidPlatformWakeLock(private val appContext: Context) : PlatformWakeLock {

    private var screenWakeLock: PowerManager.WakeLock? = null
    private val powerManager: PowerManager? by lazy {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    override fun acquire() {
        try {
            val pm = powerManager
            if (pm == null) {
                Log.w(TAG, "PowerManager unavailable for WakeLock")
                return
            }
            if (screenWakeLock == null || !screenWakeLock!!.isHeld) {
                screenWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "nezumiai:generation"
                )
                screenWakeLock?.acquire(60 * 60 * 1000) // 60分のタイムアウト
                Log.d(TAG, "WakeLock acquired for generation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }

    override fun release() {
        try {
            if (screenWakeLock != null && screenWakeLock!!.isHeld) {
                screenWakeLock!!.release()
                Log.d(TAG, "WakeLock released after generation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock", e)
        }
    }

    private companion object {
        const val TAG = "AndroidPlatformWakeLock"
    }
}

/** [PlatformTtsPlayer] の Android 実装。既存の [VoicevoxStreamingTts] をラップする。 */
class AndroidPlatformTtsPlayer(
    private val appContext: Context,
    private val streamingTts: VoicevoxStreamingTts
) : PlatformTtsPlayer {

    override fun speakStreaming(
        scope: kotlinx.coroutines.CoroutineScope,
        text: String,
        onChunkStart: ((String) -> Unit)?,
        onError: ((Throwable) -> Unit)?,
        onComplete: (() -> Unit)?
    ): kotlinx.coroutines.Job {
        return streamingTts.speakStreaming(
            scope = scope,
            text = text,
            onChunkStart = onChunkStart,
            onError = onError,
            onComplete = onComplete
        )
    }

    override fun stop() = streamingTts.stop()

    override suspend fun playAudio(audioData: ByteArray) {
        val tempFile = withContext(Dispatchers.IO) {
            java.io.File.createTempFile("tts", ".wav", appContext.cacheDir).also { file ->
                file.outputStream().use { it.write(audioData) }
            }
        }

        withContext(Dispatchers.Main) {
            var mediaPlayer: android.media.MediaPlayer? = null
            var completed = false

            fun cleanup() {
                if (completed) return
                completed = true
                runCatching { mediaPlayer?.release() }
                mediaPlayer = null
                tempFile.delete()
            }

            try {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    val player = android.media.MediaPlayer()
                    mediaPlayer = player
                    player.setOnCompletionListener(android.media.MediaPlayer.OnCompletionListener {
                        cleanup()
                        if (continuation.isActive) continuation.resume(Unit) {}
                    })
                    player.setOnErrorListener(android.media.MediaPlayer.OnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error during TTS playback: what=$what extra=$extra")
                        cleanup()
                        if (continuation.isActive) continuation.resume(Unit) {}
                        true
                    })
                    continuation.invokeOnCancellation {
                        cleanup()
                    }
                    player.setDataSource(tempFile.absolutePath)
                    player.prepare()
                    player.start()
                    Log.d(TAG, "VOICEVOX playback started")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio", e)
                cleanup()
            }
        }
    }

    private companion object {
        const val TAG = "AndroidPlatformTtsPlayer"
    }
}

/** [PlatformKeyValueStore] の Android 実装。SharedPreferences をラップする。 */
class AndroidPlatformKeyValueStore(
    context: Context,
    prefsName: String
) : PlatformKeyValueStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? =
        prefs.getString(key, default)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}

/** SD モデルパス解決の Android 実装。filesDir / 外部ストレージを走査する。 */
class AndroidPlatformSdModelPathResolver(
    private val appContext: Context
) : com.nezumi_ai.presentation.viewmodel.platform.PlatformSdModelPathResolver {

    override fun findAvailableSdModelPath(): String {
        // 保存済みパスを優先
        val savedPath = PreferencesHelper.getSdModelPath(appContext).trim()
        if (savedPath.isNotEmpty() && File(savedPath).isDirectory && isProbableSdModelDir(File(savedPath))) {
            return savedPath
        }

        val models = mutableListOf<String>()

        // sd_models directory
        val sdModelsDir = File(appContext.filesDir, "sd_models")
        sdModelsDir.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(file.absolutePath)
            }
        }

        // App external files directory
        val appDir = appContext.getExternalFilesDir(null)
        appDir?.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(file.absolutePath)
            }
        }

        // Imported models directory
        val importedDir = File(appContext.filesDir, "models/imported")
        importedDir.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(file.absolutePath)
            }
        }

        return models.firstOrNull() ?: ""
    }

    override fun resolveSdModelPathByName(modelName: String): String? {
        val name = modelName.trim()

        // sd_models directory
        val sdModelsDir = File(appContext.filesDir, "sd_models")
        sdModelsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val targetDir = resolveNestedSdModelDirForName(dir)
            if (targetDir.name == name && isProbableSdModelDir(targetDir)) {
                return targetDir.absolutePath
            }
        }

        // App external files directory
        val appDir = appContext.getExternalFilesDir(null)
        appDir?.listFiles()?.forEach { file ->
            if (file.name == name && isProbableSdModelDir(file)) {
                return file.absolutePath
            }
        }

        // Imported models directory
        val importedDir = File(appContext.filesDir, "models/imported")
        importedDir.listFiles()?.forEach { file ->
            if (file.name == name && isProbableSdModelDir(file)) {
                return file.absolutePath
            }
        }

        return null
    }

    private fun isProbableSdModelDir(file: File): Boolean {
        return SdModelLayout.isUsableModelDir(file) || SdModelLayout.isLegacyQnnDir(file)
    }

    private fun resolveNestedSdModelDirForName(dir: File): File {
        var current = dir
        repeat(3) {
            val children = current.listFiles()?.toList() ?: return current
            if (children.size == 1 && children[0].isDirectory) current = children[0]
            else return current
        }
        return current
    }
}
