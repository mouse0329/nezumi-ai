package com.nezumi_ai.data.benchmark

/**
 * 1回のベンチマーク実行結果
 */
data class BenchmarkResult(
    val runIndex: Int,
    val prompt: BenchmarkPrompt,
    val ttftMs: Long,           // Time to First Token（ms）
    val totalMs: Long,          // 全生成時間（ms）
    val tokenCount: Int,        // 生成トークン数（空白区切り近似）
    val tokensPerSec: Double,   // tokens/sec
    val memBeforeMB: Long,      // 推論前 使用メモリ（MB）
    val memAfterMB: Long,       // 推論後 使用メモリ（MB）
    val memPeakDeltaMB: Long,   // peak増加分（memAfter - memBefore）
    val engineName: String,     // "LiteRT" or "GGUF"
    val error: String? = null   // エラーがあれば
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * 複数回実行の集計結果
 */
data class BenchmarkSummary(
    val results: List<BenchmarkResult>,
    val engineName: String,
    val modelName: String
) {
    val successResults = results.filter { it.isSuccess }

    val avgTtftMs: Double get() =
        successResults.map { it.ttftMs.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0

    val avgTokensPerSec: Double get() =
        successResults.map { it.tokensPerSec }.average().takeIf { !it.isNaN() } ?: 0.0

    val minTokensPerSec: Double get() =
        successResults.minOfOrNull { it.tokensPerSec } ?: 0.0

    val maxTokensPerSec: Double get() =
        successResults.maxOfOrNull { it.tokensPerSec } ?: 0.0

    val avgMemDeltaMB: Double get() =
        successResults.map { it.memPeakDeltaMB.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0

    val totalRuns: Int get() = results.size
    val successRuns: Int get() = successResults.size
}

/**
 * ベンチマーク用プロンプト定義
 */
data class BenchmarkPrompt(
    val id: String,
    val label: String,
    val text: String,
    val expectedTokens: Int   // 目安トークン数（参考値）
) {
    companion object {
        val SHORT = BenchmarkPrompt(
            id = "short",
            label = "短文",
            text = "日本の首都はどこですか？",
            expectedTokens = 30
        )
        val MEDIUM = BenchmarkPrompt(
            id = "medium",
            label = "中文",
            text = "人工知能の歴史について、1950年代から現在までの主要な出来事を簡潔に説明してください。",
            expectedTokens = 150
        )
        val LONG = BenchmarkPrompt(
            id = "long",
            label = "長文",
            text = "Kotlinのコルーチンについて、基本概念・CoroutineScope・Dispatcher・Flow・構造化並行性を含めて詳しく解説してください。",
            expectedTokens = 400
        )

        val ALL = listOf(SHORT, MEDIUM, LONG)
    }
}
