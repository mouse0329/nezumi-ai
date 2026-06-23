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

    suspend fun acquireLocalDream(context: Context, modelPath: String, backend: String = "auto"): LocalDreamModule = mutex.withLock {
        // 前回のキャンセル処理が完了するまで待機するが、このメソッド自体が mutex.withLock 内にあるため
        // cancelCurrentGeneration が cancelMutex を取得している間にここが呼ばれると
        // cancelMutex.withLock で待機する。
        cancelMutex.withLock {
            if (active == ActiveEngine.SD && localDream != null && sdModelPath == modelPath && localDream?.isServerReady == true) {
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

    /**
     * 生成中のHTTP接続を即座に切断してSSEループを抜ける。
     * コルーチンで非同期に実行され、クリーンアップ完了までロックする。
     */
    fun cancelCurrentGeneration(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // mutex.withLock は使用せず、即座に HTTP 切断を試みる
            localDream?.cancelGeneration()
            
            // 重いクリーンアップ処理（サーバー停止など）を排他的に行う
            try {
                cancelMutex.withLock {
                    Log.i(TAG, "Starting cancellation and resource cleanup...")
                    // サーバー停止。内部で waitFor(5s) を持っているが、
                    // ここがロックされている間は acquireLocalDream が待機する。
                    localDream?.stopServer()
                    Log.i(TAG, "Cancellation and resource cleanup completed.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cancellation lock", e)
            }
        }
    }
}
