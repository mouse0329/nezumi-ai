package com.nezumi_ai.sd

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 画像生成 (Stable Diffusion / MNN) モデル zip の一括インポート。
 *
 * 対象フォーマット:
 *   - CuteYukiMix 形式: unet.mnn / clip.mnn / vae_decoder.mnn / tokenizer.json (+ 任意で model.json)
 *   - xororz 形式:      unet.mnn / clip.mnn / vae_decoder.mnn / pos_emb.bin / token_emb.bin
 *                        (+ 任意で tokenizer.json / model.json)
 *   - 各 mnn は clip_v2.mnn / clip_fp16.mnn / unet_asym_block32.mnn / vae_decoder_fp16.mnn 等の別名も許容。
 *
 * 展開先: {filesDir}/sd_models/<derivedName>/
 *   - zip の中に「1 段だけのフォルダ」がある場合はそれを剥がして直下に展開する。
 *   - 既存フォルダがある場合はサフィックスを付けて衝突回避する。
 */
object SdModelImporter {

    private const val TAG = "SdModelImporter"

    private val UNET_NAMES = setOf("unet.mnn", "unet_asym_block32.mnn", "unet_min.bin")
    private val CLIP_NAMES = setOf("clip.mnn", "clip_v2.mnn", "clip_fp16.mnn")
    private val VAE_NAMES = setOf("vae_decoder.mnn", "vae_decoder_fp16.mnn", "vae_decoder_min.bin")

    data class ImportedSdModel(val dir: File, val displayName: String)

    fun importFromUri(context: Context, uri: Uri): Result<ImportedSdModel> = runCatching {
        val displayName = queryDisplayName(context, uri) ?: "sd_model.zip"
        if (!displayName.lowercase().endsWith(".zip")) {
            throw IllegalArgumentException(".zip ファイルのみ追加できます (指定: $displayName)")
        }

        val baseStem = sanitizeStem(displayName.substringBeforeLast('.'))
        val sdModelsRoot = File(context.filesDir, "sd_models").apply { mkdirs() }
        var destDir = File(sdModelsRoot, baseStem)
        if (destDir.exists()) {
            destDir = File(sdModelsRoot, "${baseStem}_${System.currentTimeMillis()}")
        }
        destDir.mkdirs()

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("zip ファイルを開けませんでした")

        try {
            ZipInputStream(input).use { zin ->
                extractZip(zin, destDir)
            }
        } catch (t: Throwable) {
            destDir.deleteRecursively()
            throw t
        }

        flattenSingleNestedDir(destDir)

        val verdict = validate(destDir)
        if (!verdict.first) {
            destDir.deleteRecursively()
            throw IllegalArgumentException("画像生成モデルとして必要なファイルが揃っていません: ${verdict.second}")
        }

        val display = readModelJsonName(destDir) ?: destDir.name
        Log.i(TAG, "Imported SD model: dir=${destDir.absolutePath}, name=$display")
        ImportedSdModel(destDir, display)
    }

    private fun extractZip(zin: ZipInputStream, destDir: File) {
        val destPath = destDir.canonicalPath
        var entry = zin.nextEntry
        val buffer = ByteArray(64 * 1024)
        while (entry != null) {
            val outFile = File(destDir, entry.name).canonicalFile
            if (!outFile.path.startsWith(destPath)) {
                throw java.util.zip.ZipException("zip エントリが展開先の外を指しています: ${entry.name}")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    var read: Int
                    while (zin.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }

    private fun flattenSingleNestedDir(destDir: File) {
        var iterations = 0
        while (iterations < 3) {
            val children = destDir.listFiles()?.toList().orEmpty()
            if (children.size != 1 || !children[0].isDirectory) return
            val inner = children[0]
            inner.listFiles()?.forEach { f ->
                val moved = File(destDir, f.name)
                if (!f.renameTo(moved)) {
                    if (f.isDirectory) f.copyRecursively(moved, overwrite = true) else f.copyTo(moved, overwrite = true)
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
            inner.deleteRecursively()
            iterations++
        }
    }

    fun validate(dir: File): Pair<Boolean, String> {
        val names = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val hasUnet = UNET_NAMES.any { it in names }
        val hasClip = CLIP_NAMES.any { it in names }
        val hasVae = VAE_NAMES.any { it in names }
        val hasTokenizer = "tokenizer.json" in names
        val hasEmbeddings = "token_emb.bin" in names && "pos_emb.bin" in names
        val hasModelJson = "model.json" in names

        if (!hasUnet) return false to "unet.mnn / unet_asym_block32.mnn が見つかりません"
        if (!hasClip) return false to "clip.mnn / clip_v2.mnn / clip_fp16.mnn が見つかりません"
        if (!hasVae) return false to "vae_decoder.mnn / vae_decoder_fp16.mnn が見つかりません"

        if (!hasTokenizer && !hasEmbeddings && !hasModelJson) {
            return false to "tokenizer.json も pos_emb.bin/token_emb.bin も見つかりません (どちらか一方が必要)"
        }
        return true to "OK"
    }

    private fun readModelJsonName(dir: File): String? {
        val f = File(dir, "model.json")
        if (!f.exists()) return null
        return runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            listOf("display_name", "name", "base").firstNotNullOfOrNull { key ->
                obj.optString(key).takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun sanitizeStem(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isBlank()) "sd_model_${System.currentTimeMillis()}" else cleaned
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx)
            }
        }
        return name
    }
}
