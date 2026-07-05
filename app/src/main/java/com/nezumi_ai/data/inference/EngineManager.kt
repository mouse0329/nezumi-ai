package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.nezumi_ai.sd.LocalDreamModule
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LLM（LiteRT / GGUF）と SD の同時利用を避けるための直列化。
 * LocalDreamModule（MNN/QNN）に一本化。
 */
object EngineManager {
    private const val TAG = "EngineManager"

    enum class ActiveEngine {
        NONE,
        LLM,
        SD
    }

    private val mutex = Mutex()
    private val cancelMutex = Mutex()
    private var active: ActiveEngine = ActiveEngine.NONE
    private var localDream: LocalDreamModule? = null
    private var sdModelPath: String? = null
    // ★ Bug fix: 以前はキャッシュ判定に backend を含めていなかったため、
    //   一度 GPU で起動した後にユーザーが CPU を選んでも、同じモデルパスならそのまま
    //   GPU インスタンスが使い回されてしまう「CPU/GPU 切り替えが GPU 常時」バグがあった。
    //   読み込み済み backend を保持し、一致しない場合はロードし直す。
    private var sdBackend: String? = null

    suspend fun acquireLocalDream(context: Context, modelPath: String, backend: String = "auto"): LocalDreamModule = mutex.withLock {
        // 前回のキャンセル処理が完了するまで待機するが、このメソッド自体が mutex.withLock 内にあるため
        // cancelCurrentGeneration が cancelMutex を取得している間にここが呼ばれると
        // cancelMutex.withLock で待機する。
        cancelMutex.withLock {
            // ★ Bug fix: backend が一致している場合のみ再利用する。
            //   normalize して "auto" / "cpu" / "gpu" の表記揺れを吸収。
            val requestedBackend = backend.trim().lowercase().ifBlank { "auto" }
            val cachedBackend = sdBackend?.trim()?.lowercase()
            if (active == ActiveEngine.SD &&
                localDream != null &&
                sdModelPath == modelPath &&
                cachedBackend == requestedBackend &&
                localDream?.isServerReady == true
            ) {
                return localDream!!
            }
            if (cachedBackend != null && cachedBackend != requestedBackend) {
                Log.i(TAG, "LocalDream backend changed: $cachedBackend -> $requestedBackend. Restarting server.")
            }
            localDream?.stopServer()
            localDream?.cleanup()
            localDream = null
            sdModelPath = null
            sdBackend = null
            val ld = LocalDreamModule(context)
            val loaded = ld.loadModel(modelPath, backend)
            if (!loaded) {
                throw IllegalStateException("画像生成モデルの読み込みに失敗しました: backend=$backend, path=$modelPath")
            }
            localDream = ld
            sdModelPath = modelPath
            sdBackend = requestedBackend
            active = ActiveEngine.SD
            Log.i(TAG, "LocalDream acquired path=$modelPath backend=$backend (normalized=$requestedBackend)")
            ld
        }
    }

    suspend fun releaseSdKeepNone() = mutex.withLock {
        try {
            localDream?.stopServer()
            localDream?.cleanup()
            localDream = null
            sdModelPath = null
            sdBackend = null
            active = ActiveEngine.NONE
            Log.i(TAG, "SD released and cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during SD release", e)
            localDream = null
            sdModelPath = null
            sdBackend = null
            active = ActiveEngine.NONE
        }
    }

    suspend fun markLlmActive() = mutex.withLock {
        try {
            localDream?.stopServer()
            localDream?.cleanup()
            localDream = null
            sdModelPath = null
            sdBackend = null
            active = ActiveEngine.LLM
            Log.i(TAG, "Marked LLM active, SD resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error during markLlmActive", e)
            localDream = null
            sdModelPath = null
            sdBackend = null
            active = ActiveEngine.LLM
        }
    }

    suspend fun releaseAll() = mutex.withLock {
        try {
            localDream?.stopServer()
            localDream?.cleanup()
            localDream = null
            sdModelPath = null
            sdBackend = null
            active = ActiveEngine.NONE
            Log.i(TAG, "All engines released")
        } catch (e: Exception) {
            Log.e(TAG, "Error during releaseAll", e)
            localDream = null
            sdModelPath = null
            sdBackend = null
            active = ActiveEngine.NONE
        }
    }

    /**
     * 生成中のHTTP接続を即座に切断してSSEループを抜ける。
     *
     * Perf fix / クラッシュ対策:
     *   旧実装は cancel のたびに LocalDream プロセスを完全に停止していたため、
     *   中断→再生成のたびにモデルをロードし直し (CPU で UNET/CLIP/VAE の
     *   .mnn を mmap し直す) 数十秒待たされる上、 SIGKILL した直後の
     *   再入で OpenCL context 初期化が失敗してクラッシュするケースがあった。
     *
     *   local-dream の BackendService と同じ思想で、cancel は HTTP 切断だけ
     *   を行い、サーバープロセスはなるべく生かしておいて次の generate() で
     *   再利用する。バックエンドを別の backend/model に切り替える際だけ
     *   acquireLocalDream() の mutex の中で stopServer() が呼ばれる。
     */
    fun cancelCurrentGeneration(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                cancelMutex.withLock {
                    Log.i(TAG, "Cancelling current generation (HTTP disconnect only, keeping backend warm)")
                    localDream?.cancelGeneration()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cancellation", e)
            }
        }
    }
}
