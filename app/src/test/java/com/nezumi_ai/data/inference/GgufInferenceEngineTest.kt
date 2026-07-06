package com.nezumi_ai.data.inference

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class GgufInferenceEngineTest {

    @Test
    fun gpt2ModelsUseConservativeNativeSettings() {
        val settings = GgufInferenceEngine.resolveNativeGenerationSettings(
            modelPath = "/models/tiny-gpt2.gguf",
            config = InferenceConfig()
        )

        assertEquals(32, settings.batchSize)
        assertEquals(32, settings.ubatchSize)
        assertFalse(settings.flashAttentionEnabled)
        assertFalse(settings.contextShiftEnabled)
    }

    @Test
    fun tryClearKvCacheWithInferenceLock_skipsWhenInferenceMutexIsHeld() = runBlocking {
        val mutex = Mutex(locked = true)
        val cleared = AtomicBoolean(false)

        val didClear = GgufInferenceEngine.tryClearKvCacheWithInferenceLock(mutex) {
            cleared.set(true)
        }

        assertFalse(didClear)
        assertFalse(cleared.get())
    }

    @Test
    fun tryClearKvCacheWithInferenceLock_allowsClearWhenUnlocked() = runBlocking {
        val mutex = Mutex()
        val cleared = AtomicBoolean(false)

        val didClear = GgufInferenceEngine.tryClearKvCacheWithInferenceLock(mutex) {
            cleared.set(true)
        }

        assertTrue(didClear)
        assertTrue(cleared.get())
    }
}
