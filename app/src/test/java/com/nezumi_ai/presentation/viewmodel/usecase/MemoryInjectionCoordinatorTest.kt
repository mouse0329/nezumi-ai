package com.nezumi_ai.presentation.viewmodel.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6: メモリ注入の予算計算・ブロック整形（ViewModel から切り出した純粋ロジック）。
 */
class MemoryInjectionCoordinatorTest {

    @Test
    fun computeMemoryBudget_usesFifteenPercentWhenRoomAllows() {
        // 4096 tokens * 4 = 16384 chars; 15% = 2457
        // system 100, reserve 2048 → available 14236 → min(2457, 14236) = 2457
        val budget = MemoryInjectionCoordinator.computeMemoryBudgetChars(
            contextWindowTokens = 4096,
            systemPromptChars = 100
        )
        assertEquals(2457, budget)
    }

    @Test
    fun computeMemoryBudget_returnsZeroWhenSystemPromptExhaustsWindow() {
        val total = 512 * MemoryInjectionCoordinator.TOKEN_TO_CHAR_RATIO // 2048
        val budget = MemoryInjectionCoordinator.computeMemoryBudgetChars(
            contextWindowTokens = 512,
            systemPromptChars = total // すべて system が占有
        )
        assertEquals(0, budget)
    }

    @Test
    fun computeBudgetBreakdown_matchesFinalBudget() {
        val b = MemoryInjectionCoordinator.computeBudgetBreakdown(4096, 200)
        assertEquals(b.finalBudgetChars, MemoryInjectionCoordinator.computeMemoryBudgetChars(4096, 200))
        assertTrue(b.totalBudgetChars > 0)
        assertTrue(b.fifteenPercentChars > 0)
    }

    @Test
    fun formatMemoryBlock_nullWhenBudgetZeroOrEmpty() {
        assertNull(MemoryInjectionCoordinator.formatMemoryBlock(emptyList(), 1000))
        assertNull(MemoryInjectionCoordinator.formatMemoryBlock(listOf("a"), 0))
    }

    @Test
    fun formatMemoryBlock_fitsItemsWithinBudget() {
        val contents = listOf("短い1", "短い2", "短い3")
        val block = MemoryInjectionCoordinator.formatMemoryBlock(contents, 500)
        assertNotNull(block)
        assertTrue(block!!.startsWith("関連メモリ:"))
        assertTrue(block.contains("1. 短い1"))
        assertTrue(block.contains("2. 短い2"))
        assertTrue(block.contains("3. 短い3"))
    }

    @Test
    fun formatMemoryBlock_stopsWhenEntryWouldExceedBudget() {
        // header 10 + entry overhead 5; 1件目 100 文字 → 115; 2件目も 100 なら超過する予算
        val long = "x".repeat(100)
        val block = MemoryInjectionCoordinator.formatMemoryBlock(
            listOf(long, long),
            memoryBudgetChars = 120
        )
        assertNotNull(block)
        assertTrue(block!!.contains("1. "))
        assertFalse(block.contains("2. "))
    }

    @Test
    fun formatMemoryBlock_nullWhenEvenFirstEntryDoesNotFit() {
        val huge = "y".repeat(1000)
        assertNull(
            MemoryInjectionCoordinator.formatMemoryBlock(
                listOf(huge),
                memoryBudgetChars = 20 // header 10 + 1005 > 20
            )
        )
    }
}
