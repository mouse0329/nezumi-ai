package com.nezumi_ai.data.inference.remote

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.GgufInferenceEngine
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.LlamaCppGpuBackend
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * `:gguf` プロセスで動く [GgufInferenceEngine] を、AIDL 越しに
 * [AIInferenceEngine] として振る舞わせるメインプロセス側アダプタ。
 *
 * 推論ロジック自体は `:gguf` プロセス内の既存 [GgufInferenceEngine] が
 * そのまま担う。ここでは以下だけを行う:
 *  - AIInferenceEngine 呼び出し → AIDL 呼び出しへの変換
 *  - Bitmap / ByteArray のマルチモーダル入力 → 一時ファイルへの書き出し
 *    (Binder transaction の 1MB 上限を避けるためバイナリは直接渡さない)
 *  - GGUF 固有 API (chat template 操作 / KV クリア / バックエンド状態取得) の委譲
 *
 * エンジン切替時のプロセス強制終了は [shutdownProcess] 経由で行う。
 * 呼び出し元 (ModelManager) は、GGUF → 別エンジンへの切替時にこれを呼ぶことで、
 * llama_backend_free() 未呼出のまま残る Vulkan/OpenCL グローバル状態を
 * OS のプロセス回収に委ねられる。
 */
class RemoteGgufInferenceEngine(
    context: Context
) : AIInferenceEngine {

    private val appContext = context.applicationContext
    private val connection = RemoteEngineConnection(
        context = appContext,
        serviceComponent = ComponentName(appContext, GgufInferenceService::class.java.name),
        tag = TAG
    )

    // ─── AIInferenceEngine 実装 ─────────────────────────────────

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> =
        connection.loadModel(modelName, InferenceConfigBundle.toBundle(config))

    override suspend fun unloadModel(): Result<Unit> = connection.unloadModel()

    override suspend fun cancelInference() = connection.cancelInference()

    override suspend fun isAvailable(): Boolean =
        // llama_bridge ライブラリ自体は :gguf プロセス側でしかロードされないため、
        // ここでのチェックは「サービスに bind できるか」までに留める。
        // ネイティブ可用性 (llama_backend_init 成否) は :gguf 側で判定される。
        connection.isAvailableSync()

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
        // Bitmap / ByteArray は Binder で直接渡せないため、アプリ内部ストレージの
        // 一時ファイルに書き出してパスだけを渡す。:gguf プロセスは同一 UID のため
        // /data/user/0/<pkg>/cache 配下をそのまま読める (計画書 4.3 / 5.2)。
        // 削除は :gguf 側 (GgufInferenceService) がデコード後に行う。
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
                if (delta != null) trySend(delta)
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
            // コレクタがキャンセルされたらリモート側の推論も止める。
            // 既存の GgufInferenceEngine.cancelInference と同等の语义。
            runCatching { service.cancelInference() }
        }
    }

    // ─── GGUF 固有 API (ModelManager / ChatViewModel から利用) ────

    /** 直近ロードで要求した GPU バックエンドが利用できず CPU へフォールバックしたか。 */
    val gpuBackendFallbackOccurred: Boolean
        get() = connection.getEngineStatusSync()
            .getBoolean(RemoteEngineStatusKeys.KEY_GPU_FALLBACK_OCCURRED, false)

    /** 直近ロードで実際に使われたバックエンド ("CPU" / "OPENCL" / "VULKAN")。 */
    val actualGpuBackend: String
        get() = connection.getEngineStatusSync()
            .getString(RemoteEngineStatusKeys.KEY_ACTUAL_GPU_BACKEND) ?: LlamaCppGpuBackend.CPU

    fun hasGgufChatTemplate(): Boolean =
        connection.getEngineStatusSync()
            .getBoolean(RemoteEngineStatusKeys.KEY_HAS_GGUF_CHAT_TEMPLATE, false)

    fun clearKvCacheIfLoaded() = connection.clearKvCacheIfLoadedSync()

    fun requestForceClearBeforeNextInference() =
        connection.requestForceClearBeforeNextInferenceSync()

    suspend fun formatWithGgufChatTemplate(
        messagesJson: String,
        enableThinking: Boolean
    ): String = connection.formatWithGgufChatTemplate(messagesJson, enableThinking)

    suspend fun formatWithJinjaChatTemplate(
        messagesJson: String,
        chatTemplate: String,
        enableThinking: Boolean
    ): String = connection.formatWithJinjaChatTemplate(messagesJson, chatTemplate, enableThinking)

    fun parseWithGgufChatTemplate(
        output: String,
        isPartial: Boolean
    ): GgufInferenceEngine.GgufChatParseResult? {
        // ストリーミング中の部分パースは 1 トークンごとに呼ばれ得るが、
        // ここではブロッキング IPC のまま実装する。計画書 7 章の通り、
        // 体感遅延が問題になる場合はメインプロセス側キャッシュか
        // Kotlin パーサーへのフォールバックを検討する。
        val json = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            connection.parseWithGgufChatTemplate(output, isPartial)
        } ?: return null
        return runCatching {
            val obj = org.json.JSONObject(json)
            GgufInferenceEngine.GgufChatParseResult(
                content = obj.optString("content", ""),
                reasoningContent = obj.optString("reasoning_content", "")
            )
        }.getOrNull()
    }

    // ─── プロセス管理 / プローブ ─────────────────────────────────

    /**
     * `:gguf` プロセスを強制終了し、ネイティブのグローバル状態
     * (Vulkan インスタンス / OpenCL コンテキスト / GGML backend registry) を
     * OS に回収させる。GGUF → LiteRT-LM 等へのエンジン切替時に呼ぶ。
     */
    suspend fun shutdownProcess() = connection.shutdownProcess()

    /** :gguf プロセス内で llama.cpp に実問い合わせして GPU バックエンド可用性を返す。 */
    fun probeGpuBackend(backend: String): Boolean = connection.probeGpuBackendSync(backend)

    /** :gguf プロセス内の llama.cpp ビルドに含まれる GPU バックエンド一覧。 */
    fun compiledGpuBackends(): Set<String> = connection.getCompiledGpuBackendsSync()

    /**
     * Qwen3-TTS のデバッグ合成を :gguf プロセスで実行する。
     * llama_bridge をメインプロセスにロードしないための代替経路。
     */
    suspend fun ttsSynthesize(
        modelPath: String,
        tokenizerPath: String,
        text: String,
        speakerPath: String?,
        outPath: String,
        nThreads: Int,
        nPredict: Int,
        seed: Int
    ): String = connection.ttsSynthesize(
        modelPath, tokenizerPath, text, speakerPath, outPath, nThreads, nPredict, seed
    )

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
        private const val TAG = "RemoteGgufInferenceEngine"

        /**
         * マルチモーダル入力の受け渡し用ディレクトリ (cacheDir 直下)。
         * :gguf 側サービスが読み終わったらファイルを削除する。
         */
        const val SHARED_MEDIA_DIR = "remote_engine_media"
    }
}
