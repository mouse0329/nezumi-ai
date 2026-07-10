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
    fun resolveModelDir_findsXororzUnetName() {
        val root = Files.createTempDirectory("mnn_sd_xororz").toFile()
        File(root, "unet_asym_block32.mnn").writeText("stub")

        assertEquals(root.absolutePath, module.resolveModelDir(root)?.absolutePath)

        root.deleteRecursively()
    }

    @Test
    fun sdModelLayout_picksXororzFiles() {
        val root = Files.createTempDirectory("mnn_sd_layout").toFile()
        File(root, "unet_asym_block32.mnn").writeText("u")
        File(root, "clip_v2.mnn").writeText("c")
        File(root, "vae_decoder_fp16.mnn").writeText("v")

        val layout = SdModelLayout.resolve(root)!!
        assertEquals("unet_asym_block32.mnn", layout.unetFile)
        assertEquals("clip_v2.mnn", layout.clipFile)
        assertEquals("vae_decoder_fp16.mnn", layout.vaeDecoderFile)
        assertEquals(listOf("unet_asym_block32.mnn", "clip_v2.mnn", "vae_decoder_fp16.mnn"), layout.probeTargets())

        root.deleteRecursively()
    }

    @Test
    fun resolveModelDir_returnsNullWhenMissing() {
        val root = Files.createTempDirectory("mnn_sd_missing").toFile()
        assertNull(module.resolveModelDir(root))
        root.deleteRecursively()
    }
}
