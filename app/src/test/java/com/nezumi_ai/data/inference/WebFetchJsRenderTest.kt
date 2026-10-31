package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchJsRenderTest {
    @Test
    fun mainFrame_acceptsFinalUrlAfterRedirect() {
        assertTrue(
            isMainFramePageFinished(
                "https://example.com/article",
                "https://example.com/article"
            )
        )
    }

    @Test
    fun mainFrame_rejectsAboutBlankAndIframes() {
        assertFalse(isMainFramePageFinished("about:blank", "about:blank"))
        assertFalse(isMainFramePageFinished("https://ads.example/i", "https://example.com/article"))
        assertFalse(isMainFramePageFinished(null, "https://example.com"))
        assertFalse(isMainFramePageFinished("https://example.com", null))
    }

    @Test
    fun joinChunks_requiresEveryIndex() {
        assertEquals("abcdef", joinChunks(3, mapOf(2 to "ef", 0 to "ab", 1 to "cd")))
        assertNull(joinChunks(3, mapOf(0 to "ab", 1 to "cd")))
        assertNull(joinChunks(0, emptyMap()))
        assertNull(joinChunks(33, emptyMap()))
    }

    @Test
    fun choose_prefersUsefulJsRender() {
        val js = mapOf("success" to true, "length" to 400, "markdown" to "from-js", "jsRendered" to true)
        val staticResult = mapOf("success" to true, "length" to 900, "markdown" to "from-static")
        assertEquals("from-js", chooseJsOrStatic(js, staticResult)["markdown"])
        assertEquals(true, chooseJsOrStatic(js, staticResult)["jsRendered"])
    }

    @Test
    fun choose_fallsBackWhenJsIsShortOrMissing() {
        val shortJs = mapOf("success" to true, "length" to 40, "markdown" to "shell", "jsRendered" to true)
        val staticResult = mapOf("success" to true, "length" to 800, "markdown" to "article")
        val chosen = chooseJsOrStatic(shortJs, staticResult)
        assertEquals("article", chosen["markdown"])
        assertEquals(true, chosen["jsFallback"])
        assertEquals(false, chosen["jsRendered"])

        val failed = mapOf("success" to false, "error" to "fetch_failed")
        val fallback = chooseJsOrStatic(null, failed)
        assertEquals(false, fallback["success"])
        assertEquals(true, fallback["jsFallback"])
    }

    @Test
    fun choose_keepsShortJsWhenStaticIsEmpty() {
        val shortJs = mapOf("success" to true, "length" to 80, "markdown" to "partial", "jsRendered" to true)
        val staticResult = mapOf("success" to false, "error" to "empty_content", "length" to 0)
        assertEquals("partial", chooseJsOrStatic(shortJs, staticResult)["markdown"])
    }

    @Test
    fun bridge_assemblesChunksAndIgnoresForeignToken() {
        var got: String? = null
        val bridge = PageDumpBridge("abcdef01") { got = it }
        bridge.chunk("abcdef01", 1, "world")
        bridge.chunk("ffffffff", 0, "NOPE")
        bridge.begin("ffffffff", 1)
        assertNull(got)
        bridge.begin("abcdef01", 2)
        assertNull(got)
        bridge.begin("abcdef01", 2)
        bridge.chunk("abcdef01", 0, "hello ")
        assertEquals("hello world", got)
    }

    @Test
    fun bridge_rejectsOversizedChunk() {
        var got: String? = null
        val bridge = PageDumpBridge("abcdef01") { got = it }
        bridge.begin("abcdef01", 1)
        bridge.chunk("abcdef01", 0, "x".repeat(60_000))
        assertNull(got)
    }

    @Test
    fun pageDumpScript_embedsTokenAndRejectsUnsafeToken() {
        val script = pageDumpScript("abc123ef")
        assertTrue(script.contains("abc123ef"))
        assertTrue(script.contains("NezumiFetch.begin"))
        assertTrue(script.contains("350000"))
        try {
            pageDumpScript("abc\"1234")
            throw AssertionError("unsafe token was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid_dump_token") == true)
        }
    }
}
