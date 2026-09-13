package com.nezumi_ai.data.inference.remote

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.nezumi_ai.data.inference.GgufInferenceEngine
import com.nezumi_ai.data.inference.LlamaBridge
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
 * GGUF (llama.cpp) 推論を `:gguf` 別プロセスで実行する Service。
 *
 * `android:process=":gguf"` で宣言され、メインプロセスとは
 * [IRemoteInferenceEngine] (AIDL) で通信する。
 *
 * 内部には既存の [GgufInferenceEngine] をそのまま保持し、推論ロジックは
 * 一切変更せず委譲する。このプロセスが終了されれば、llama.cpp の
 * `llama_backend_free()` 未呼出で残り続ける Vulkan/OpenCL の
 * グローバル状態ごと OS が回収するため、メインプロセスから
 * エンジン切替時に [requestProcessExit] 経由で終了させる想定。
 */
class GgufInferenceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 推論ロジック本体。プロセス内で唯一のインスタンスとして保持する。
     * lazy にして、bind されただけではネイティブライブラリをロードしない。
     */
    private val ggufEngine: GgufInferenceEngine by lazy {
        Log.i(TAG, "creating GgufInferenceEngine in :gguf process (pid=${Process.myPid()})")
        GgufInferenceEngine(applicationContext)
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
                    val result = ggufEngine.loadModel(
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
                    val result = ggufEngine.unloadModel()
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
                    // Bug fix(#LiteRT-stream-binder-throttle 横展開): GGUF はトークン単位で
                    // emit されるため LiteRT-LM (1 文字単位) ほど頻度は高くないが、生成速度が
                    // 一定を超えると同じ oneway Binder トランザクションバッファの詰まりが
                    // 再現する。時間ウィンドウでまとめて送ることで Binder トランザクション数を
                    // 生成速度から切り離す。
                    collectBatchedForBinder(
                        source = ggufEngine.inference(
                            sessionId, prompt,
                            InferenceConfigBundle.fromBundle(config)
                        ).catch { t ->
                            if (t !is CancellationException) {
                                callback.onError(t.message ?: "inference failed")
                            }
                        }
                    ) { batch ->
                        callback.onToken(batch)
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
                // ファイルパス → Bitmap / ByteArray にデコード。
                // 読み終わった一時ファイルは削除してキャッシュを圧迫しないようにする。
                // デコードは重いため Binder スレッドではなくワーカー上で行う。
                val images = withContext(Dispatchers.IO) {
                    imagePaths.orEmpty().mapNotNull { path -> decodeBitmapAndDelete(path) }
                }
                val audioClips = withContext(Dispatchers.IO) {
                    audioPaths.orEmpty().mapNotNull { path -> readBytesAndDelete(path) }
                }
                try {
                    // Bug fix(#LiteRT-stream-binder-throttle 横展開): 上の inference() と同じ理由。
                    collectBatchedForBinder(
                        source = ggufEngine.inferenceWithMedia(
                            sessionId, prompt, images, audioClips,
                            InferenceConfigBundle.fromBundle(config)
                        ).catch { t ->
                            if (t !is CancellationException) {
                                callback.onError(t.message ?: "inferenceWithMedia failed")
                            }
                        }
                    ) { batch ->
                        callback.onToken(batch)
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
                runCatching { ggufEngine.cancelInference() }
                    .onFailure { Log.w(TAG, "cancelInference failed", it) }
            }
        }

        override fun isAvailable(): Boolean =
            runCatching {
                kotlinx.coroutines.runBlocking { ggufEngine.isAvailable() }
            }.getOrDefault(false)

        override fun getEngineStatus(): Bundle = Bundle().apply {
            putBoolean(
                RemoteEngineStatusKeys.KEY_GPU_FALLBACK_OCCURRED,
                ggufEngine.gpuBackendFallbackOccurred
            )
            putString(
                RemoteEngineStatusKeys.KEY_ACTUAL_GPU_BACKEND,
                ggufEngine.actualGpuBackend
            )
            putBoolean(
                RemoteEngineStatusKeys.KEY_HAS_GGUF_CHAT_TEMPLATE,
                ggufEngine.hasGgufChatTemplate()
            )
        }

        // ─── GGUF 固有 ────────────────────────────────────────────

        override fun clearKvCacheIfLoaded() {
            runCatching { ggufEngine.clearKvCacheIfLoaded() }
                .onFailure { Log.w(TAG, "clearKvCacheIfLoaded failed", it) }
        }

        override fun requestForceClearBeforeNextInference() {
            runCatching { ggufEngine.requestForceClearBeforeNextInference() }
                .onFailure { Log.w(TAG, "requestForceClearBeforeNextInference failed", it) }
        }

        override fun formatWithGgufChatTemplate(
            messagesJson: String?,
            enableThinking: Boolean,
            callback: IRemoteStringCallback?
        ) {
            if (callback == null) return
            serviceScope.launch {
                try {
                    val result = ggufEngine.formatWithGgufChatTemplate(
                        messagesJson ?: "", enableThinking
                    )
                    callback.onResult(result)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    callback.onError(t.message ?: "formatWithGgufChatTemplate failed")
                }
            }
        }

        override fun formatWithJinjaChatTemplate(
            messagesJson: String?,
            chatTemplate: String?,
            enableThinking: Boolean,
            callback: IRemoteStringCallback?
        ) {
            if (callback == null) return
            serviceScope.launch {
                try {
                    val result = ggufEngine.formatWithJinjaChatTemplate(
                        messagesJson ?: "", chatTemplate ?: "", enableThinking
                    )
                    callback.onResult(result)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    callback.onError(t.message ?: "formatWithJinjaChatTemplate failed")
                }
            }
        }

        override fun parseWithGgufChatTemplate(
            output: String?,
            isPartial: Boolean,
            callback: IRemoteStringCallback?
        ) {
            if (callback == null) return
            serviceScope.launch {
                try {
                    val parsed = ggufEngine.parseWithGgufChatTemplate(output ?: "", isPartial)
                    if (parsed == null) {
                        callback.onResult("{}")
                    } else {
                        val json = org.json.JSONObject()
                            .put("content", parsed.content)
                            .put("reasoning_content", parsed.reasoningContent)
                            .toString()
                        callback.onResult(json)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    callback.onError(t.message ?: "parseWithGgufChatTemplate failed")
                }
            }
        }

        // ─── コンテキストメーター正確化 (GGUF 固有) ─────────────────

        override fun getGgufPastTokenCount(): Int =
            ggufEngine.getPastTokenCount() ?: -1

        override fun getGgufLastPromptTokenInfo(): IntArray? {
            val info = ggufEngine.getLastPromptTokenInfo() ?: return null
            return intArrayOf(info.first, info.second)
        }

        override fun countGgufPromptTokens(text: String?): Int =
            if (text.isNullOrEmpty()) -1 else ggufEngine.countPromptTokens(text) ?: -1

        // ─── LiteRT-LM 固有 (GGUF 側は no-op) ─────────────────────

        override fun markSessionHasMedia(sessionId: Long) = Unit
        override fun clearSessionMediaHistory(sessionId: Long) = Unit
        override fun forceReset(callback: IRemoteResultCallback?) {
            callback?.onSuccess()
        }

        // ─── プロセス管理 / プローブ ──────────────────────────────

        override fun getRemotePid(): Int = Process.myPid()

        override fun requestProcessExit() {
            Log.i(TAG, "requestProcessExit received; unloading model then killing :gguf process")
            serviceScope.launch {
                // モデル・mmproj 等の明示的クリーンアップを試みてから終了する。
                // llama_backend_free() は API 契約上「プロセス終了時に1回」であり、
                // ここで呼ぶと再初期化が未定義になるため呼ばない。
                // Vulkan/OpenCL のグローバル状態はプロセスごと OS が回収する。
                runCatching { ggufEngine.cancelInference() }
                runCatching { ggufEngine.unloadModel() }
                    .onFailure { Log.w(TAG, "unloadModel before exit failed", it) }
                Log.i(TAG, "killing :gguf process (pid=${Process.myPid()})")
                Process.killProcess(Process.myPid())
            }
        }

        override fun probeGpuBackend(backend: String?): Boolean {
            if (backend == null) return false
            if (!LlamaBridge.isLibraryLoaded()) return false
            return runCatching { LlamaBridge.nativeProbeGpuBackendAvailable(backend) }
                .getOrDefault(false)
        }

        override fun getCompiledGpuBackends(): Array<String> =
            runCatching { LlamaBridge.compiledGpuBackends().toTypedArray() }
                .getOrDefault(emptyArray())

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
            if (callback == null) return
            // TTS 合成は重いためワーカースレッドで実行する。
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (modelPath == null || tokenizerPath == null || text == null || outPath == null) {
                        callback.onError("ttsSynthesize: required argument is null")
                        return@launch
                    }
                    if (!LlamaBridge.isLibraryLoaded()) {
                        callback.onError("llama_bridge not loaded in :gguf process")
                        return@launch
                    }
                    val result = LlamaBridge.nativeTtsSynthesize(
                        modelPath, tokenizerPath, text, speakerPath, outPath,
                        nThreads, nPredict, seed
                    )
                    callback.onResult(result)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "ttsSynthesize threw", t)
                    callback.onError(t.message ?: "ttsSynthesize threw")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: :gguf process (pid=${Process.myPid()})")
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
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
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
        private const val TAG = "GgufInferenceService"
    }
}
