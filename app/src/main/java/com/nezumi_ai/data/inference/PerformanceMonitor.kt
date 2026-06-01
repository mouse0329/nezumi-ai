package com.nezumi_ai.data.inference

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 推論パフォーマンスをモニタリングするユーティリティ。
 * 
 * - トークン生成速度（tokens/sec）
 * - 初回トークン生成時間（TTFT: Time To First Token）
 * - メモリ使用量
 * - GPU/CPU使用率の推定
 */
object PerformanceMonitor {
    
    private const val TAG = "PerformanceMonitor"
    
    data class InferenceMetrics(
        val sessionId: Long,
        val startTimeMs: Long,
        val firstTokenTimeMs: Long?,
        val endTimeMs: Long?,
        val totalTokens: Int,
        val promptTokens: Int,
        val tokensPerSecond: Float?,
        val ttftMs: Long?,
        val peakMemoryMb: Long,
        val avgMemoryMb: Long,
        val backend: String
    ) {
        fun toLogString(): String {
            return "InferenceMetrics(session=$sessionId, tokens=$totalTokens, " +
                   "tps=${tokensPerSecond?.let { "%.2f".format(it) } ?: "N/A"}, " +
                   "ttft=${ttftMs}ms, peakMem=${peakMemoryMb}MB, backend=$backend)"
        }
    }
    
    private val activeMetrics = ConcurrentHashMap<Long, MetricsBuilder>()
    private val completedMetrics = mutableListOf<InferenceMetrics>()
    private val metricsMutex = Mutex()
    
    private class MetricsBuilder(
        val sessionId: Long,
        val backend: String,
        val promptTokens: Int
    ) {
        val startTimeMs = System.currentTimeMillis()
        var firstTokenTimeMs: Long? = null
        var endTimeMs: Long? = null
        var totalTokens = 0
        val memorySnapshots = mutableListOf<Long>()
        
        fun recordToken() {
            if (firstTokenTimeMs == null) {
                firstTokenTimeMs = System.currentTimeMillis()
            }
            totalTokens++
            recordMemory()
        }
        
        fun recordMemory() {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            memorySnapshots.add(usedMemory)
        }
        
        fun build(): InferenceMetrics {
            endTimeMs = System.currentTimeMillis()
            val ttft = firstTokenTimeMs?.let { it - startTimeMs }
            val totalTime = endTimeMs!! - startTimeMs
            val tps = if (totalTokens > 0 && totalTime > 0) {
                (totalTokens * 1000f) / totalTime
            } else null
            
            return InferenceMetrics(
                sessionId = sessionId,
                startTimeMs = startTimeMs,
                firstTokenTimeMs = firstTokenTimeMs,
                endTimeMs = endTimeMs,
                totalTokens = totalTokens,
                promptTokens = promptTokens,
                tokensPerSecond = tps,
                ttftMs = ttft,
                peakMemoryMb = memorySnapshots.maxOrNull() ?: 0L,
                avgMemoryMb = if (memorySnapshots.isNotEmpty()) {
                    memorySnapshots.average().toLong()
                } else 0L,
                backend = backend
            )
        }
    }
    
    /**
     * 推論セッションの開始を記録
     */
    fun startInference(sessionId: Long, backend: String, promptTokens: Int) {
        activeMetrics[sessionId] = MetricsBuilder(sessionId, backend, promptTokens)
        Log.d(TAG, "Started monitoring session $sessionId (backend=$backend, promptTokens=$promptTokens)")
    }
    
    /**
     * トークン生成を記録
     */
    fun recordToken(sessionId: Long) {
        activeMetrics[sessionId]?.recordToken()
    }
    
    /**
     * 推論セッションの終了を記録
     */
    suspend fun endInference(sessionId: Long): InferenceMetrics? {
        val builder = activeMetrics.remove(sessionId) ?: return null
        val metrics = builder.build()
        
        metricsMutex.withLock {
            completedMetrics.add(metrics)
            // 最新100件のみ保持
            if (completedMetrics.size > 100) {
                completedMetrics.removeAt(0)
            }
        }
        
        Log.i(TAG, "Inference completed: ${metrics.toLogString()}")
        return metrics
    }
    
    /**
     * 完了したメトリクスの統計を取得
     */
    suspend fun getStatistics(): Statistics {
        return metricsMutex.withLock {
            if (completedMetrics.isEmpty()) {
                return@withLock Statistics.empty()
            }
            
            val tpsList = completedMetrics.mapNotNull { it.tokensPerSecond }
            val ttftList = completedMetrics.mapNotNull { it.ttftMs }
            
            Statistics(
                totalInferences = completedMetrics.size,
                avgTokensPerSecond = tpsList.average().toFloat(),
                maxTokensPerSecond = tpsList.maxOrNull() ?: 0f,
                minTokensPerSecond = tpsList.minOrNull() ?: 0f,
                avgTtftMs = ttftList.average().toLong(),
                avgPeakMemoryMb = completedMetrics.map { it.peakMemoryMb }.average().toLong(),
                backendDistribution = completedMetrics.groupingBy { it.backend }.eachCount()
            )
        }
    }

    suspend fun getLastCompletedInferenceMetrics(): InferenceMetrics? {
        return metricsMutex.withLock {
            completedMetrics.lastOrNull()
        }
    }

    suspend fun getLastCompletedTokenCount(): Float? {
        return getLastCompletedInferenceMetrics()?.totalTokens?.toFloat()
    }
    
    data class Statistics(
        val totalInferences: Int,
        val avgTokensPerSecond: Float,
        val maxTokensPerSecond: Float,
        val minTokensPerSecond: Float,
        val avgTtftMs: Long,
        val avgPeakMemoryMb: Long,
        val backendDistribution: Map<String, Int>
    ) {
        companion object {
            fun empty() = Statistics(0, 0f, 0f, 0f, 0L, 0L, emptyMap())
        }
        
        fun toLogString(): String {
            return "Statistics(inferences=$totalInferences, " +
                   "avgTps=${"%.2f".format(avgTokensPerSecond)}, " +
                   "maxTps=${"%.2f".format(maxTokensPerSecond)}, " +
                   "avgTtft=${avgTtftMs}ms, " +
                   "avgPeakMem=${avgPeakMemoryMb}MB, " +
                   "backends=$backendDistribution)"
        }
    }
    
    /**
     * システム情報を取得
     */
    fun getSystemInfo(): SystemInfo {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory
        val availableProcessors = runtime.availableProcessors()
        
        return SystemInfo(
            maxMemoryMb = maxMemory,
            totalMemoryMb = totalMemory,
            usedMemoryMb = usedMemory,
            freeMemoryMb = freeMemory,
            availableProcessors = availableProcessors
        )
    }
    
    data class SystemInfo(
        val maxMemoryMb: Long,
        val totalMemoryMb: Long,
        val usedMemoryMb: Long,
        val freeMemoryMb: Long,
        val availableProcessors: Int
    ) {
        fun toLogString(): String {
            return "SystemInfo(maxMem=${maxMemoryMb}MB, " +
                   "used=${usedMemoryMb}MB, " +
                   "free=${freeMemoryMb}MB, " +
                   "cores=$availableProcessors)"
        }
    }
    
    /**
     * メトリクスをクリア
     */
    suspend fun clear() {
        metricsMutex.withLock {
            completedMetrics.clear()
        }
        activeMetrics.clear()
        Log.d(TAG, "Metrics cleared")
    }
}
