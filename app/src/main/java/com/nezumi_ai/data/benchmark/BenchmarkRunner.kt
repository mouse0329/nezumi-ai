package com.nezumi_ai.data.benchmark

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.TextTokenEstimator
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

/**
 * ModelManager を通じてベンチマーク計測を行うランナー。
 * 既存の推論パイプラインに変更を加えず、Flow を観測するだけで TTFT / TPS / メモリを計測する。
 */
class BenchmarkRunner(
    private val context: Context,
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "BenchmarkRunner"
        /** ウォームアップ用セッションID */
        private const val WARMUP_SESSION_ID = -999L
        /** 本計測用セッションID（0はメモリ抽出専用経路なので避ける） */
        private const val BENCH_SESSION_ID = -998L
        /** InferenceStreamProtocol のプレフィックス先頭 */
        private const val PROTOCOL_PREFIX = "\u0000__"
    }

    /**
     * ウォームアップ実行（結果は捨てる）。
     */
    suspend fun warmup(config: InferenceConfig) {
        Log.d(TAG, "Warmup start")
        try {
            modelManager.runInference(WARMUP_SESSION_ID, BenchmarkPrompt.SHORT.text, config)
                .catch { /* warmupなので無視 */ }
                .collect { /* 捨てる */ }
        } catch (e: Exception) {
            Log.w(TAG, "Warmup failed (ignored): ${e.message}")
        }
        Log.d(TAG, "Warmup done")
    }

    /**
     * 指定プロンプトで1回ベンチマークを実行する。
     *
     * @param prompt      ベンチマーク用プロンプト
     * @param config      推論設定（enableThinking=false 推奨）
     * @param engineName  "LiteRT" or "GGUF" など（表示用。呼び出し元が指定）
     * @param runIndex    繰り返し番号（0始まり）
     */
    suspend fun runOnce(
        prompt: BenchmarkPrompt,
        config: InferenceConfig,
        engineName: String,
        runIndex: Int,
        onChunk: (String) -> Unit = {}
    ): BenchmarkResult {
        val memBefore = getUsedMemoryMB()

        var ttftMs = -1L
        var tokenCount = 0f
        val startMs = System.currentTimeMillis()
        var firstTokenAbsMs = -1L
        var error: String? = null

        try {
            modelManager.runInference(BENCH_SESSION_ID, prompt.text, config)
                .catch { e ->
                    error = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "Benchmark inference error run=$runIndex prompt=${prompt.id}: $error", e)
                }
                .collect { chunk ->
                    // InferenceStreamProtocol プレフィックス付きチャンクはスキップ
                    if (chunk.startsWith(PROTOCOL_PREFIX)) return@collect

                    if (chunk.isNotEmpty()) {
                        // ここでライブ出力コールバックを呼ぶ
                        try {
                            onChunk(chunk)
                        } catch (e: Exception) {
                            Log.w(TAG, "onChunk callback failed: ${e.message}")
                        }

                        // TTFT: 最初のテキストチャンクが届いた時刻
                        if (firstTokenAbsMs < 0) {
                            firstTokenAbsMs = System.currentTimeMillis()
                            ttftMs = firstTokenAbsMs - startMs
                            Log.d(TAG, "[$runIndex] TTFT=${ttftMs}ms prompt=${prompt.id}")
                        }
                        tokenCount += TextTokenEstimator.estimateOutputTokens(chunk)
                    }
                }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Benchmark outer error run=$runIndex: $error", e)
        }

        val totalMs = System.currentTimeMillis() - startMs
        val memAfter = getUsedMemoryMB()
        val tokensPerSec = if (totalMs > 0 && tokenCount > 0f) {
            tokenCount * 1000.0 / totalMs
        } else 0.0

        Log.d(TAG, "[$runIndex] done: ttft=${ttftMs}ms total=${totalMs}ms tokens=%.1f tps=%.1f mem_delta=${memAfter - memBefore}MB".format(tokenCount, tokensPerSec))

        return BenchmarkResult(
            runIndex = runIndex,
            prompt = prompt,
            ttftMs = ttftMs.coerceAtLeast(0),
            totalMs = totalMs,
            tokenCount = tokenCount.roundToInt(),
            tokensPerSec = tokensPerSec,
            memBeforeMB = memBefore,
            memAfterMB = memAfter,
            memPeakDeltaMB = (memAfter - memBefore).coerceAtLeast(0),
            engineName = engineName,
            error = error
        )
    }

    private fun getUsedMemoryMB(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem - info.availMem) / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }
}
