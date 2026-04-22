package com.nezumi_ai.data.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * メモリ使用状況をリアルタイムで監視し、段階的に対応するクラス。
 * - 85%: 警告、gc() 促進
 * - 90%: キャッシュ削減提案
 * - 95%+: 推論中断、エラー返却
 */
object MemoryObserver {
    private const val TAG = "MemoryObserver"
    
    // メモリ段階のしきい値（％）
    private const val MEMORY_LEVEL_WARNING = 85
    private const val MEMORY_LEVEL_CRITICAL = 90
    private const val MEMORY_LEVEL_SEVERE = 95
    
    // メモリ段階
    enum class MemoryLevel {
        NORMAL,      // 0-85%
        WARNING,     // 85-90%: gc() を促進
        CRITICAL,    // 90-95%: キャッシュ削減
        SEVERE       // 95%+: 推論中断
    }
    
    data class MemoryStatus(
        val level: MemoryLevel,
        val usedPercent: Int,
        val usedMB: Long,
        val maxMB: Long,
        val isLowMemory: Boolean
    )

    data class SystemMemoryInfo(
        val totalMemoryMB: Long,
        val availableMemoryMB: Long,
        val usedMemoryMB: Long,
        val usedPercent: Int,
        val lowMemoryFlag: Boolean
    )
    
    /**
     * 現在のメモリ状態を取得
     */
    suspend fun getMemoryStatus(context: Context): MemoryStatus {
        return withContext(Dispatchers.IO) {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            
            val usedPercent = if (maxMemory > 0) {
                ((usedMemory * 100) / maxMemory).toInt()
            } else {
                0
            }
            
            val level = when {
                usedPercent >= MEMORY_LEVEL_SEVERE -> MemoryLevel.SEVERE
                usedPercent >= MEMORY_LEVEL_CRITICAL -> MemoryLevel.CRITICAL
                usedPercent >= MEMORY_LEVEL_WARNING -> MemoryLevel.WARNING
                else -> MemoryLevel.NORMAL
            }
            
            val isLowMemory = isDeviceLowMemory(context)
            
            MemoryStatus(
                level = level,
                usedPercent = usedPercent,
                usedMB = usedMemory / (1024 * 1024),
                maxMB = maxMemory / (1024 * 1024),
                isLowMemory = isLowMemory
            )
        }
    }
    
    /**
     * スマホ本体のシステムメモリ情報を取得
     */
    fun getSystemMemoryInfo(context: Context): SystemMemoryInfo {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) {
                return SystemMemoryInfo(0, 0, 0, 0, false)
            }

            @Suppress("DEPRECATION")
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalMemoryMB = memInfo.totalMem / (1024 * 1024)
            val availableMemoryMB = memInfo.availMem / (1024 * 1024)
            val usedMemoryMB = totalMemoryMB - availableMemoryMB
            val usedPercent = if (totalMemoryMB > 0) {
                ((usedMemoryMB * 100) / totalMemoryMB).toInt()
            } else {
                0
            }

            SystemMemoryInfo(
                totalMemoryMB = totalMemoryMB,
                availableMemoryMB = availableMemoryMB,
                usedMemoryMB = usedMemoryMB,
                usedPercent = usedPercent,
                lowMemoryFlag = memInfo.lowMemory
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get system memory info", e)
            SystemMemoryInfo(0, 0, 0, 0, false)
        }
    }

    /**
     * システムがメモリ不足状態にあるかチェック
     */
    private fun isDeviceLowMemory(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) return false

            // MemoryInfo を使用して lowMemory フラグを取得
            @Suppress("DEPRECATION")
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.lowMemory
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check device low memory status", e)
            false
        }
    }
    
    /**
     * メモリ不足に対する段階的な対応を推奨
     * @return true: 推論続行可能 / false: 推論中止推奨
     */
    suspend fun requestMemoryCorrectionIfNeeded(context: Context): Boolean {
        val status = getMemoryStatus(context)
        
        return when (status.level) {
            MemoryLevel.NORMAL -> {
                Log.d(TAG, "Memory: ${status.usedPercent}% - OK")
                true
            }
            MemoryLevel.WARNING -> {
                Log.w(TAG, "Memory: ${status.usedPercent}% - WARNING. Suggesting gc()")
                triggerGarbageCollection()
                true
            }
            MemoryLevel.CRITICAL -> {
                Log.w(TAG, "Memory: ${status.usedPercent}% - CRITICAL. Cache reduction recommended")
                triggerGarbageCollection()
                // キャッシュ削減を要求（呼び出し側で対応）
                // CacheManager.cleanupCacheIfNeeded() を呼び出させる
                true
            }
            MemoryLevel.SEVERE -> {
                Log.e(TAG, "Memory: ${status.usedPercent}% - SEVERE. Inference should be aborted")
                false
            }
        }
    }
    
    /**
     * 異常なメモリ急増を検出（前回比との差分チェック）
     * @return true: メモリ足りている / false: 異常検出またはメモリ危機
     */
    private var lastCheckedMemoryMB: Long = 0
    suspend fun checkMemoryTrend(context: Context): Boolean {
        val status = getMemoryStatus(context)
        
        // 初回はチェックをスキップ
        if (lastCheckedMemoryMB == 0L) {
            lastCheckedMemoryMB = status.usedMB
            return true
        }
        
        val delta = status.usedMB - lastCheckedMemoryMB
        val trendPercent = if (lastCheckedMemoryMB > 0) {
            ((delta * 100) / lastCheckedMemoryMB).toInt()
        } else {
            0
        }
        
        lastCheckedMemoryMB = status.usedMB
        
        // 1 回の推論で 30% 以上のメモリ増加は異常
        if (delta > 100 && trendPercent > 30) {
            Log.w(TAG, "Abnormal memory increase detected: +${delta}MB (+$trendPercent%)")
            return status.level != MemoryLevel.SEVERE
        }
        
        return status.level != MemoryLevel.SEVERE
    }
    
    /**
     * 強制ガベージコレクション
     */
    private fun triggerGarbageCollection() {
        try {
            Log.d(TAG, "Triggering garbage collection")
            System.gc()
            System.runFinalization()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger GC", e)
        }
    }
    
    /**
     * デバイスのネイティブメモリ情報（デバッグ用）
     */
    suspend fun getDetailedMemoryInfo(context: Context): String {
        return withContext(Dispatchers.IO) {
            val runtime = Runtime.getRuntime()
            val total = runtime.totalMemory() / (1024 * 1024)
            val free = runtime.freeMemory() / (1024 * 1024)
            val max = runtime.maxMemory() / (1024 * 1024)
            val used = total - free
            
            """
            JVM Memory:
              Total: ${total}MB
              Used: ${used}MB
              Free: ${free}MB
              Max: ${max}MB
              Usage: ${if (max > 0) ((used * 100) / max) else 0}%
            """.trimIndent()
        }
    }
}
