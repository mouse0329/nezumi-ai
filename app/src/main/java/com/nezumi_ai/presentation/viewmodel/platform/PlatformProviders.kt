package com.nezumi_ai.presentation.viewmodel.platform

import android.graphics.Bitmap
import android.net.Uri

/**
 * ChatViewModel 分割 (Compose Multiplatform 前提整理) のためのプラットフォーム抽象。
 *
 * 各インターフェースは commonMain に置ける「純粋な契約」として定義し、
 * Android 固有実装は `platform.android` パッケージ側に寄せる。
 * 現段階では Android 実装のみを提供する (iOS 実装は後日)。
 */

/** 画像 / 音声 / ギャラリー保存など、OS のメディア I/O を隠す。 */
interface PlatformMediaLoader {
    suspend fun loadBitmapFromUri(uri: Uri): Bitmap?
    suspend fun loadAudioBytesFromUri(uri: Uri): ByteArray?
    fun scaleBitmapTo1024(bitmap: Bitmap): Bitmap
    suspend fun saveBitmapToGallery(bitmap: Bitmap)
    fun persistUriIfNeeded(uriString: String?): String?
    fun persistUriIfNeeded(uri: Uri?): String?
    fun deleteStoredFileIfOwned(uriString: String?)
    fun deleteMessageAttachments(imageUri: String?, audioUri: String?)
    fun toUri(uriString: String?): Uri?
    fun savePngBitmap(bitmap: Bitmap, baseName: String): String?
}

/** 画面スリープ抑制 (WakeLock) を隠す。 */
interface PlatformWakeLock {
    fun acquire()
    fun release()
}

/** VOICEVOX などの TTS 再生を隠す。 */
interface PlatformTtsPlayer {
    fun speakStreaming(
        scope: kotlinx.coroutines.CoroutineScope,
        text: String,
        onChunkStart: ((String) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ): kotlinx.coroutines.Job
    fun stop()
    suspend fun playAudio(audioData: ByteArray)
}

/** SharedPreferences 直叩きを隠すキー・バリューストア。 */
interface PlatformKeyValueStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

/** SD (画像生成) モデルファイルのパス解決を隠す。 */
interface PlatformSdModelPathResolver {
    fun findAvailableSdModelPath(): String
    fun resolveSdModelPathByName(modelName: String): String?
}
