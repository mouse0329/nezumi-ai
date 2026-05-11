package com.nezumi_ai.data.inference

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * GPU/NPU バックエンド別のリソース管理。
 * - バックエンド初期化前の準備（古いリソースの完全削除）
 * - バックエンド切り替え時のメモリリーク防止
 * - GPU/NPU メモリ状態の追跡
 * - フォールバック時のクリーンアップ
 */
class BackendResourceManager {
    companion object {
        private const val TAG = "BackendResourceManager"
        
        enum class BackendType {
            CPU,
            GPU,
            NPU,
            UNKNOWN
        }
        
        fun backendTypeOf(backend: Backend?): BackendType {
            return when {
                backend is Backend.CPU -> BackendType.CPU
                backend is Backend.GPU -> BackendType.GPU
                backend is Backend.NPU -> BackendType.NPU
                else -> BackendType.UNKNOWN
            }
        }
    }
    
    /**
     * バックエンドリソース
     */
    private data class BackendResource(
        val type: BackendType,
        val engine: Engine? = null,
        val createdTimeMs: Long = System.currentTimeMillis()
    ) {
        val ageMs: Long
            get() = System.currentTimeMillis() - createdTimeMs
    }
    
    private val resourceMutex = Mutex()
    private val currentResource = AtomicReference<BackendResource?>(null)
    private val previousResources = mutableListOf<BackendResource>()
    
    // バックエンド メモリ使用量（デバッグ用）
    private val backendMemoryTracker = mutableMapOf<BackendType, Long>()
    
    /**
     * バックエンド初期化前の準備（古いリソース削除）
     * @param targetBackend 切り替え先のバックエンド
     */
    suspend fun prepareBackendSwitch(targetBackend: Backend) {
        return resourceMutex.withLock {
            val targetType = backendTypeOf(targetBackend)
            val current = currentResource.get()
            
            if (current?.type == targetType) {
                Log.d(TAG, "Backend already in use: $targetType")
                return
            }
            
            Log.i(TAG, "Preparing backend switch: ${current?.type} -> $targetType")
            
            // 古いリソースをクリーンアップ
            cleanupPreviousResources()
            
            // 古いバックエンドを保存（ロールバック用）
            current?.let { previousResources.add(it) }
        }
    }
    
    /**
     * バックエンド初期化後にリソースを登録
     */
    suspend fun registerBackendEngine(engine: Engine, backend: Backend) {
        return resourceMutex.withLock {
            val backendType = backendTypeOf(backend)
            
            // 前のリソースを保存
            currentResource.get()?.let { previousResources.add(it) }
            
            // 新しいリソースを設定
            currentResource.set(BackendResource(backendType, engine))
            
            Log.d(TAG, "Backend engine registered: $backendType")
        }
    }
    
    /**
     * バックエンド初期化失敗時のフォールバック処理
     * @param failedBackend 失敗したバックエンド
     * @return ロールバック成功したか
     */
    suspend fun rollbackBackend(failedBackend: Backend): Boolean {
        return resourceMutex.withLock {
            val failedType = backendTypeOf(failedBackend)
            Log.w(TAG, "Rolling back backend: $failedType")
            
            // 失敗したバックエンドをクリーンアップ
            currentResource.get()?.let {
                if (it.type == failedType) {
                    cleanupResource(it)
                }
            }
            
            // 前のリソースがあれば復帰
            if (previousResources.isNotEmpty()) {
                val previous = previousResources.removeAt(previousResources.size - 1)
                currentResource.set(previous)
                Log.i(TAG, "Rolled back to previous backend: ${previous.type}")
                return true
            }
            
            return false
        }
    }
    
    /**
     * バックエンド メモリ状態の更新（デバッグ用）
     */
    suspend fun updateBackendMemoryUsage(backendType: BackendType, usageMB: Long) {
        return resourceMutex.withLock {
            backendMemoryTracker[backendType] = usageMB
            Log.d(TAG, "Backend memory: $backendType = ${usageMB}MB")
        }
    }
    
    /**
     * バックエンド メモリ使用量を取得
     */
    suspend fun getBackendMemoryUsage(backendType: BackendType): Long? {
        return resourceMutex.withLock {
            backendMemoryTracker[backendType]
        }
    }
    
    /**
     * 現在のバックエンド情報
     */
    suspend fun getCurrentBackendInfo(): String {
        return resourceMutex.withLock {
            val current = currentResource.get()
            if (current == null) {
                "No backend loaded"
            } else {
                "Type: ${current.type}, Age: ${current.ageMs}ms"
            }
        }
    }
    
    /**
     * 全リソースをクリーンアップ（アプリ終了時）
     */
    suspend fun cleanupAll() {
        return resourceMutex.withLock {
            Log.i(TAG, "Cleaning up all backend resources")
            
            currentResource.get()?.let { cleanupResource(it) }
            currentResource.set(null)
            
            cleanupPreviousResources()
            backendMemoryTracker.clear()
        }
    }
    
    /**
     * 前のバックエンド リソースをクリーンアップ
     */
    private suspend fun cleanupPreviousResources() {
        previousResources.forEach { resource ->
            cleanupResource(resource)
        }
        previousResources.clear()
    }
    
    /**
     * 単一のバックエンド リソースをクリーンアップ
     */
    private suspend fun cleanupResource(resource: BackendResource) {
        withContext(Dispatchers.Default) {
            try {
                resource.engine?.close()
                Log.d(TAG, "Backend resource cleaned up: ${resource.type}")
            } catch (e: Exception) {
                Log.w(TAG, "Error during backend resource cleanup", e)
            }
        }
    }
}

/**
 * GPU/NPU 専用のメモリ管理ユーティリティ
 */
object GpuMemoryManager {
    private const val TAG = "GpuMemoryManager"
    
    // GPU メモリ上限（デバイス固有。通常 2-8GB）
    // 実機で測定・キャリブレーション
    private const val GPU_MEMORY_LIMIT_MB = 2048  // 2GB（保守的な値）
    
    // NPU メモリ上限（デバイス固有。通常 512MB-2GB）
    private const val NPU_MEMORY_LIMIT_MB = 512    // 512MB
    
    /**
     * バックエンド別の利用可能メモリをチェック
     * @return true: メモリ十分 / false: メモリ不足
     */
    fun hasEnoughGpuMemory(): Boolean {
        // 実装：デバイスの GPU メモリ使用率をチェック
        // （API レベルに応じて異なるアプローチが必要）
        
        // フォールバック：JVM メモリ状態でチェック
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        val usagePercent = if (max > 0) ((used * 100) / max) else 0
        
        val isOk = usagePercent < 80
        if (!isOk) {
            Log.w(TAG, "GPU memory insufficient: ${usagePercent}%")
        }
        return isOk
    }
    
    fun hasEnoughNpuMemory(): Boolean {
        // NPU メモリも同様にチェック
        return hasEnoughGpuMemory()
    }
    
    /**
     * バックエンド推奨を取得（メモリ状態に基づいて）
     */
    fun recommendBackend(): String {
        val cpuOk = true  // CPU は常に OK
        val gpuOk = hasEnoughGpuMemory()
        val npuOk = hasEnoughNpuMemory()
        
        return when {
            npuOk -> "NPU"
            gpuOk -> "GPU"
            cpuOk -> "CPU"
            else -> "CPU"  // フォールバック
        }
    }
}
