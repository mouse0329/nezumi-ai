package com.nezumi_ai.data.inference.remote

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.nezumi_ai.data.inference.LiteRtLmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LiteRT-LM 推論を `:litert` 別プロセスで実行する Service。
 *
 * `android:process=":litert"` で宣言され、メインプロセスとは
 * [IRemoteInferenceEngine] (AIDL) で通信する。
 *
 * 内部には既存の [LiteRtLmEngine] をそのまま保持する。
 * `Engine.close()` が GPU バックエンドで SIGABRT し得る既知の問題があるため、
 * エンジン切替時は正規の close に頼らず [requestProcessExit] で
 * プロセスごと終了させ、OS にネイティブリソースを回収させる。
 */
class LiteRtEngineService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 推論ロジック本体。lazy にして bind されただけでは
     * LiteRT-LM のネイティブ初期化が走らないようにする。
     */
    private val liteRtEngine: LiteRtLmEngine by lazy {
        Log.i(TAG, "creating LiteRtLmEngine in :litert process (pid=${Process.myPid()})")
        LiteRtLmEngine(applicationContext)
    }

    private val binder = object : IRemoteInferenceEngine.Stub() {

        // ─── モデルライフサイクル ─────────────────────────────────

        override fun loadModel(modelName: String?, config: Bundle?, callback: IRemoteResultCallback?) {
            if (callback == null) return
            serviceScope.launch {
                try {
                    if (modelName == null) {
                        callback.onError("modelName is null")
                        return@launch
                    }
                    val result = liteRtEngine.loadModel(
                        modelName,
                        InferenceConfigBundle.fromBundle(config)
                    )
                    if (result.isSuccess) {
                        callback.onSuccess()
                    } else {
                        callback.onError(result.exceptionOrNull()?.message ?: "loadModel failed")
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "loadModel threw", t)
                    callback.onError(t.message ?: "loadModel threw")
                }
            }
        }

        override fun unloadModel(callback: IRemoteResultCallback?) {
            if (callback == null) return
            serviceScope.launch {
                try {
                    val result = liteRtEngine.unloadModel()
                    if (result.isSuccess) {
                        callback.onSuccess()
                    } else {
                        callback.onError(result.exceptionOrNull()?.message ?: "unloadModel failed")
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    callback.onError(t.message ?: "unloadModel threw")
                }
            }
        }

        // ─── 推論 ─────────────────────────────────────────────────

        override fun inference(
            sessionId: Long,
            prompt: String?,
            config: Bundle?,
            callback: IRemoteTokenCallback?
        ) {
            if (callback == null) return
            if (prompt == null) {
                callback.onError("prompt is null")
                return
            }
            serviceScope.launch {
                try {
                    liteRtEngine.inference(
                        sessionId, prompt,
                        InferenceConfigBundle.fromBundle(config)
                    ).catch { t ->
                        if (t !is CancellationException) {
                            callback.onError(t.message ?: "inference failed")
                        }
                    }.collect { chunk ->
                        callback.onToken(chunk)
                    }
                    callback.onComplete()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "inference threw", t)
                    callback.onError(t.message ?: "inference threw")
                }
            }
        }

        override fun inferenceWithMedia(
            sessionId: Long,
            prompt: String?,
            imagePaths: Array<out String>?,
            audioPaths: Array<out String>?,
            config: Bundle?,
            callback: IRemoteTokenCallback?
        ) {
            if (callback == null) return
            if (prompt == null) {
                callback.onError("prompt is null")
                return
            }

            serviceScope.launch {
                // デコードは重いため Binder スレッドではなくワーカー上で行う。
                val images = withContext(Dispatchers.IO) {
                    imagePaths.orEmpty().mapNotNull { path -> decodeBitmapAndDelete(path) }
                }
                val audioClips = withContext(Dispatchers.IO) {
                    audioPaths.orEmpty().mapNotNull { path -> readBytesAndDelete(path) }
                }
                try {
                    liteRtEngine.inferenceWithMedia(
                        sessionId, prompt, images, audioClips,
                        InferenceConfigBundle.fromBundle(config)
                    ).catch { t ->
                        if (t !is CancellationException) {
                            callback.onError(t.message ?: "inferenceWithMedia failed")
                        }
                    }.collect { chunk ->
                        callback.onToken(chunk)
                    }
                    callback.onComplete()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "inferenceWithMedia threw", t)
                    callback.onError(t.message ?: "inferenceWithMedia threw")
                }
            }
        }

        override fun cancelInference() {
            serviceScope.launch {
                runCatching { liteRtEngine.cancelInference() }
                    .onFailure { Log.w(TAG, "cancelInference failed", it) }
            }
        }

        override fun isAvailable(): Boolean = true

        override fun getEngineStatus(): Bundle = Bundle().apply {
            putString(RemoteEngineStatusKeys.KEY_LOADED_BACKEND, liteRtEngine.currentLoadedBackend())
            // コンテキストメーター / TPS 正確化:
            //   会話の KV トークン数と直近推論の実測ベンチマークを公開する。
            putInt(
                RemoteEngineStatusKeys.KEY_CONVERSATION_TOKEN_COUNT,
                liteRtEngine.getConversationTokenCount() ?: -1
            )
            val bench = liteRtEngine.getLastBenchmarkSnapshot()
            putInt(RemoteEngineStatusKeys.KEY_LAST_PREFILL_TOKENS, bench?.prefillTokens ?: -1)
            putInt(RemoteEngineStatusKeys.KEY_LAST_DECODE_TOKENS, bench?.decodeTokens ?: -1)
            putDouble(RemoteEngineStatusKeys.KEY_LAST_DECODE_TPS, bench?.decodeTokensPerSecond ?: -1.0)
            putDouble(RemoteEngineStatusKeys.KEY_LAST_TTFT_MS, bench?.timeToFirstTokenMs ?: -1.0)
        }

        // ─── GGUF 固有 (LiteRT 側は no-op) ────────────────────────

        override fun clearKvCacheIfLoaded() = Unit
        override fun requestForceClearBeforeNextInference() = Unit
        override fun formatWithGgufChatTemplate(
            messagesJson: String?,
            enableThinking: Boolean,
            callback: IRemoteStringCallback?
        ) {
            callback?.onResult("")
        }

        override fun formatWithJinjaChatTemplate(
            messagesJson: String?,
            chatTemplate: String?,
            enableThinking: Boolean,
            callback: IRemoteStringCallback?
        ) {
            callback?.onResult("")
        }

        override fun parseWithGgufChatTemplate(
            output: String?,
            isPartial: Boolean,
            callback: IRemoteStringCallback?
        ) {
            callback?.onResult("{}")
        }

        // ─── コンテキストメーター正確化 (GGUF 固有のため LiteRT 側は no-op) ──

        override fun getGgufPastTokenCount(): Int = -1
        override fun getGgufLastPromptTokenInfo(): IntArray? = null
        override fun countGgufPromptTokens(text: String?): Int = -1

        // ─── LiteRT-LM 固有 ──────────────────────────────────────

        override fun markSessionHasMedia(sessionId: Long) {
            runCatching { liteRtEngine.markSessionHasMedia(sessionId) }
                .onFailure { Log.w(TAG, "markSessionHasMedia failed", it) }
        }

        override fun clearSessionMediaHistory(sessionId: Long) {
            runCatching { liteRtEngine.clearSessionMediaHistory(sessionId) }
                .onFailure { Log.w(TAG, "clearSessionMediaHistory failed", it) }
        }

        override fun forceReset(callback: IRemoteResultCallback?) {
            if (callback == null) return
            serviceScope.launch {
                try {
                    liteRtEngine.forceReset()
                    callback.onSuccess()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    callback.onError(t.message ?: "forceReset failed")
                }
            }
        }

        // ─── プロセス管理 ─────────────────────────────────────────

        override fun getRemotePid(): Int = Process.myPid()

        override fun requestProcessExit() {
            Log.i(TAG, "requestProcessExit received; cancelling inference then killing :litert process")
            serviceScope.launch {
                // Engine.close() は SIGABRT し得るため unloadModel() は呼ばず、
                // 会話キャンセルだけ行ってプロセスごと終了する。
                // TFLite GPU delegate / NPU dispatch のネイティブ状態は
                // OS がプロセス回収で一括解放する。
                runCatching { liteRtEngine.cancelInference() }
                    .onFailure { Log.w(TAG, "cancelInference before exit failed", it) }
                Log.i(TAG, "killing :litert process (pid=${Process.myPid()})")
                Process.killProcess(Process.myPid())
            }
        }

        // ─── GGUF プローブ系 (LiteRT 側では未対応) ────────────────

        override fun probeGpuBackend(backend: String?): Boolean = false
        override fun getCompiledGpuBackends(): Array<String> = emptyArray()
        override fun ttsSynthesize(
            modelPath: String?,
            tokenizerPath: String?,
            text: String?,
            speakerPath: String?,
            outPath: String?,
            nThreads: Int,
            nPredict: Int,
            seed: Int,
            callback: IRemoteStringCallback?
        ) {
            callback?.onError("ttsSynthesize is only supported on the :gguf process")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: :litert process (pid=${Process.myPid()})")
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── 一時ファイルの読み込み + 削除 ─────────────────────────────

    private fun decodeBitmapAndDelete(path: String): android.graphics.Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            runCatching { file.delete() }
            bitmap
        } catch (t: Throwable) {
            Log.w(TAG, "failed to decode image: $path", t)
            null
        }
    }

    private fun readBytesAndDelete(path: String): ByteArray? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            runCatching { file.delete() }
            bytes
        } catch (t: Throwable) {
            Log.w(TAG, "failed to read audio: $path", t)
            null
        }
    }

    companion object {
        private const val TAG = "LiteRtEngineService"
    }
}
