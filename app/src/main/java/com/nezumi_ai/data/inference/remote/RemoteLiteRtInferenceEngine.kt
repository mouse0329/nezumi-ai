package com.nezumi_ai.data.inference.remote

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.InferenceConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * `:litert` プロセスで動く LiteRtLmEngine を、AIDL 越しに
 * [AIInferenceEngine] として振る舞わせるメインプロセス側アダプタ。
 *
 * GGUF 側 ([RemoteGgufInferenceEngine]) と対称の構成。
 * LiteRT-LM 固有の `forceReset()` / `markSessionHasMedia()` も
 * ここ経由でリモートに委譲する。
 *
 * エンジン切替 (LiteRT-LM → GGUF 等) 時は [shutdownProcess] で
 * `:litert` プロセスを終了させる。これにより `Engine.close()` が
 * SIGABRT し得る状況を避けつつ、TFLite GPU delegate / NPU dispatch
 * ライブラリのネイティブリソースを OS が強制回収する。
 */
class RemoteLiteRtInferenceEngine(
    context: Context
) : AIInferenceEngine {

    private val appContext = context.applicationContext
    private val connection = RemoteEngineConnection(
        context = appContext,
        serviceComponent = ComponentName(appContext, LiteRtEngineService::class.java.name),
        tag = TAG
    )

    // ─── AIInferenceEngine 実装 ─────────────────────────────────

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> =
        connection.loadModel(modelName, InferenceConfigBundle.toBundle(config))

    override suspend fun unloadModel(): Result<Unit> = connection.unloadModel()

    override suspend fun cancelInference() = connection.cancelInference()

    override suspend fun isAvailable(): Boolean = connection.isAvailableSync()

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceInternal(sessionId, prompt, emptyList(), emptyList(), config)

    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> {
        val imagePaths = images.mapIndexedNotNull { index, bitmap ->
            writeBitmapToSharedCache(bitmap, index)
        }
        val audioPaths = audioClips.mapIndexedNotNull { index, bytes ->
            writeAudioToSharedCache(bytes, index)
        }
        return inferenceInternal(sessionId, prompt, imagePaths, audioPaths, config)
    }

    private fun inferenceInternal(
        sessionId: Long,
        prompt: String,
        imagePaths: List<String>,
        audioPaths: List<String>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow {
        val service = try {
            connection.getService()
        } catch (t: Throwable) {
            close(t)
            return@callbackFlow
        }

        val callback = object : IRemoteTokenCallback.Stub() {
            override fun onToken(delta: String?) {
                if (delta != null) {
                    // Bug fix(#LiteRT-stream-drop): Binder コールバックは非サスペンドなので
                    // trySend(...) 失敗時に partial chunk が黙って捨てられていた。
                    // 完了時の FINAL だけは後段で届き得るため、
                    // 「完成文は表示されるが増分ストリーミングだけ見えない」症状になる。
                    val result = trySendBlocking(delta)
                    if (!result.isSuccess) {
                        Log.w(TAG, "Dropping remote LiteRT token chunk because callbackFlow channel is not ready")
                    }
                }
            }

            override fun onComplete() {
                close()
            }

            override fun onError(message: String?) {
                close(RuntimeException(message ?: "remote inference failed"))
            }
        }

        try {
            if (imagePaths.isEmpty() && audioPaths.isEmpty()) {
                service.inference(sessionId, prompt, InferenceConfigBundle.toBundle(config), callback)
            } else {
                service.inferenceWithMedia(
                    sessionId, prompt,
                    imagePaths.toTypedArray(), audioPaths.toTypedArray(),
                    InferenceConfigBundle.toBundle(config), callback
                )
            }
        } catch (t: Throwable) {
            close(t)
            return@callbackFlow
        }

        awaitClose {
            runCatching { service.cancelInference() }
        }
    }

    // ─── LiteRT-LM 固有 API ─────────────────────────────────────

    /** 現在ロード済みのバックエンド ("GPU" / "CPU" / "NPU"、未ロード時は null)。 */
    val loadedBackend: String?
        get() = connection.getEngineStatusSync()
            .getString(RemoteEngineStatusKeys.KEY_LOADED_BACKEND)

    /**
     * 現在の会話の KV キャッシュ内トークン数 (prefill + decode)。
     * 画像・音声を含む実測値。コンテキストメーター正確化用。未取得時は null。
     */
    fun getConversationTokenCountSync(): Int? =
        connection.getEngineStatusSync()
            .getInt(RemoteEngineStatusKeys.KEY_CONVERSATION_TOKEN_COUNT, -1)
            .takeIf { it >= 0 }

    /**
     * 直近推論の実測ベンチマーク。
     * LiteRT-LM の getBenchmarkInfo() から取得した prefill/decode トークン数と TPS。
     */
    data class LiteRtBenchmark(
        val prefillTokens: Int,
        val decodeTokens: Int,
        val decodeTokensPerSecond: Double,
        val ttftMs: Double,
    )

    /** 直近推論の実測ベンチマーク。未推論・未取得時は null。 */
    fun getLastBenchmarkSync(): LiteRtBenchmark? {
        val status = connection.getEngineStatusSync()
        val prefill = status.getInt(RemoteEngineStatusKeys.KEY_LAST_PREFILL_TOKENS, -1)
        if (prefill < 0) return null
        return LiteRtBenchmark(
            prefillTokens = prefill,
            decodeTokens = status.getInt(RemoteEngineStatusKeys.KEY_LAST_DECODE_TOKENS, -1),
            decodeTokensPerSecond = status.getDouble(RemoteEngineStatusKeys.KEY_LAST_DECODE_TPS, -1.0),
            ttftMs = status.getDouble(RemoteEngineStatusKeys.KEY_LAST_TTFT_MS, -1.0),
        )
    }

    /**
     * 「このセッションは media を含む」ことをリモートの LiteRtLmEngine に伝える。
     * ChatViewModel のマルチターン画像対応で利用する。
     */
    fun markSessionHasMedia(sessionId: Long) = connection.markSessionHasMediaSync(sessionId)

    fun clearSessionMediaHistory(sessionId: Long) =
        connection.clearSessionMediaHistorySync(sessionId)

    /**
     * `Engine.close()` を経ない強制リセット (旧 LiteRtLmEngine.forceReset 相当)。
     * プロセス分離後は [shutdownProcess] がこの役割を代替するが、
     * 移行期間の安全策として両方残す (計画書 8 章)。
     */
    suspend fun forceReset() {
        connection.forceReset()
            .onFailure { Log.w(TAG, "forceReset via remote failed", it) }
    }

    // ─── プロセス管理 ───────────────────────────────────────────

    /**
     * `:litert` プロセスを強制終了し、TFLite GPU delegate / NPU dispatch
     * ライブラリのネイティブ状態を OS に回収させる。
     */
    suspend fun shutdownProcess() = connection.shutdownProcess()

    // ─── 一時ファイル書き出し ────────────────────────────────────

    private suspend fun writeBitmapToSharedCache(bitmap: Bitmap, index: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.cacheDir, SHARED_MEDIA_DIR).apply { mkdirs() }
                val file = File(dir, "img_${System.currentTimeMillis()}_${index}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                file.absolutePath
            }.onFailure { Log.w(TAG, "failed to write bitmap to shared cache", it) }
                .getOrNull()
        }

    private suspend fun writeAudioToSharedCache(bytes: ByteArray, index: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.cacheDir, SHARED_MEDIA_DIR).apply { mkdirs() }
                val file = File(dir, "audio_${System.currentTimeMillis()}_${index}.wav")
                file.writeBytes(bytes)
                file.absolutePath
            }.onFailure { Log.w(TAG, "failed to write audio to shared cache", it) }
                .getOrNull()
        }

    companion object {
        private const val TAG = "RemoteLiteRtInferenceEngine"
        private const val SHARED_MEDIA_DIR = "remote_engine_media"
    }
}
