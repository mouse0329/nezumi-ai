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
 *
 * #11 fix: androidFreeRam ベースの空きメモリ取得に変更。
 *   /proc/meminfo の MemFree + (Cached - Shmem) + SReclaimable - Unevictable を合算。
 *   MemAvailable のみでは約2GBの乖離があったが、この方式で Android 設定の「空きRAM」に近い値を取得できる。
 *   isMemoryLowForFileSize の thresholdPercent の意味を「モデルサイズに対して必要な空きメモリの割合」に再定義し、
 *   デフォルトを 60% に変更（3GB モデル × 0.6 = 1.8GB の空きが必要）。
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

    // #11 fix: thresholdPercent の意味を再定義。
    //   モデルサイズ × thresholdPercent% のメモリが必要。
    //   例: 60% → 3GB モデルなら 1.8GB のメモリが必要。
    //   ダウンロード時は総メモリ、ロード時は空きメモリで判定。
    const val DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 60
    const val MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 0
    const val MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT = 300

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
        val lowMemoryFlag: Boolean,
        val source: String = ""
    )

    /**
     * Get current memory status.
     * #11 fix: androidFreeRam ベースの空きメモリを使用。
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
     * スマホ本体のシステムメモリ情報を取得。
     * #11 fix: androidFreeRam（/proc/meminfo 複数フィールド合算）を優先使用。
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

            val memFields = readMemInfoFields()
            val androidFreeKB = getAndroidFreeRamKB(memFields)
            val memAvailableKB = memFields["MemAvailable"]

            val availableMemoryMB = when {
                androidFreeKB > 0 -> androidFreeKB / 1024
                memAvailableKB != null -> memAvailableKB / 1024
                else -> memInfo.availMem / (1024 * 1024)
            }
            val source = when {
                androidFreeKB > 0 -> "androidFreeRam"
                memAvailableKB != null -> "MemAvailable"
                else -> "availMem"
            }

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
                    "source=${source} availableMemory=${availableMemoryMB}MB usedMemory=${usedMemoryMB}MB " +
                    "usedPercent=${usedPercent}% availablePercent=${availablePercent}% lowMemory=${memInfo.lowMemory}"
            )

            SystemMemoryInfo(
                totalMemoryMB = totalMemoryMB,
                availableMemoryMB = availableMemoryMB,
                usedMemoryMB = usedMemoryMB,
                usedPercent = usedPercent,
                availablePercent = availablePercent,
                lowMemoryFlag = memInfo.lowMemory,
                source = source
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
     * 指定間隔でシステムメモリ情報をサンプリングする Flow を返す。
     * デフォルト5秒（androidFreeRam の計算コストを考慮）。
     */
    fun observeSystemMemoryInfo(context: Context, sampleIntervalMs: Long = 5000L): Flow<SystemMemoryInfo> {
        return flow {
            while (true) {
                emit(getSystemMemoryInfo(context))
                delay(sampleIntervalMs)
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * /proc/meminfo の各フィールドを合算して Android の「空きRAM」を近似的に算出する。
     * #11 fix: MemAvailable のみでは Android 設定と約2GB乖離があったため、複数フィールドを合算。
     * 空きRAM ≈ MemFree + (Cached - Shmem) + SReclaimable - Unevictable
     *   - Cached: ページキャッシュ（必要に応じて解放可能）
     *   - Shmem: 共有メモリ（Cached に含まれるが解放不可のため除外）
     *   - SReclaimable: 回収可能なカーネルスラブ
     *   - Unevictable: ロックされた解放不可ページ
     */
    private fun getAndroidFreeRamKB(memFields: Map<String, Long>): Long {
        val memFree = memFields["MemFree"] ?: 0L
        val cached = memFields["Cached"] ?: 0L
        val sReclaimable = memFields["SReclaimable"] ?: 0L
        val shmem = memFields["Shmem"] ?: 0L
        val unevictable = memFields["Unevictable"] ?: 0L

        val freeableCache = (cached - shmem).coerceAtLeast(0L)
        return memFree + freeableCache + sReclaimable - unevictable
    }

    /**
     * /proc/meminfo の全フィールドを Map として読み取る。
     */
    private fun readMemInfoFields(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            File("/proc/meminfo").useLines { lines ->
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val key = parts[0].trimEnd(':')
                        val value = parts[1].toLongOrNull() ?: continue
                        result[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/meminfo fields", e)
        }
        return result
    }

    /**
     * メモリ不足に対する段階的な対応を推奨。
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
     * 異常なメモリ急増を検出（前回比との差分チェック）。
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
     * MODEL_MIN_MEMORY に載っていないモデル（GGUF 等）は isMemoryLowForFileSize で判定すること。
     * @return true: メモリが不足している / false: メモリが十分
     */
    fun isMemoryLow(context: Context, modelName: String): Boolean {
        val minRequiredGb = MODEL_MIN_MEMORY[modelName.uppercase()]
        if (minRequiredGb == null) {
            Log.w(TAG, "isMemoryLow: unknown model=$modelName, skipping check")
            return false
        }

        val memFields = readMemInfoFields()
        val androidFreeKB = getAndroidFreeRamKB(memFields)
        val availableGb = androidFreeKB / 1024f / 1024f  // KB → GB

        Log.d(TAG, "isMemoryLow: model=$modelName availableGb=$availableGb minRequired=$minRequiredGb")
        return availableGb < minRequiredGb
    }

    /**
     * モデルファイルサイズから必要なメモリを推定してメモリ不足を検知。
     * #11 fix: androidFreeRam ベースの空きメモリで判定。
     *   thresholdPercent はモデルサイズに対して必要なメモリの割合。
     *   例: 60% → 3GB モデルなら 1.8GB のメモリが必要。
     * @param modelFileSizeBytes モデルファイルのサイズ（バイト）
     * @param thresholdPercent モデルサイズの何%のメモリが必要か（デフォルト60）
     * @param useAvailable true: 空きメモリ基準（ロード時） / false: 総メモリ基準（ダウンロード時）
     * @return true: メモリが不足している / false: メモリが十分
     */
    fun isMemoryLowForFileSize(
        context: Context,
        modelFileSizeBytes: Long,
        thresholdPercent: Int = DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT,
        useAvailable: Boolean = true
    ): Boolean {
        if (thresholdPercent <= 0) return false

        val modelFileSizeGb = modelFileSizeBytes / BYTES_IN_GB
        val requiredGb = modelFileSizeGb * (thresholdPercent / 100f)

        val isLow = if (useAvailable) {
            // ロード時: 空きメモリが必要量以上あるかチェック
            val memFields = readMemInfoFields()
            val androidFreeKB = getAndroidFreeRamKB(memFields)
            val availableGb = androidFreeKB / 1024f / 1024f  // KB → GB

            Log.d(
                TAG,
                "isMemoryLowForFileSize: modelFileSize=${modelFileSizeGb}GB threshold=${thresholdPercent}% " +
                    "required=${requiredGb}GB availableGb=${availableGb}GB isLow=${availableGb < requiredGb}"
            )
            availableGb < requiredGb
        } else {
            // ダウンロード時: 総メモリが必要量以上あるかチェック
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalGb = memInfo.totalMem / BYTES_IN_GB

            Log.d(
                TAG,
                "isMemoryLowForFileSize: modelFileSize=${modelFileSizeGb}GB threshold=${thresholdPercent}% " +
                    "required=${requiredGb}GB totalGb=${totalGb}GB isLow=${totalGb < requiredGb}"
            )
            totalGb < requiredGb
        }

        return isLow
    }
}