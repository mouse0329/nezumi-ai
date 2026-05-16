package com.nezumi_ai.data.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BYTES_IN_GB = 1024f * 1024 * 1024

/**
 * メモリ使用状況をリアルタイムで監視し、段階的に対応するクラス。
 * - <70%: 正常（緑）
 * - 70-85%: 注意（黄）、gc() 促進
 * - 85%+: 危険（赤）、推論中断推奨
 *
 * また、モデルロード前に必要なデバイスメモリをチェック（Gallery アプローチ）
 */
object MemoryObserver {
    private const val TAG = "MemoryObserver"

    // モデルごとの最小メモリ要件（GB） - Gallery の allowlist から取得
    private val MODEL_MIN_MEMORY = mapOf(
        "GEMMA4-2B" to 8.0f,    // Gemma-4-E2B-it: 最小 8GB
        "GEMMA4-4B" to 12.0f,   // Gemma-4-E4B-it: 最小 12GB ★ Gallery と同じ
        "GEMMA3-2B" to 8.0f,    // Gemma-3n-E2B-it: 最小 8GB
        "GEMMA3-4B" to 12.0f,   // Gemma-3n-E4B-it: 最小 12GB
        "GEMMA3-1B" to 6.0f,    // Gemma3-1B-IT: 最小 6GB
    )
    
    // メモリ段階のしきい値（％）
    private const val MEMORY_LEVEL_WARNING = 70
    private const val MEMORY_LEVEL_SEVERE = 85
    
    // メモリ段階
    enum class MemoryLevel {
        NORMAL,      // 0-70%: 正常
        WARNING,     // 70-85%: 注意、gc() を促進
        SEVERE       // 85%+: 危険、推論中断推奨
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

            Log.d(TAG, "SYSTEM_MEMORY_INFO: totalMem=${memInfo.totalMem}B (${totalMemoryMB}MB) availMem=${memInfo.availMem}B (${availableMemoryMB}MB) usedMemory=${usedMemoryMB}MB usedPercent=${usedPercent}% lowMemory=${memInfo.lowMemory}")

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

    /**
     * デバイスメモリがモデルの最小要件を満たしているか判定（Gallery アプローチ）
     * @return true: メモリが不足している / false: メモリが十分
     */
    fun isMemoryLow(context: Context, modelName: String): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val minDeviceMemoryInGb = MODEL_MIN_MEMORY[modelName.uppercase()]

        return if (activityManager != null && minDeviceMemoryInGb != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            // API 34+ では advertisedMem を使用（Gallery と同じ）
            var deviceMemInGb = memInfo.totalMem / BYTES_IN_GB
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                deviceMemInGb = memInfo.advertisedMem / BYTES_IN_GB
            }

            Log.d(
                TAG,
                "isMemoryLow check: model=$modelName deviceMemGb=$deviceMemInGb minRequired=$minDeviceMemoryInGb"
            )

            deviceMemInGb < minDeviceMemoryInGb
        } else {
            Log.w(TAG, "isMemoryLow: Unable to determine - activityManager=$activityManager minMemory=$minDeviceMemoryInGb")
            false  // 判定不可の場合は進める
        }
    }

    /**
     * モデルファイルサイズから必要なメモリを推定してメモリ不足を検知
     * @param modelFileSizeBytes モデルファイルのサイズ（バイト）
     * @return true: メモリが不足している / false: メモリが十分
     */
    fun isMemoryLowForFileSize(context: Context, modelFileSizeBytes: Long): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false

        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        // デバイスの総メモリ（GB）
        var deviceMemInGb = memInfo.totalMem / BYTES_IN_GB
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            deviceMemInGb = memInfo.advertisedMem / BYTES_IN_GB
        }

        // モデルファイルサイズから必要メモリを推定
        // 経験則: モデルファイルサイズの約2.5倍のRAMが必要
        // （モデルロード + KVキャッシュ + 推論バッファ）
        val modelFileSizeGb = modelFileSizeBytes / BYTES_IN_GB
        val estimatedRequiredMemGb = modelFileSizeGb * 2.5f

        Log.d(
            TAG,
            "isMemoryLowForFileSize: modelFileSize=${modelFileSizeGb}GB estimatedRequired=${estimatedRequiredMemGb}GB deviceMem=${deviceMemInGb}GB"
        )

        // 推定必要メモリがデバイスメモリの80%を超える場合は警告
        return estimatedRequiredMemGb > (deviceMemInGb * 0.8f)
    }
}
