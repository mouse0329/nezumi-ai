package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.document.DocumentConversionManager
import com.nezumi_ai.presentation.viewmodel.platform.PlatformSdModelPathResolver
import com.nezumi_ai.sd.SdScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5: generate_image / convert_md_to_document の引数パース（ViewModel から切り出し）。
 */
class ImageToolInvokerTest {

    private val invoker = ImageToolInvoker(
        object : PlatformSdModelPathResolver {
            override fun findAvailableSdModelPath(): String = "/sd/default.mnn"
            override fun resolveSdModelPathByName(modelName: String): String? =
                if (modelName == "default") "/sd/default.mnn" else null
        }
    )

    @Test
    fun parseGenerateImageArgs_nullWhenPromptMissing() {
        assertNull(invoker.parseGenerateImageArgs(emptyMap(), 20, 7f))
        assertNull(invoker.parseGenerateImageArgs(mapOf("prompt" to "  "), 20, 7f))
    }

    @Test
    fun parseGenerateImageArgs_normalizesSizeStepsCfgAndAliases() {
        val parsed = invoker.parseGenerateImageArgs(
            mapOf(
                "prompt" to " a cat ",
                "negative_prompt" to "blur",
                "model_name" to "default",
                "width" to 300,
                "height" to 700,
                "steps" to 99, // coerce to 50
                "cfg_scale" to 8.5f,
                "seed" to 42L,
                "scheduler" to "euler"
            ),
            defaultSteps = 20,
            defaultCfg = 7f
        )
        assertNotNull(parsed)
        assertEquals("a cat", parsed!!.prompt)
        assertEquals("blur", parsed.negativePrompt)
        assertEquals("default", parsed.requestedModelName)
        assertEquals(256, parsed.width) // nearest of 256/512/768
        assertEquals(768, parsed.height)
        assertEquals(50, parsed.steps)
        assertEquals(8.5f, parsed.cfg, 0.001f)
        assertEquals(42L, parsed.seed)
        assertEquals(SdScheduler.EULER, parsed.scheduler)
    }

    @Test
    fun parseGenerateImageArgs_usesDefaultsWhenOptionalMissing() {
        val parsed = invoker.parseGenerateImageArgs(
            mapOf("prompt" to "hi"),
            defaultSteps = 25,
            defaultCfg = 6.5f
        )!!
        assertEquals(256, parsed.width)
        assertEquals(256, parsed.height)
        assertEquals(25, parsed.steps)
        assertEquals(6.5f, parsed.cfg, 0.001f)
        assertEquals(-1L, parsed.seed)
        assertNull(parsed.requestedModelName)
    }

    @Test
    fun parseConvertMd_missingMarkdown() {
        val (args, err) = invoker.parseConvertMdToDocumentArgs(mapOf("format" to "pdf"))
        assertNull(args)
        assertNotNull(err)
        assertFalse(err!!.success)
        assertEquals("missing_markdown", err.payload["error"])
    }

    @Test
    fun parseConvertMd_invalidFormat() {
        val (args, err) = invoker.parseConvertMdToDocumentArgs(
            mapOf("markdown" to "# hi", "format" to "txt")
        )
        assertNull(args)
        assertEquals("invalid_format", err!!.payload["error"])
    }

    @Test
    fun parseConvertMd_successAndPayloadsOmitMarkdownFromModel() {
        val (args, err) = invoker.parseConvertMdToDocumentArgs(
            mapOf(
                "markdown" to "# Title\nbody",
                "format" to "docx",
                "fileName" to "report"
            )
        )
        assertNull(err)
        assertNotNull(args)
        assertEquals(DocumentConversionManager.TargetFormat.DOCX, args!!.format)
        assertEquals("report.docx", args.displayName)

        val (full, model) = invoker.buildConvertMdPayloads(args)
        assertEquals(true, full["success"])
        assertTrue(full.containsKey("markdown"))
        assertFalse(model.containsKey("markdown"))
        assertEquals("# Title\nbody".length, model["markdownLength"])
    }

    @Test
    fun resolveSdModelPathForToolArg_defaultAndNamed() {
        assertEquals("/sd/default.mnn", invoker.resolveSdModelPathForToolArg(null))
        assertEquals("/sd/default.mnn", invoker.resolveSdModelPathForToolArg("default"))
        assertNull(invoker.resolveSdModelPathForToolArg("missing"))
    }

    @Test
    fun snapImageDimension_nearestAllowed() {
        assertEquals(256, ImageToolInvoker.snapImageDimension(200))
        assertEquals(512, ImageToolInvoker.snapImageDimension(400))
        assertEquals(768, ImageToolInvoker.snapImageDimension(900))
    }
}
