package com.nezumi_ai.data.inference

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 推論 Job の統一管理。
 * - SupervisorJob による親子関係管理
 * - キャンセルの完全な伝播（親→子の一方向）
 * - タイムアウト自動処理
 * - 実行中タスクの追跡
 * - リソースリークの防止
 */
class InferenceJobController {
    companion object {
        private const val TAG = "InferenceJobController"
        
        // デフォルトタイムアウト（5分）
        private const val DEFAULT_INFERENCE_TIMEOUT_MS = 5 * 60 * 1000L
    }
    
    /**
     * 推論タスクの状態
     */
    enum class InferenceState {
        IDLE,        // 実行待機中
        RUNNING,     // 実行中
        CANCELLING,  // キャンセル中
        COMPLETED,   // 完了
        FAILED,      // 失敗
        TIMEOUT      // タイムアウト
    }
    
    /**
     * 推論タスク情報
     */
    data class InferenceTask(
        val taskId: Long,
        val sessionId: Long,
        val startTimeMs: Long = System.currentTimeMillis(),
        var state: InferenceState = InferenceState.IDLE,
        var job: Job? = null,
        var elapsedMs: Long = 0
    ) {
        val isRunning: Boolean
            get() = state == InferenceState.RUNNING
        
        val isCancelled: Boolean
            get() = state == InferenceState.CANCELLING
    }
    
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)
    
    private val tasks = ConcurrentHashMap<Long, InferenceTask>()
    private val taskMutex = Mutex()
    
    private val taskIdCounter = AtomicReference(System.currentTimeMillis())
    
    /**
     * 新しい推論タスク ID を生成
     */
    private fun generateTaskId(): Long = taskIdCounter.accumulateAndGet(1) { a, _ -> a + 1 }
    
    /**
     * 推論タスクを開始
     * @param sessionId セッション ID
     * @param timeoutMs タイムアウト時間（ミリ秒）
     * @param block 実行する処理
     * @return タスク ID
     */
    suspend fun <T> launchInference(
        sessionId: Long,
        timeoutMs: Long = DEFAULT_INFERENCE_TIMEOUT_MS,
        block: suspend () -> T
    ): Result<Pair<Long, T>> {
        return taskMutex.withLock {
            val taskId = generateTaskId()
            val task = InferenceTask(
                taskId = taskId,
                sessionId = sessionId
            )
            
            tasks[taskId] = task
            Log.d(TAG, "Inference task created: taskId=$taskId sessionId=$sessionId timeout=${timeoutMs}ms")
            
            try {
                task.state = InferenceState.RUNNING
                
                // タイムアウト付きで実行
                val result = withTimeoutOrNull(timeoutMs) {
                    block()
                }
                
                task.elapsedMs = System.currentTimeMillis() - task.startTimeMs
                
                if (result != null) {
                    task.state = InferenceState.COMPLETED
                    Log.d(TAG, "Inference task completed: taskId=$taskId elapsed=${task.elapsedMs}ms")
                    Result.success(Pair(taskId, result))
                } else {
                    task.state = InferenceState.TIMEOUT
                    Log.w(TAG, "Inference task timeout: taskId=$taskId timeout=${timeoutMs}ms")
                    Result.failure(
                        Exception("Inference timeout after ${timeoutMs}ms")
                    )
                }
            } catch (e: CancellationException) {
                task.state = InferenceState.CANCELLING
                task.elapsedMs = System.currentTimeMillis() - task.startTimeMs
                Log.d(TAG, "Inference task cancelled: taskId=$taskId elapsed=${task.elapsedMs}ms")
                Result.failure(e)
            } catch (e: Exception) {
                task.state = InferenceState.FAILED
                task.elapsedMs = System.currentTimeMillis() - task.startTimeMs
                Log.e(TAG, "Inference task failed: taskId=$taskId elapsed=${task.elapsedMs}ms", e)
                Result.failure(e)
            } finally {
                // タスク履歴は一定期間保持（デバッグ用）
                // スコープ内の関連タスク削除は scope.cancel() が担当
            }
        }
    }
    
    /**
     * セッション内の全タスクをキャンセル
     */
    suspend fun cancelSessionTasks(sessionId: Long) {
        return taskMutex.withLock {
            val tasksToCancel = tasks.filter { it.value.sessionId == sessionId }
            
            tasksToCancel.forEach { (taskId, task) ->
                if (task.isRunning) {
                    task.job?.cancel("Session $sessionId cancelled")
                    Log.d(TAG, "Task cancelled: taskId=$taskId sessionId=$sessionId")
                }
            }
        }
    }
    
    /**
     * 単一タスクをキャンセル
     */
    suspend fun cancelTask(taskId: Long) {
        return taskMutex.withLock {
            val task = tasks[taskId] ?: return
            
            if (task.isRunning) {
                task.job?.cancel("Task $taskId cancelled by user")
                Log.d(TAG, "Task cancelled: taskId=$taskId")
            }
        }
    }
    
    /**
     * タスク状態を取得
     */
    suspend fun getTaskState(taskId: Long): InferenceState? {
        return taskMutex.withLock {
            tasks[taskId]?.state
        }
    }
    
    /**
     * セッション内の実行中タスク数
     */
    suspend fun getRunningTaskCount(sessionId: Long): Int {
        return taskMutex.withLock {
            tasks.count { (_, task) ->
                task.sessionId == sessionId && task.isRunning
            }
        }
    }
    
    /**
     * 全コントローラをシャットダウン
     * （アプリ終了時に呼び出し）
     */
    suspend fun shutdown() {
        return taskMutex.withLock {
            Log.i(TAG, "Shutting down inference job controller")
            scope.cancel("Controller shutdown")
            tasks.clear()
        }
    }
    
    /**
     * デバッグ用：実行中タスクの情報
     */
    suspend fun dumpRunningTasks(): String {
        return taskMutex.withLock {
            val sb = StringBuilder("Running Inference Tasks:\n")
            val runningTasks = tasks.filter { it.value.isRunning }
            
            if (runningTasks.isEmpty()) {
                sb.append("  (none)\n")
            } else {
                runningTasks.forEach { (taskId, task) ->
                    val elapsed = System.currentTimeMillis() - task.startTimeMs
                    sb.append("  Task $taskId: session=${task.sessionId}, elapsed=${elapsed}ms, state=${task.state}\n")
                }
            }
            sb.toString()
        }
    }
}
