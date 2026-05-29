package com.nezumi_ai.data.inference

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
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
    
    const val DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 45
    const val MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 0
    const val MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 100
    
    // メモリ段階
    enum class MemoryLevel {
        NORMAL,      // 通常状態
        WARNING,     // 用量が高く GC を促進するべき状態
        SEVERE       // 低メモリ状態、または利用可能メモリが極めて少ない状態
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
        val availablePercent: Int,
        val lowMemoryFlag: Boolean
    )
    
    /**
     * Get current memory status.
     * #6 fix: use MemAvailable from /proc/meminfo when available, falling back to ActivityManager.MemoryInfo.availMem.
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
    private fun getSystemMemoryInfoBlocking(context: Context): SystemMemoryInfo {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) {
                return SystemMemoryInfo(0, 0, 0, 0, 0, false)
            }

            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalMemoryMB = memInfo.totalMem / (1024 * 1024)
            val availableMemoryMB = getAvailableMemoryMB(memInfo)
            val usedMemoryMB = totalMemoryMB - availableMemoryMB
            val usedPercent = if (totalMemoryMB > 0) {
                ((usedMemoryMB * 100) / totalMemoryMB).toInt()
            } else {
                0
            }
            val availablePercent = (100 - usedPercent).coerceIn(0, 100)

            Log.d(
                TAG,
                "SYSTEM_MEMORY_INFO: totalMem=${memInfo.totalMem}B (${totalMemoryMB}MB) " +
                    "MemAvailable=${availableMemoryMB}MB usedMemory=${usedMemoryMB}MB " +
                    "usedPercent=${usedPercent}% availablePercent=${availablePercent}% lowMemory=${memInfo.lowMemory}"
            )

            SystemMemoryInfo(
                totalMemoryMB = totalMemoryMB,
                availableMemoryMB = availableMemoryMB,
                usedMemoryMB = usedMemoryMB,
                usedPercent = usedPercent,
                availablePercent = availablePercent,
                lowMemoryFlag = memInfo.lowMemory
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get system memory info", e)
            SystemMemoryInfo(0, 0, 0, 0, 0, false)
        }
    }

    suspend fun getSystemMemoryInfo(context: Context): SystemMemoryInfo {
        return withContext(Dispatchers.IO) {
            getSystemMemoryInfoBlocking(context)
        }
    }

    @Deprecated("Use suspend getSystemMemoryInfo(context) when possible.")
    fun getSystemMemoryInfoSync(context: Context): SystemMemoryInfo {
        return runBlocking(Dispatchers.IO) {
            getSystemMemoryInfo(context)
        }
    }

    /**
     * 1秒ごとにシステムメモリ情報をサンプリングする Flow を返す。
     */
    fun observeSystemMemoryInfo(context: Context, sampleIntervalMs: Long = 1000L): Flow<SystemMemoryInfo> {
        return flow {
            while (true) {
                emit(getSystemMemoryInfo(context))
                delay(sampleIntervalMs)
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * /proc/meminfo の MemAvailable (KB) を読む。
     * Androidシステム設定画面の「空きRAM」と同じ基準。
     * @return MemAvailable の値 (KB)、読み取り失敗時は null
     */
    private fun readMemAvailableKB(): Long? {
        return try {
            File("/proc/meminfo").useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("MemAvailable:")) {
                        return@useLines trimmed.split(Regex("\\s+"))
                            .getOrNull(1)?.toLongOrNull()
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/meminfo: ${e.message}")
            null
        }
    }

    private fun getMemAvailableBytes(): Long? {
        return readMemAvailableKB()?.takeIf { it > 0 }?.let { it * 1024L }
    }

    private fun getAvailableBytes(memInfo: ActivityManager.MemoryInfo): Long {
        return getMemAvailableBytes() ?: memInfo.availMem
    }

    private fun getAvailableMemoryMB(memInfo: ActivityManager.MemoryInfo): Long {
        return getAvailableBytes(memInfo) / (1024 * 1024)
    }

    private fun getAvailableMemoryGB(memInfo: ActivityManager.MemoryInfo): Float {
        return getAvailableBytes(memInfo) / BYTES_IN_GB
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

        if (sysInfo.availableMemoryMB < 800) {
            Log.w(TAG, "Memory: avail=${sysInfo.availableMemoryMB}MB - WARNING. Suggesting gc()")
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
     * 現在の空きメモリ（MemAvailable）を基準にするため、アンロード直後に呼ぶこと。
     * @return true: メモリが不足している / false: メモリが十分
     */
    fun isMemoryLow(context: Context, modelName: String): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val minRequiredGb = MODEL_MIN_MEMORY[modelName.uppercase()]

        return if (activityManager != null && minRequiredGb != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            // MemAvailable を使用（システム設定画面と同じ基準）
            val availableGb = getAvailableMemoryGB(memInfo)

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

        val availableGb = getAvailableMemoryGB(memInfo)
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
