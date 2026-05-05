package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.nezumi_ai.sd.LocalDreamModule
import com.nezumi_ai.sd.SdEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LLM（LiteRT / GGUF）と SD ネイティブの同時利用を避けるための直列化。
 * 実際のアンロード／再ロードは呼び出し側（ChatViewModel 等）で行う。
 */
object EngineManager {
    private const val TAG = "EngineManager"
    private const val USE_LOCAL_DREAM = true // MNN/QNN使用フラグ

    enum class ActiveEngine {
        NONE,
        LLM,
        SD
    }

    private val mutex = Mutex()
    private var active: ActiveEngine = ActiveEngine.NONE
    private var sdEngine: SdEngine? = null
    private var localDream: LocalDreamModule? = null
    private var sdModelPath: String? = null

    suspend fun acquireSd(modelPath: String, threads: Int = 4): SdEngine = mutex.withLock {
        if (active == ActiveEngine.SD && sdEngine != null && sdModelPath == modelPath) {
            return sdEngine!!
        }
        sdEngine?.release()
        val eng = SdEngine(modelPath)
        eng.load(threads)
        sdEngine = eng
        sdModelPath = modelPath
        active = ActiveEngine.SD
        Log.i(TAG, "SD acquired path=$modelPath")
        eng
    }
    
    suspend fun acquireLocalDream(context: Context, modelPath: String, backend: String = "auto"): LocalDreamModule = mutex.withLock {
        if (active == ActiveEngine.SD && localDream != null && sdModelPath == modelPath) {
            return localDream!!
        }
        localDream?.stopServer()
        val ld = LocalDreamModule(context)
        val loaded = ld.loadModel(modelPath, backend)
        if (!loaded) {
            throw IllegalStateException("Failed to load LocalDream model: $modelPath")
        }
        localDream = ld
        sdModelPath = modelPath
        active = ActiveEngine.SD
        Log.i(TAG, "LocalDream acquired path=$modelPath backend=$backend")
        ld
    }

    suspend fun releaseSdKeepNone() = mutex.withLock {
        sdEngine?.release()
        sdEngine = null
        localDream?.stopServer()
        localDream = null
        sdModelPath = null
        active = ActiveEngine.NONE
        Log.i(TAG, "SD released")
    }

    suspend fun markLlmActive() = mutex.withLock {
        sdEngine?.release()
        sdEngine = null
        localDream?.stopServer()
        localDream = null
        sdModelPath = null
        active = ActiveEngine.LLM
    }

    suspend fun releaseAll() = mutex.withLock {
        sdEngine?.release()
        sdEngine = null
        localDream?.stopServer()
        localDream?.cleanup()
        localDream = null
        sdModelPath = null
        active = ActiveEngine.NONE
    }
    
    fun isUsingLocalDream(): Boolean = USE_LOCAL_DREAM
}
