package com.nezumi_ai.sd

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * SD1.5 MNN モデルディレクトリのファイル名解決。
 * [mnn-sd-engine/src/model_config.cpp] と同じ優先順位。
 *
 * model.json が存在する場合はこのファイルの clip/unet/vae_decoder キーを
 * 優先して使う (xororz / CuteYuki 両対応)。
 */
object SdModelLayout {

    private const val TAG = "SdModelLayout"

    val UNET_CANDIDATES = listOf("unet_asym_block32.mnn", "unet.mnn", "unet_min.bin")
    val CLIP_CANDIDATES = listOf("clip_v2.mnn", "clip_fp16.mnn", "clip.mnn")
    val VAE_DECODER_CANDIDATES = listOf("vae_decoder_fp16.mnn", "vae_decoder.mnn", "vae_decoder_min.bin")
    const val TOKENIZER_FILE = "tokenizer.json"
    const val MODEL_JSON_FILE = "model.json"

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
        val override = readModelJson(modelDir)
        return Resolved(
            modelDir = modelDir,
            unetFile = override?.unet?.takeIf { File(modelDir, it).exists() }
                ?: pickFirst(modelDir, UNET_CANDIDATES),
            clipFile = override?.clip?.takeIf { File(modelDir, it).exists() }
                ?: pickFirst(modelDir, CLIP_CANDIDATES),
            vaeDecoderFile = override?.vaeDecoder?.takeIf { File(modelDir, it).exists() }
                ?: pickFirst(modelDir, VAE_DECODER_CANDIDATES),
            tokenizerFile = override?.tokenizer
                ?.takeIf { File(modelDir, it).exists() }
                ?: TOKENIZER_FILE
        )
    }

    private data class ModelJsonOverride(
        val clip: String?,
        val unet: String?,
        val vaeDecoder: String?,
        val tokenizer: String?
    )

    private fun readModelJson(modelDir: File): ModelJsonOverride? {
        val f = File(modelDir, MODEL_JSON_FILE)
        if (!f.exists()) return null
        return runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            ModelJsonOverride(
                clip = obj.optString("clip").ifBlank { null },
                unet = obj.optString("unet").ifBlank { null },
                vaeDecoder = obj.optString("vae_decoder").ifBlank { null },
                tokenizer = obj.optString("tokenizer").ifBlank { null }
            )
        }.onFailure {
            Log.w(TAG, "model.json の読み込みに失敗: ${f.absolutePath}", it)
        }.getOrNull()
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
