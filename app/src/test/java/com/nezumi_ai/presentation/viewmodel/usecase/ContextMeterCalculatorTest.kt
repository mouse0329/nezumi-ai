package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1: コンテキストメーターの純粋計算（ViewModel から切り出したクラスタ D）。
 */
class ContextMeterCalculatorTest {

    private val calculator = ContextMeterCalculator()

    private fun msg(
        id: Long,
        role: String,
        content: String = "",
        parent: Long? = null,
        variant: Int = 0,
        imageUri: String? = null,
        audioUri: String? = null,
        timestamp: Long = id
    ) = MessageEntity(
        id = id,
        sessionId = 1L,
        role = role,
        content = content,
        timestamp = timestamp,
        parentUserMessageId = parent,
        variantIndex = variant,
        imageUri = imageUri,
        audioUri = audioUri
    )

    @Test
    fun applyVariantSelection_keepsUserAndChoosesLatestAssistantByDefault() {
        val user = msg(1, "user", "hi")
        val a0 = msg(2, "assistant", "v0", parent = 1, variant = 0)
        val a1 = msg(3, "assistant", "v1", parent = 1, variant = 1)
        val selected = calculator.applyVariantSelection(listOf(user, a0, a1), emptyMap())
        assertEquals(listOf(1L, 3L), selected.map { it.id })
    }

    @Test
    fun applyVariantSelection_respectsExplicitIndex() {
        val user = msg(1, "user", "hi")
        val a0 = msg(2, "assistant", "v0", parent = 1, variant = 0)
        val a1 = msg(3, "assistant", "v1", parent = 1, variant = 1)
        val selected = calculator.applyVariantSelection(
            listOf(user, a0, a1),
            mapOf(1L to 0)
        )
        assertEquals(listOf(1L, 2L), selected.map { it.id })
    }

    @Test
    fun messagesIncludedInModelContext_excludesRegeneratingParentAssistants() {
        val user = msg(10, "user", "q")
        val a = msg(11, "assistant", "old", parent = 10, variant = 0)
        val otherUser = msg(20, "user", "other")
        val otherA = msg(21, "assistant", "ok", parent = 20, variant = 0)
        val included = calculator.messagesIncludedInModelContext(
            listOf(user, a, otherUser, otherA),
            emptyMap(),
            regeneratingParentUserMessageId = 10L
        )
        assertEquals(listOf(10L, 20L, 21L), included.map { it.id })
        assertFalse(included.any { it.id == 11L })
    }

    @Test
    fun recountAttachmentCount_countsImagesAndAudio() {
        val messages = listOf(
            msg(1, "user", imageUri = "content://a.jpg,content://b.png"),
            msg(2, "user", audioUri = "content://sound.wav")
        )
        assertEquals(3, calculator.recountAttachmentCount(messages))
    }

    @Test
    fun recountGgufMediaTokens_zeroWhenRatesUnknown() {
        val messages = listOf(msg(1, "user", imageUri = "content://a.jpg"))
        assertEquals(0, calculator.recountGgufMediaTokens(messages, 0, 0))
    }

    @Test
    fun recountGgufMediaTokens_usesPerImageAndAudioRates() {
        val messages = listOf(
            msg(1, "user", imageUri = "content://a.jpg,content://b.jpg"),
            msg(2, "user", audioUri = "content://s.wav")
        )
        // 2 images * 10 + 1 audio * 5
        assertEquals(25, calculator.recountGgufMediaTokens(messages, 10, 5))
    }

    @Test
    fun scaleSegmentsAfterSend_addsGeneratedWhenTotalDoesNotGrowChat() {
        // total - media - fixed <= currentChat → chat = current + generated
        val scaled = calculator.scaleSegmentsAfterSend(
            total = 30,
            media = 5,
            currentSystemTokens = 10,
            currentToolTokens = 2,
            currentChatTokens = 20,
            generated = 7
        )
        assertEquals(10, scaled.systemTokens)
        assertEquals(2, scaled.toolTokens)
        assertEquals(27, scaled.chatTokens) // 20 + 7
        assertEquals(5, scaled.mediaTokens)
        assertEquals(44, scaled.publishedTotal)
    }

    @Test
    fun scaleSegmentsAfterSend_usesChatFromTotalWhenLarger() {
        // textTotal 100 - fixed 12 = 88 > currentChat 20 → chat = 88
        val scaled = calculator.scaleSegmentsAfterSend(
            total = 110,
            media = 10,
            currentSystemTokens = 10,
            currentToolTokens = 2,
            currentChatTokens = 20,
            generated = 0
        )
        assertEquals(88, scaled.chatTokens)
        assertEquals(10, scaled.mediaTokens)
    }
}
