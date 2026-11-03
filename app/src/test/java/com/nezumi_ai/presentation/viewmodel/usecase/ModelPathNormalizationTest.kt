package com.nezumi_ai.presentation.viewmodel.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Phase 3: モデル ID / パス正規化（ModelSessionCoordinator 拡張分）。
 */
@RunWith(RobolectricTestRunner::class)
class ModelPathNormalizationTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun normalizeModel_preservesCloudIds() {
        assertEquals(
            "cloud:openai/gpt-4o",
            ModelSessionCoordinator.normalizeModel(context, "cloud:openai/gpt-4o")
        )
    }

    @Test
    fun normalizeModel_builtinAliases() {
        assertEquals("Gemma4-2B", ModelSessionCoordinator.normalizeModel(context, "gemma4-2b"))
        assertEquals("Gemma4-4B", ModelSessionCoordinator.normalizeModel(context, "Gemma4-4B"))
        assertEquals("E4B", ModelSessionCoordinator.normalizeModel(context, "Gemma3n-4B"))
        assertEquals("E2B", ModelSessionCoordinator.normalizeModel(context, "e2b"))
    }

    @Test
    fun normalizeModel_unknownFallsBackToGemma42B() {
        assertEquals("Gemma4-2B", ModelSessionCoordinator.normalizeModel(context, "unknown-model"))
    }

    @Test
    fun toEngineModelName_mapsBuiltins() {
        assertEquals("gemma4-2b", ModelSessionCoordinator.toEngineModelName(context, "Gemma4-2B"))
        assertEquals("gemma4-4b", ModelSessionCoordinator.toEngineModelName(context, "Gemma4-4B"))
        assertEquals("gemma-3n-4b", ModelSessionCoordinator.toEngineModelName(context, "E4B"))
        assertEquals("gemma-3n-2b", ModelSessionCoordinator.toEngineModelName(context, "E2B"))
    }

    @Test
    fun toEngineModelName_preservesCloud() {
        assertEquals(
            "cloud:anthropic/claude",
            ModelSessionCoordinator.toEngineModelName(context, "cloud:anthropic/claude")
        )
    }

    @Test
    fun resolveGgufModelPath_findsImportedByFileName() {
        val imported = File(context.filesDir, "models/imported").apply { mkdirs() }
        val f = File(imported, "demo-model.gguf").apply { writeText("gguf-stub") }
        val resolved = ModelSessionCoordinator.resolveGgufModelPath(context, "demo-model.gguf")
        assertEquals(f.absolutePath, resolved)
        assertEquals(
            f.absolutePath,
            ModelSessionCoordinator.normalizeModel(context, "demo-model.gguf")
        )
        assertEquals(
            f.absolutePath,
            ModelSessionCoordinator.toEngineModelName(context, "demo-model.gguf")
        )
    }

    @Test
    fun isGgufEngineModel() {
        assertTrue(ModelSessionCoordinator.isGgufEngineModel("/path/to/x.gguf"))
        assertFalse(ModelSessionCoordinator.isGgufEngineModel("gemma4-2b"))
    }

    @Test
    fun getEngineModelSizeBytes_knownBuiltins() {
        assertEquals(2_400_000_000L, ModelSessionCoordinator.getEngineModelSizeBytes("gemma4-2b"))
        assertEquals(4_800_000_000L, ModelSessionCoordinator.getEngineModelSizeBytes("gemma4-4b"))
    }

    @Test
    fun formatModelErrorDialogMessage_joinsTitleMessageDetails() {
        val text = ModelSessionCoordinator.formatModelErrorDialogMessage(
            title = "Error",
            message = "Could not load",
            details = "path=/x"
        )
        assertEquals("Error\n\nCould not load\n\npath=/x", text)
    }

    @Test
    fun isModelLoadWarningMarker() {
        assertTrue(
            ModelSessionCoordinator.isModelLoadWarningMarker(
                RuntimeException("MEMORY_WARNING_SHOWN")
            )
        )
        assertTrue(
            ModelSessionCoordinator.isModelLoadWarningMarker(
                RuntimeException("CPU_COMPAT_WARNING_SHOWN")
            )
        )
        assertFalse(
            ModelSessionCoordinator.isModelLoadWarningMarker(RuntimeException("other"))
        )
    }
}
