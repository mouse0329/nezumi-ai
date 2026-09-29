package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.inference.remote.RemoteEngineProcessDiedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSessionCoordinatorTest {

    @Test
    fun genericLlamaInitMessage_isNotMemoryFailure() {
        val error = RuntimeException(
            "LlamaCppContext failed to initialize — invalid model file or insufficient memory"
        )
        assertFalse(ModelSessionCoordinator.isMemoryLoadFailure(error))
    }

    @Test
    fun genericLlamaInitMessageWithNativeDetail_isNotMemoryFailure() {
        val error = RuntimeException(
            "LlamaCppContext failed to initialize — invalid model file or insufficient memory. " +
                "llama.cpp: llama_init_from_model: quantized V cache requires flash_attn to be enabled"
        )
        assertFalse(ModelSessionCoordinator.isMemoryLoadFailure(error))
    }

    @Test
    fun flashAttnRequirement_isNotMemoryFailure() {
        val error = IllegalStateException(
            "quantized V cache requires flash_attn to be enabled"
        )
        assertFalse(ModelSessionCoordinator.isMemoryLoadFailure(error))
    }

    @Test
    fun actualAllocationFailure_isMemoryFailure() {
        val error = RuntimeException("failed to allocate memory for KV cache")
        assertTrue(ModelSessionCoordinator.isMemoryLoadFailure(error))
    }

    @Test
    fun outOfMemoryError_isMemoryFailure() {
        assertTrue(ModelSessionCoordinator.isMemoryLoadFailure(OutOfMemoryError("Java heap")))
    }

    @Test
    fun processDeathLikelyOom_isMemoryFailure() {
        val error = RemoteEngineProcessDiedException("process died", likelyOutOfMemory = true)
        assertTrue(ModelSessionCoordinator.isMemoryLoadFailure(error))
    }

    @Test
    fun genericLlamaInit_doesNotDeleteModelFile() {
        assertFalse(
            ModelSessionCoordinator.shouldDeleteLocalModelFileOnLoadError(
                "LlamaCppContext failed to initialize — invalid model file or insufficient memory",
                RuntimeException("LlamaCppContext failed to initialize — invalid model file or insufficient memory")
            )
        )
    }

    @Test
    fun corruptFile_canDeleteModelFile() {
        assertTrue(
            ModelSessionCoordinator.shouldDeleteLocalModelFileOnLoadError(
                "GGUF file is corrupt",
                null
            )
        )
    }
}
