package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemma / LiteRT-LM 推論（[com.google.ai.edge.litertlm]）。
 * AI Edge Gallery の [LlmChatModelHelper] と同様に Engine + Conversation で推論し、
 * thought チャンネルを [InferenceStreamProtocol.encodeThinkChunk] で送出する。
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmEngine(
    private val appContext: Context
) : AIInferenceEngine {

    companion object {
        private const val TAG = "LiteRtLmEngine"
        private const val MAX_VISION_IMAGES = 5
        private const val MAX_BITMAP_EDGE = 1024
        private const val THOUGHT_CHANNEL = "thought"

        private fun shouldEmitPartialText(partial: String): Boolean {
            if (partial.isEmpty()) return false
            val t = partial.trimStart()
            return !t.startsWith("<ctrl", ignoreCase = true)
        }
    }

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var loadedConfig: InferenceConfig? = null
    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()

    /** Engine は同時に 1 セッションのみ。コールバックスレッドと awaitClose の競合もここで直列化する */
    private val activeConversationLock = Any()
    @Volatile
    private var activeLiteRtConversation: Conversation? = null
    
    /** セッション遷移検出用 */
    @Volatile
    private var lastSessionId: Long? = null

    /**
     * AI Edge Gallery 方式：推論キャンセル時は cancelProcess() だけ。
     * close() はセッション遷移時のみ。
     */
    private fun cancelActiveConversation() {
        synchronized(activeConversationLock) {
            val c = activeLiteRtConversation ?: return
            runCatching {
                Log.d(TAG, "Cancelling active conversation process")
                c.cancelProcess()
            }.onFailure { t ->
                Log.w(TAG, "Failed to cancel conversation process", t)
            }
        }
    }

    /**
     * セッション遷移時のみ使用。古い Conversation を close() して新たに作成する。
     */
    private fun closeAndResetActiveConversation() {
        synchronized(activeConversationLock) {
            val c = activeLiteRtConversation ?: return
            activeLiteRtConversation = null
            runCatching {
                Log.d(TAG, "Closing active conversation")
                c.close()
            }.onFailure { t ->
                Log.w(TAG, "Failed to close conversation", t)
            }
            Log.i(TAG, "Active conversation closed and reset")
        }
    }

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return try {
            modelMutex.withLock {
                val normalizedConfig = config.normalized()
                val modelFile = resolveLocalModelFile(modelName)
                if (modelFile == null || !modelFile.exists()) {
                    return Result.failure(IllegalStateException("Model file is not available"))
                }
                val modelPath = modelFile.absolutePath
                val needsReload = loadedModelPath != modelPath ||
                    loadedConfig != normalizedConfig ||
                    engine == null

                if (!needsReload) {
                    Log.d(TAG, "Model already loaded: $modelPath")
                    return Result.success(Unit)
                }

                runCatching { engine?.close() }
                engine = null
                loadedModelPath = null
                loadedConfig = null

                val preferredBackend = backendForConfig(normalizedConfig.backendType)
                val cacheDir = appContext.getExternalFilesDir(null)?.absolutePath

                fun tryCreate(withVisionAudio: Boolean, backend: Backend): Engine {
                    val ec = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        visionBackend = if (withVisionAudio) Backend.GPU() else null,
                        audioBackend = if (withVisionAudio) Backend.CPU() else null,
                        maxNumTokens = normalizedConfig.maxTokens,
                        cacheDir = cacheDir
                    )
                    val eng = Engine(ec)
                    eng.initialize()
                    return eng
                }

                fun loadWithBackend(backend: Backend): Engine {
                    return runCatching { tryCreate(withVisionAudio = true, backend) }
                        .getOrElse { first ->
                            Log.w(TAG, "Engine init with vision/audio failed, retrying text-only", first)
                            tryCreate(withVisionAudio = false, backend)
                        }
                }

                val eng = try {
                    loadWithBackend(preferredBackend)
                } catch (e: Throwable) {
                    if (normalizedConfig.backendType.uppercase() == "CPU") throw e
                    Log.w(
                        TAG,
                        "Engine init failed on backend=${normalizedConfig.backendType}, falling back to CPU",
                        e
                    )
                    loadWithBackend(Backend.CPU())
                }

                engine = eng
                loadedModelPath = modelPath
                loadedConfig = normalizedConfig
                Log.d(TAG, "LiteRT-LM engine loaded: $modelPath backend=${normalizedConfig.backendType}")
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to load model", e)
            Result.failure(e)
        }
    }

    private fun backendForConfig(backendType: String): Backend {
        return when (backendType.uppercase()) {
            "GPU" -> Backend.GPU()
            "NPU" -> Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
            else -> Backend.CPU()
        }
    }

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow {
        inferenceMutex.lock()
        val mutexReleased = AtomicBoolean(false)  // Thread-safe flag to prevent double-unlock
        fun releaseInferenceMutex() {
            if (mutexReleased.compareAndSet(false, true)) {
                inferenceMutex.unlock()
            }
        }

        val eng = modelMutex.withLock { engine }
        if (eng == null) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        val normalized = modelMutex.withLock { loadedConfig?.normalized() } ?: config.normalized()

        try {
            Log.d(TAG, "LiteRT inference session=$sessionId images=${images.size} audio=${audioClips.size} enableThinking=${normalized.enableThinking}")

            // セッション遷移を検出したら前の Conversation をクリア
            if (lastSessionId != null && lastSessionId != sessionId) {
                Log.i(TAG, "Session transition detected: $lastSessionId -> $sessionId, resetting conversation")
                closeAndResetActiveConversation()
            }
            lastSessionId = sessionId
            
            // Stability 優先：毎回新しい Conversation を作成（KV cache 破損を防ぐ）
            Log.d(TAG, "Creating new conversation for fresh inference")
            closeAndResetActiveConversation()  // 前回の Conversation をクリアしてから新規作成
            
            ExperimentalFlags.enableConversationConstrainedDecoding = false
            val samplerConfig = if (normalized.backendType == "NPU") {
                null
            } else {
                SamplerConfig(
                    topK = normalized.maxTopK,
                    topP = normalized.topP.toDouble(),
                    temperature = normalized.temperature.toDouble()
                )
            }
            val conv = eng.createConversation(
                ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = null,
                    tools = emptyList()
                )
            )
            ExperimentalFlags.enableConversationConstrainedDecoding = false
            synchronized(activeConversationLock) {
                activeLiteRtConversation = conv
            }

            val contents = mutableListOf<Content>()
            for (bitmap in images.take(MAX_VISION_IMAGES)) {
                val scaled = scaleBitmapForVision(bitmap)
                try {
                    contents.add(Content.ImageBytes(scaled.toPngByteArray()))
                } finally {
                    if (scaled !== bitmap) {
                        scaled.recycle()
                    }
                }
            }
            for (clip in audioClips) {
                if (clip.isNotEmpty()) {
                    val normalized =
                        LlmMultimodalAudioHelper.toMono16Bit16kHzWav(appContext, clip)
                    if (normalized != null && normalized.isNotEmpty()) {
                        contents.add(Content.AudioBytes(normalized))
                    } else {
                        // 変換失敗時は従来どおり生バイトをフォールバックとして送る
                        Log.w(TAG, "Audio normalization failed; sending raw audio bytes")
                        contents.add(Content.AudioBytes(clip))
                    }
                }
            }
            if (prompt.trim().isNotEmpty()) {
                contents.add(Content.Text(prompt))
            }

            val extraContext =
                if (normalized.enableThinking) mapOf("enable_thinking" to "true") else emptyMap()

            val answerAccum = StringBuilder()

            conv.sendMessageAsync(
                Contents.of(contents),
                object : MessageCallback {
                    @Volatile
                    var completed = false

                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        if (completed) return
                        val thought = message.channels[THOUGHT_CHANNEL]
                        if (!thought.isNullOrEmpty()) {
                            trySend(InferenceStreamProtocol.encodeThinkChunk(thought)).isSuccess
                        }
                        val text = message.toString()
                        if (shouldEmitPartialText(text)) {
                            answerAccum.append(text)
                            trySend(text).isSuccess
                        }
                    }

                    override fun onDone() {
                        if (completed) return
                        completed = true
                        
                        Log.d(TAG, "Inference onDone: sending final message session=$sessionId")
                        val finalResult = InferenceStreamProtocol.encodeFinal(answerAccum.toString())
                        val sendResult = trySend(finalResult)
                        Log.d(TAG, "Inference final send result: ${sendResult.isSuccess} session=$sessionId")
                        if (!sendResult.isSuccess) {
                            Log.e(TAG, "Final trySend failed (channel closed/full); collector may miss FINAL")
                        }
                        // cancelProcess/Conversation.close がブロックすると close() が遅れ、
                        // Kotlin 側の collect が終わらず「応答中」のままになる。先に Flow を閉じる。
                        Log.d(TAG, "Inference completed session=$sessionId, closing flow")
                        close()
                        // Gallery: onDone では shutdownActiveLiteRtConversation を呼ばない。
                        // 正常終了時に cancelProcess は不要で、awaitClose で確実にクリアされるため重複呼び出しを避ける。
                        releaseInferenceMutex()
                    }

                    override fun onError(throwable: Throwable) {
                        if (completed) return
                        completed = true
                        
                        if (throwable is CancellationException) {
                            Log.i(TAG, "Inference cancelled session=$sessionId")
                            val finalResult = InferenceStreamProtocol.encodeFinal(answerAccum.toString())
                            trySend(finalResult)
                        } else {
                            Log.e(TAG, "Inference error session=$sessionId", throwable)
                        }
                        if (throwable is CancellationException) {
                            close()
                        } else {
                            close(if (throwable is Exception) throwable else RuntimeException(throwable))
                        }
                        // Gallery方式：エラー時は cancel のみ（Flow が既に閉じているため close は不要）
                        cancelActiveConversation()
                        releaseInferenceMutex()
                    }
                },
                extraContext
            )

            awaitClose {
                Log.d(TAG, "awaitClose: cleaning up session=$sessionId")
                // onDone/onError で既に処理済み。ここは Flow collector 離脱時のクリーンアップのみ。
                // cancel/close は呼ばない（Conversation を保持してセッション遷移まで再利用）
                releaseInferenceMutex()
            }
        } catch (t: Throwable) {
            // 例外時は cancel のみ（close は呼ばず）
            cancelActiveConversation()
            releaseInferenceMutex()
            if (t is CancellationException) {
                close(t)
                return@callbackFlow
            }
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Inference failed session=$sessionId", e)
            close(e)
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        return try {
            modelMutex.withLock {
                Log.d(TAG, "Unloading LiteRT-LM engine")
                // App 終了時は cancel + close 両方
                cancelActiveConversation()
                closeAndResetActiveConversation()
                runCatching { engine?.close() }
                engine = null
                loadedModelPath = null
                loadedConfig = null
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to unload", e)
            Result.failure(e)
        }
    }

    override suspend fun cancelInference() {
        Log.d(TAG, "Cancelling active inference (cancelProcess only, KV cache preserved)")
        cancelActiveConversation()
    }

    override suspend fun isAvailable(): Boolean = true

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun scaleBitmapForVision(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= MAX_BITMAP_EDGE && h <= MAX_BITMAP_EDGE) return bitmap
        val scale = minOf(MAX_BITMAP_EDGE.toFloat() / w, MAX_BITMAP_EDGE.toFloat() / h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM scaling bitmap", e)
            Bitmap.createScaledBitmap(
                bitmap,
                (w * scale * 0.75f).toInt().coerceAtLeast(1),
                (h * scale * 0.75f).toInt().coerceAtLeast(1),
                true
            )
        }
    }

    private fun resolveModelPath(modelName: String): String {
        val lowered = modelName.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && modelName.startsWith("/")) {
            return modelName
        }
        return when (ModelFileManager.resolveModelName(modelName)) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "gemma-3n-2b.task"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "gemma-3n-4b.task"
            ModelFileManager.LocalModel.GEMMA4_2B -> "gemma-4-2b.litertlm"
            ModelFileManager.LocalModel.GEMMA4_4B -> "gemma-4-4b.litertlm"
        }
    }

    private fun resolveLocalModelFile(modelName: String): File? {
        val resolved = resolveModelPath(modelName)
        val lowered = resolved.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && resolved.startsWith("/")) {
            val file = File(resolved)
            val validated = ModelFileManager.validateImportedTaskFile(file)
            if (validated.isFailure) {
                Log.w(TAG, "Imported model validation failed: ${validated.exceptionOrNull()?.message}")
                return null
            }
            return validated.getOrNull()
        }
        val localModel = ModelFileManager.resolveModelName(modelName)
        val verified = ModelFileManager.validatedModelFileForLoad(appContext, localModel)
        if (verified.isFailure) {
            Log.w(TAG, "Local model integrity check failed: ${verified.exceptionOrNull()?.message}")
            return null
        }
        return verified.getOrNull()
    }
}
