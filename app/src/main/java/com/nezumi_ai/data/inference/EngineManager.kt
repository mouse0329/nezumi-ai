package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.nezumi_ai.sd.LocalDreamModule
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
    private var active: ActiveEngine = ActiveEngine.NONE
    private var localDream: LocalDreamModule? = null
    private var sdModelPath: String? = null

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
        try {
            localDream?.stopServer()
            localDream?.cleanup()
            localDream = null
            sdModelPath = null
            active = ActiveEngine.NONE
            Log.i(TAG, "SD released and cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during SD release", e)
            localDream = null
            sdModelPath = null
            active = ActiveEngine.NONE
        }
    }

    suspend fun markLlmActive() = mutex.withLock {
        try {
            localDream?.stopServer()
            localDream?.cleanup()
            localDream = null
            sdModelPath = null
            active = ActiveEngine.LLM
            Log.i(TAG, "Marked LLM active, SD resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error during markLlmActive", e)
            localDream = null
            sdModelPath = null
            active = ActiveEngine.LLM
        }
    }

    suspend fun releaseAll() = mutex.withLock {
        try {
            localDream?.stopServer()
            localDream?.cleanup()
            localDream = null
            sdModelPath = null
            active = ActiveEngine.NONE
            Log.i(TAG, "All engines released")
        } catch (e: Exception) {
            Log.e(TAG, "Error during releaseAll", e)
            localDream = null
            sdModelPath = null
            active = ActiveEngine.NONE
        }
    }

    /** ミューテックス不要: 生成中のHTTP接続を即座に切断してSSEループを抜ける */
    fun cancelCurrentGeneration() {
        localDream?.cancelGeneration()
    }
}
