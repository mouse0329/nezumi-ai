package com.nezumi_ai.sd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MnnSdModuleTest {

    private val module = MnnSdModule()

    @Test
    fun resolveModelDir_findsUnetInSubdirectory() {
        val root = Files.createTempDirectory("mnn_sd_test").toFile()
        val nested = File(root, "pack/model").apply { mkdirs() }
        File(nested, "unet.mnn").writeText("stub")

        assertEquals(nested.absolutePath, module.resolveModelDir(root)?.absolutePath)

        root.deleteRecursively()
    }

    @Test
    fun resolveModelDir_returnsNullWhenMissing() {
        val root = Files.createTempDirectory("mnn_sd_missing").toFile()
        assertNull(module.resolveModelDir(root))
        root.deleteRecursively()
    }
}
