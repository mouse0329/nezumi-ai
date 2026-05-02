package com.nezumi_ai.presentation.ui.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHelperTest {
    @Test
    fun inferenceEngineForModel_usesLiteRtForImportedTaskFiles() {
        assertEquals(
            "LiteRT-LM",
            SettingsHelper.inferenceEngineForModel("/data/user/0/com.nezumi_ai/files/models/imported/custom.task")
        )
    }

    @Test
    fun inferenceEngineForModel_usesLiteRtForImportedLiteRtLmFiles() {
        assertEquals(
            "LiteRT-LM",
            SettingsHelper.inferenceEngineForModel("/data/user/0/com.nezumi_ai/files/models/imported/custom.litertlm")
        )
    }

    @Test
    fun inferenceEngineForModel_usesLlamaCppOnlyForGgufFileNames() {
        assertEquals(
            "llama.cpp",
            SettingsHelper.inferenceEngineForModel("/data/user/0/com.nezumi_ai/files/models/imported/custom.gguf")
        )
    }

    @Test
    fun inferenceEngineForModel_ignoresGgufInParentDirectoryName() {
        assertEquals(
            "LiteRT-LM",
            SettingsHelper.inferenceEngineForModel("/data/user/0/com.nezumi_ai/files/models.gguf/imported/custom.task")
        )
    }

    @Test
    fun importedModelKindLabel_usesFileExtensionOnly() {
        assertEquals(
            "LiteRT-LM (.task)",
            SettingsHelper.importedModelKindLabel("/data/user/0/com.nezumi_ai/files/models.gguf/imported/custom.task")
        )
    }
}
