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
    // "clip1.mnn" は SDXL ビルドの CLIP-L (新命名規則)。
    private val CLIP_NAMES = setOf("clip.mnn", "clip1.mnn", "clip_v2.mnn", "clip_fp16.mnn")
    private val CLIP2_NAMES = setOf("clip2.mnn", "clip2_fp16.mnn", "text_encoder_2.mnn")
    private val VAE_NAMES = setOf("vae_decoder.mnn", "vae_decoder_fp16.mnn", "vae_decoder_min.bin")
    // SDXL 専用の補助ファイル (フォルダに入っていない zip もあるので別途 lookup で使う)
    //   ユーザの SDXL zip は clip1/2 + token_emb1/2 + pos_emb1/2 の 1/2 ペア命名と、
    //   MNN 新形式の外部ウェイト .mnn.weight を伴うので、この両パターンも孤立可能ファイルとして拾う。
    private val SDXL_AUX_NAMES = setOf(
        "clip1.mnn", "clip1.mnn.weight",
        "clip2.mnn", "clip2.mnn.weight",
        "clip2_fp16.mnn", "text_encoder_2.mnn",
        "tokenizer.json", "tokenizer_2.json",
        "token_emb.bin", "pos_emb.bin",
        "token_emb1.bin", "pos_emb1.bin",
        "token_emb2.bin", "pos_emb2.bin",
        "unet.mnn.weight",
        "vae_decoder_fp16.mnn.weight", "vae_decoder.mnn.weight",
        "model.json"
    )

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

        // ★ 完全な一括 try/catch/finally で包む — これまでは flattenSingleNestedDir /
        //   validate の失敗が try の外側にあり、例外時に destDir が残骸として
        //   sd_models/ に残ってしまい (毎失敗ごとに GB 単位で容量リーク)。
        //   さらに openInputStream() の戻り値も use{} で包む (例外時の FD リーク対策)。
        var succeeded = false
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("zip ファイルを開けませんでした")
            input.use { rawIn ->
                ZipInputStream(rawIn).use { zin ->
                    extractZip(zin, destDir)
                }
            }

            flattenSingleNestedDir(destDir)

            val verdict = validate(destDir)
            if (!verdict.first) {
                throw IllegalArgumentException("画像生成モデルとして必要なファイルが揃っていません: ${verdict.second}")
            }

            val display = readModelJsonName(destDir) ?: destDir.name
            Log.i(TAG, "Imported SD model: dir=${destDir.absolutePath}, name=$display")
            succeeded = true
            ImportedSdModel(destDir, display)
        } finally {
            if (!succeeded) {
                // 失敗したときは destDir と、その中に優先展開されたときの一時フォルダを
                // すべて削除する。deleteRecursively() はファイルハンドルが後でクローズする
                // ケースに弱いので、GC を一回させてから二度削除を試みる。
                if (destDir.exists()) {
                    val ok1 = runCatching { destDir.deleteRecursively() }.getOrDefault(false)
                    if (!ok1 || destDir.exists()) {
                        System.gc()
                        runCatching { destDir.deleteRecursively() }
                    }
                    if (destDir.exists()) {
                        Log.w(TAG, "Failed to fully cleanup ${destDir.absolutePath} after import failure; " +
                            "leftover size = ${dirSize(destDir)} bytes")
                    }
                }
            }
        }
    }.also { result ->
        // 失敗した場合、sd_models 直下に残っている「不完全フォルダ」(例: pos_emb2/, 
        // token_emb/, clip_v2/ など) をまとめてクリーンアップする。これは以前の失敗で
        // 残ったゴミも拂える。成功時も同様のスキャンを走らせる (今後のゴミ予防)。
        runCatching { cleanupOrphans(context) }
    }

    /**
     * sd_models/ 直下にある「単体ではモデルとして成立していないフォルダ」を
     * 削除する。pos_emb2 / token_emb2 / clip_v2 / clip2 など、SDXL 分割 zip の
     * 失敗・中断で残るゴミを拾う。 ※ 安全のため、SdModelLayout.validate() で
     * 「usable=true」なフォルダと、登録済みパス (推奨取得元 = PreferencesHelper) は
     * 一切触らない。
     */
    fun cleanupOrphans(context: Context): Int {
        val root = File(context.filesDir, "sd_models")
        if (!root.exists() || !root.isDirectory) return 0
        var removed = 0
        root.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            // モデルとして成立している (SD1.5 or SDXL) ならスキップ。
            if (SdModelLayout.validate(child).isUsable) return@forEach
            // 単一 SDXL 補助ファイルしか入っていない (= SDXL 失敗の残骸) を判定。
            val entries = child.listFiles()?.map { it.name }?.toSet().orEmpty()
            val onlyAux = entries.isNotEmpty() && entries.all { it in SDXL_AUX_NAMES }
            // または空フォルダ。
            val isEmpty = entries.isEmpty()
            if (onlyAux || isEmpty) {
                val size = dirSize(child)
                if (child.deleteRecursively()) {
                    removed++
                    Log.i(TAG, "cleanupOrphans: removed ${child.name} (freed $size bytes)")
                }
            }
        }
        return removed
    }

    private fun dirSize(dir: File): Long =
        runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

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
        val result = SdModelLayout.validate(dir)
        if (result.isUsable) return true to "OK"
        val message = when (result.reason) {
            "missing unet" -> "unet.mnn / unet_asym_block32.mnn が見つかりません"
            "missing clip" -> "clip.mnn / clip_v2.mnn / clip_fp16.mnn が見つかりません"
            "missing vae" -> "vae_decoder.mnn / vae_decoder_fp16.mnn が見つかりません"
            "missing tokenizer or embeddings" -> "tokenizer.json も pos_emb.bin/token_emb.bin も見つかりません (どちらか一方が必要)"
            "missing clip2 (required for sdxl)" -> "SDXL に必要な clip2.mnn / clip2_fp16.mnn / text_encoder_2.mnn が見つかりません"
            "missing tokenizer2 or embeddings2 (required for sdxl)" -> "SDXL に必要な tokenizer_2.json も pos_emb2.bin/token_emb2.bin も見つかりません"
            "legacy qnn only" -> "旧 QNN (.bin) 形式のみです。MNN (.mnn) 形式のモデルを追加してください"
            else -> "画像生成モデルとして必要なファイルが揃っていません (${result.reason})"
        }
        return false to message
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
