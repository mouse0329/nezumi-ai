package com.nezumi_ai.data.inference

import android.app.ActivityManager
import android.content.Context
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
        const val DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 45
        const val MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 0
        const val MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 100
    
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
     * Get current memory status.
     * #6 fix: use ActivityManager.MemoryInfo.availMem (system-wide free memory) instead of JVM heap.
     * LLM models load into native memory; JVM heap usage does not reflect actual memory pressure.
     * Uses absolute memory thresholds instead of percentages for more reliable detection.
     */
    suspend fun getMemoryStatus(context: Context): MemoryStatus {
        return withContext(Dispatchers.IO) {
            val sysInfo = getSystemMemoryInfo(context)

            // 絶対値とlowMemoryフラグで判定
            val level = when {
                sysInfo.lowMemoryFlag || sysInfo.availableMemoryMB < 300 -> MemoryLevel.SEVERE
                sysInfo.availableMemoryMB < 800 -> MemoryLevel.WARNING
                else -> MemoryLevel.NORMAL
            }

            MemoryStatus(
                level = level,
                usedPercent = sysInfo.usedPercent,
                usedMB = sysInfo.usedMemoryMB,
                maxMB = sysInfo.totalMemoryMB,
                isLowMemory = sysInfo.lowMemoryFlag
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
        val sysInfo = getSystemMemoryInfo(context)

        if (sysInfo.lowMemoryFlag) {
            Log.e(TAG, "System lowMemory flag is set - aborting")
            return false
        }

        if (sysInfo.availableMemoryMB < 300) {
            Log.e(TAG, "Available memory critically low: ${sysInfo.availableMemoryMB}MB")
            return false
        }

        if (sysInfo.usedPercent >= MEMORY_LEVEL_WARNING) {
            Log.w(TAG, "Memory: ${sysInfo.usedPercent}% - WARNING. Suggesting gc()")
            triggerGarbageCollection()
        }

        Log.d(TAG, "Memory OK: avail=${sysInfo.availableMemoryMB}MB lowMemory=${sysInfo.lowMemoryFlag}")
        return true
    }
    
    /**
     * 異常なメモリ急増を検出（前回比との差分チェック）
     * @return true: メモリ足りている / false: 異常検出またはメモリ危機
     */
    // #35 fix: use AtomicLong to prevent concurrent access from multiple coroutines
    private val lastCheckedMemoryMB = java.util.concurrent.atomic.AtomicLong(0L)

    suspend fun checkMemoryTrend(context: Context): Boolean {
        val status = getMemoryStatus(context)

        val prev = lastCheckedMemoryMB.get()
        if (prev == 0L) {
            lastCheckedMemoryMB.set(status.usedMB)
            return true
        }

        val delta = status.usedMB - prev
        val trendPercent = if (prev > 0) ((delta * 100) / prev).toInt() else 0

        lastCheckedMemoryMB.set(status.usedMB)

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
     * アンロード後に確保できる空きメモリがモデルの最小要件を満たすか判定。
     * 現在の空きメモリ（availMem）を基準にするため、アンロード直後に呼ぶこと。
     * @return true: メモリが不足している / false: メモリが十分
     */
    fun isMemoryLow(context: Context, modelName: String): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val minRequiredGb = MODEL_MIN_MEMORY[modelName.uppercase()]

        return if (activityManager != null && minRequiredGb != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            // 現在の空きメモリ（アンロード後に利用可能な量）
            val availableGb = memInfo.availMem / BYTES_IN_GB

            Log.d(
                TAG,
                "isMemoryLow check: model=$modelName availableGb=$availableGb minRequired=$minRequiredGb"
            )

            availableGb < minRequiredGb
        } else {
            Log.w(TAG, "isMemoryLow: Unable to determine - activityManager=$activityManager minMemory=$minRequiredGb")
            false  // 判定不可の場合は進める
        }
    }

    /**
     * モデルファイルサイズから必要なメモリを推定してメモリ不足を検知
     * デフォルトでは利用可能な空きメモリを基準に判定します。
     * @param modelFileSizeBytes モデルファイルのサイズ（バイト）
     * @param thresholdPercent モデルサイズが利用可能な空きメモリの何%を超えると警告するか
     * @param useAvailable true: 空きメモリを基準に判定 / false: 総メモリを基準に判定
     * @return true: メモリが不足している / false: メモリが十分
     */
    fun isMemoryLowForFileSize(context: Context, modelFileSizeBytes: Long, thresholdPercent: Int = DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT, useAvailable: Boolean = true): Boolean {
        if (thresholdPercent <= 0) {
            return false
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false

        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val availableGb = memInfo.availMem / BYTES_IN_GB
        val totalGb = memInfo.totalMem / BYTES_IN_GB
        val modelFileSizeGb = modelFileSizeBytes / BYTES_IN_GB

        val isLow = if (useAvailable) {
            val requiredAvailableGb = modelFileSizeGb * (thresholdPercent / 100f)
            requiredAvailableGb > availableGb
        } else {
            val allowedModelSizeGb = totalGb * (thresholdPercent / 100f)
            modelFileSizeGb > allowedModelSizeGb
        }

        Log.d(
            TAG,
            "isMemoryLowForFileSize: modelFileSize=${modelFileSizeGb}GB threshold=${thresholdPercent}% " +
                "availableMem=${availableGb}GB totalMem=${totalGb}GB useAvailable=$useAvailable isLow=$isLow"
        )

        return isLow
    }
}
