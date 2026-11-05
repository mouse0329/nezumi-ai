package com.nezumi_ai.presentation.viewmodel.usecase

/**
 * クラスタ H (メモリ注入) の純粋ロジック。
 *
 * 埋め込み初期化・検索・プリセット判定は ViewModel / Repository 側。
 * ここではコンテキスト予算の計算と、検索ヒットをプロンプト用ブロックへ整形する。
 */
object MemoryInjectionCoordinator {

    const val TOKEN_TO_CHAR_RATIO = 4
    const val MEMORY_BUDGET_RATIO = 0.15f
    const val HISTORY_RESERVE_CHARS = 2048
    /** 「関連メモリ:\n」相当のオーバーヘッド */
    const val MEMORY_HEADER_CHARS = 10
    const val MIN_MEMORY_BUDGET_CHARS = 500

    /**
     * システムプロンプト長とコンテキスト窓から、メモリ注入に使える文字数予算を求める。
     * 0 のときは注入をスキップする。
     */
    fun computeMemoryBudgetChars(
        contextWindowTokens: Int,
        systemPromptChars: Int
    ): Int {
        val totalBudgetChars = contextWindowTokens * TOKEN_TO_CHAR_RATIO
        val memoryBudgetCharsBeforeCoerce = (totalBudgetChars * MEMORY_BUDGET_RATIO).toInt()
        val availableCharsAfterReserves = totalBudgetChars - systemPromptChars - HISTORY_RESERVE_CHARS

        return when {
            availableCharsAfterReserves >= MIN_MEMORY_BUDGET_CHARS ->
                minOf(memoryBudgetCharsBeforeCoerce, availableCharsAfterReserves)
            availableCharsAfterReserves > 0 ->
                minOf(MIN_MEMORY_BUDGET_CHARS, availableCharsAfterReserves).coerceAtLeast(100)
            else -> {
                val adjustedReserve = HISTORY_RESERVE_CHARS / 2
                val remainingBudget = totalBudgetChars - systemPromptChars - adjustedReserve
                minOf(MIN_MEMORY_BUDGET_CHARS, remainingBudget).coerceAtLeast(0)
            }
        }
    }

    /**
     * スコア高い順の content リストを予算内に詰め、「関連メモリ:」ブロックにする。
     * 1件も入らなければ null。
     */
    fun formatMemoryBlock(
        contentsInScoreOrder: List<String>,
        memoryBudgetChars: Int
    ): String? {
        if (memoryBudgetChars <= 0 || contentsInScoreOrder.isEmpty()) return null

        val selected = mutableListOf<String>()
        var usedChars = MEMORY_HEADER_CHARS
        for (content in contentsInScoreOrder) {
            val entryChars = content.length + 5 // "N. \n"
            if (usedChars + entryChars > memoryBudgetChars) break
            selected.add(content.trim())
            usedChars += entryChars
        }
        if (selected.isEmpty()) return null

        return buildString {
            append("関連メモリ:\n")
            selected.forEachIndexed { index, text ->
                append(index + 1)
                append(". ")
                append(text)
                append('\n')
            }
        }.trim()
    }

    /**
     * 予算計算の内訳（ログ用）。
     */
    data class BudgetBreakdown(
        val totalBudgetChars: Int,
        val fifteenPercentChars: Int,
        val availableAfterReserves: Int,
        val finalBudgetChars: Int
    )

    fun computeBudgetBreakdown(
        contextWindowTokens: Int,
        systemPromptChars: Int
    ): BudgetBreakdown {
        val totalBudgetChars = contextWindowTokens * TOKEN_TO_CHAR_RATIO
        val fifteen = (totalBudgetChars * MEMORY_BUDGET_RATIO).toInt()
        val available = totalBudgetChars - systemPromptChars - HISTORY_RESERVE_CHARS
        val final = computeMemoryBudgetChars(contextWindowTokens, systemPromptChars)
        return BudgetBreakdown(totalBudgetChars, fifteen, available, final)
    }
}
