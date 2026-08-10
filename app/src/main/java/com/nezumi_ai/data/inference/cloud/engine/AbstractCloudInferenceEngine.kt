package com.nezumi_ai.data.inference.cloud.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * クラウド API を叩く [AIInferenceEngine] 実装の共通基底。
 *
 * ## 責務
 * - 直列実行のロック管理 ([inferenceMutex])
 * - 進行中の [Call] を保持し、[cancelInference] / awaitClose でキャンセルする
 * - loadModel/unloadModel の軽量な既定実装 (現在のモデル名を覚えるだけ)
 * - [inferenceWithMedia] の骨格 (callbackFlow + FINAL 発行 + 例外整形)
 *
 * サブクラスは [runStreamingInference] で実際の HTTP + ストリーム解析を行う。
 * ストリーム中はデルタを [ProducerScope.trySend] でそのまま送るだけで、
 * FINAL チャンクの発行は本クラスが担当する。
 *
 * ## 「モデルロード」の意味
 * クラウド系ではオンデバイス推論のようなロード実体は存在しない。
 * ここでは「そのモデル名を今後の inference で使う」という宣言的な扱いに留め、
 * 実際の HTTP はリクエスト時にのみ発生する。
 */
abstract class AbstractCloudInferenceEngine(
    protected val appContext: Context,
    val provider: CloudApiKeyStore.Provider
) : AIInferenceEngine {

    protected val TAG: String = "Cloud/${provider.id}"

    protected val http: OkHttpClient = CloudHttpClient.instance

    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    /** 現在バインドしているモデル名 (`gemini-2.5-flash` などの生の modelName)。 */
    @Volatile
    protected var currentModelName: String? = null

    /** 現在進行中の [Call]。cancelInference / awaitClose から中断する。 */
    private val inflight = AtomicReference<Call?>(null)

    // ─── AIInferenceEngine ────────────────────────────────────────

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return loadMutex.withLock {
            val cleaned = modelName.trim()
            if (cleaned.isBlank()) {
                Result.failure(IllegalArgumentException("model name is blank"))
            } else if (!CloudApiKeyStore.isConfigured(appContext, provider)) {
                Result.failure(
                    IllegalStateException(
                        "Cloud provider '${provider.id}' is not configured. " +
                            "Please set the API key / base URL in the model settings."
                    )
                )
            } else {
                currentModelName = cleaned
                Log.d(TAG, "loadModel bound modelName=$cleaned")
                Result.success(Unit)
            }
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        return loadMutex.withLock {
            currentModelName = null
            cancelInflight()
            Result.success(Unit)
        }
    }

    override suspend fun cancelInference() {
        cancelInflight()
    }

    override suspend fun isAvailable(): Boolean {
        return currentModelName != null && CloudApiKeyStore.isConfigured(appContext, provider)
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
    ): Flow<String> = callbackFlow<String> {
        inferenceMutex.lock()
        val model = currentModelName
        if (model == null) {
            inferenceMutex.unlock()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }
        if (!CloudApiKeyStore.isConfigured(appContext, provider)) {
            inferenceMutex.unlock()
            close(
                IllegalStateException(
                    "Cloud provider '${provider.id}' is not configured. " +
                        "Please open Settings → Cloud models and set the API key / base URL."
                )
            )
            return@callbackFlow
        }

        val fullAnswer = StringBuilder()
        var closed = false
        try {
            Log.d(
                TAG,
                "inference start session=$sessionId model=$model promptLen=${prompt.length} images=${images.size}"
            )
            runStreamingInference(
                session = this,
                sessionId = sessionId,
                model = model,
                prompt = prompt,
                images = images,
                config = config.normalized(),
                onDelta = { delta ->
                    if (delta.isNotEmpty()) {
                        fullAnswer.append(delta)
                        trySend(delta)
                    }
                }
            )
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close()
            closed = true
        } catch (c: CancellationException) {
            Log.d(TAG, "inference cancelled session=$sessionId")
            // ChatViewModel は FINAL 到達を「完了扱い」する。キャンセル時は
            // 途中までのテキストを FINAL として送出しておく。
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close()
            closed = true
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed session=$sessionId", t)
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close(if (t is Exception) t else RuntimeException(t))
            closed = true
        } finally {
            if (!closed) {
                // 例外・close ルートを一つも通らなかった場合の保険
                runCatching { close() }
            }
            cancelInflight()
            if (inferenceMutex.isLocked) {
                runCatching { inferenceMutex.unlock() }
            }
        }

        awaitClose {
            Log.d(TAG, "awaitClose session=$sessionId")
            cancelInflight()
        }
    }.flowOn(Dispatchers.IO)

    // ─── サブクラスに実装させる箇所 ─────────────────────────────

    /**
     * 実際の HTTP リクエストとストリーム解析を行う。
     *
     * サブクラスは Provider ごとの
     *   - リクエスト JSON 組み立て
     *   - Authorization ヘッダ設定
     *   - SSE / NDJSON パース
     * を実装し、テキストデルタを [onDelta] へ渡す。
     *
     * 進行中の [Call] は [registerCall] で登録すること。
     * 登録した Call は本基底が [cancelInflight] でキャンセルする。
     *
     * 例外を投げた場合は inferenceWithMedia 側で捕捉され、
     * FINAL 発行後に上流へ再スローされる。
     */
    protected abstract suspend fun runStreamingInference(
        session: ProducerScope<String>,
        sessionId: Long,
        model: String,
        prompt: String,
        images: List<Bitmap>,
        config: InferenceConfig,
        onDelta: (String) -> Unit
    )

    /** サブクラスから、開始した Call を「進行中」として本基底に預ける。 */
    protected fun registerCall(call: Call) {
        val previous = inflight.getAndSet(call)
        previous?.cancel()
    }

    private fun cancelInflight() {
        inflight.getAndSet(null)?.let { call ->
            runCatching { call.cancel() }
                .onFailure { Log.w(TAG, "cancel call failed", it) }
        }
    }
}
