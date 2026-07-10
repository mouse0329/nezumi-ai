package com.nezumi_ai.sd

import java.io.File

/**
 * SD1.5 MNN モデルディレクトリのファイル名解決。
 * [mnn-sd-engine/src/model_config.cpp] と同じ優先順位。
 */
object SdModelLayout {

    val UNET_CANDIDATES = listOf("unet_asym_block32.mnn", "unet.mnn", "unet_min.bin")
    val CLIP_CANDIDATES = listOf("clip_v2.mnn", "clip_fp16.mnn", "clip.mnn")
    val VAE_DECODER_CANDIDATES = listOf("vae_decoder_fp16.mnn", "vae_decoder.mnn", "vae_decoder_min.bin")
    const val TOKENIZER_FILE = "tokenizer.json"

    data class Resolved(
        val modelDir: File,
        val unetFile: String?,
        val clipFile: String?,
        val vaeDecoderFile: String?,
        val tokenizerFile: String = TOKENIZER_FILE
    ) {
        fun probeTargets(): List<String> = listOfNotNull(unetFile, clipFile, vaeDecoderFile)
    }

    fun resolve(dir: File): Resolved? {
        val modelDir = findModelDir(dir) ?: return null
        return Resolved(
            modelDir = modelDir,
            unetFile = pickFirst(modelDir, UNET_CANDIDATES),
            clipFile = pickFirst(modelDir, CLIP_CANDIDATES),
            vaeDecoderFile = pickFirst(modelDir, VAE_DECODER_CANDIDATES)
        )
    }

    /** unet マーカーでサブディレクトリを探索（最大 3 階層）。 */
    fun findModelDir(dir: File): File? {
        if (hasUnetMarker(dir)) return dir

        fun search(current: File, depth: Int): File? {
            if (depth > 3) return null
            current.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (hasUnetMarker(sub)) return sub
                search(sub, depth + 1)?.let { return it }
            }
            return null
        }
        return search(dir, 0)
    }

    private fun hasUnetMarker(dir: File): Boolean =
        UNET_CANDIDATES.any { File(dir, it).exists() }

    private fun pickFirst(dir: File, candidates: List<String>): String? =
        candidates.firstOrNull { File(dir, it).exists() }
}
