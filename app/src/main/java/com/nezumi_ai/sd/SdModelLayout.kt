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
    private const val MAX_SEARCH_DEPTH = 3
    private const val TOKEN_EMB_FILE = "token_emb.bin"
    private const val POS_EMB_FILE = "pos_emb.bin"
    private const val LEGACY_QNN_MARKER = "unet.bin"
    private const val LEGACY_QNN_VAE = "vae_decoder.bin"
    private const val LEGACY_QNN_CLIP = "clip.bin"

    // Bug fix:
    //   旧実装は「unet があるだけ」で候補ディレクトリを拾う箇所が複数あり、
    //   tokenizer/ や clip/ のような不完全フォルダまで画像生成モデルとして
    //   一覧に混ざってしまっていた。結果として UI がその壊れた候補を自動選択し、
    //   LocalDreamModule.loadModel() で "Could not find usable model files" が発生。
    //   ここで "実際に読み込み可能な SD MNN モデルか" を一元判定する。
    //
    //   判定条件:
    //     - UNet / CLIP / VAE の 3 点セットが揃う
    //     - tokenizer.json もしくは (token_emb.bin + pos_emb.bin) がある
    //   ※ legacy QNN (.bin) は一覧には出さない（既に廃止のため）。
    data class ValidationResult(
        val isUsable: Boolean,
        val modelDir: File?,
        val reason: String
    )

    fun validate(dir: File): ValidationResult {
        fun validateCandidate(candidate: File): ValidationResult {
            if (!candidate.exists() || !candidate.isDirectory) {
                return ValidationResult(false, null, "not a directory")
            }
            if (File(candidate, LEGACY_QNN_MARKER).exists()) {
                return ValidationResult(false, null, "legacy qnn only")
            }
            val resolved = resolveCandidate(candidate)
            if (resolved.unetFile == null) return ValidationResult(false, null, "missing unet")
            if (resolved.clipFile == null) return ValidationResult(false, null, "missing clip")
            if (resolved.vaeDecoderFile == null) return ValidationResult(false, null, "missing vae")
            val hasTokenizer = File(candidate, resolved.tokenizerFile).exists()
            val hasEmbeddings = File(candidate, TOKEN_EMB_FILE).exists() && File(candidate, POS_EMB_FILE).exists()
            if (!hasTokenizer && !hasEmbeddings) {
                return ValidationResult(false, null, "missing tokenizer or embeddings")
            }
            return ValidationResult(true, candidate, "ok")
        }

        fun search(current: File, depth: Int): ValidationResult {
            val self = validateCandidate(current)
            if (self.isUsable) return self
            if (depth >= MAX_SEARCH_DEPTH) return self
            current.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                val child = search(sub, depth + 1)
                if (child.isUsable) return child
            }
            return self
        }

        return search(dir, 0)
    }

    fun isUsableModelDir(dir: File): Boolean = validate(dir).isUsable

    fun findUsableModelDir(dir: File): File? = validate(dir).modelDir

    fun isLegacyQnnDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        if (File(dir, LEGACY_QNN_MARKER).exists() &&
            File(dir, LEGACY_QNN_VAE).exists() &&
            (File(dir, LEGACY_QNN_CLIP).exists() || File(dir, "clip_v2.mnn").exists())) {
            return true
        }
        if (MAX_SEARCH_DEPTH <= 0) return false
        fun search(current: File, depth: Int): Boolean {
            if (!current.exists() || !current.isDirectory) return false
            if (File(current, LEGACY_QNN_MARKER).exists() &&
                File(current, LEGACY_QNN_VAE).exists() &&
                (File(current, LEGACY_QNN_CLIP).exists() || File(current, "clip_v2.mnn").exists())) {
                return true
            }
            if (depth >= MAX_SEARCH_DEPTH) return false
            current.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (search(sub, depth + 1)) return true
            }
            return false
        }
        return search(dir, 0)
    }

    private fun resolveCandidate(modelDir: File): Resolved {
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

    private fun hasAnyUnetMarker(dir: File): Boolean =
        UNET_CANDIDATES.any { File(dir, it).exists() } || File(dir, LEGACY_QNN_MARKER).exists()

    fun resolve(dir: File): Resolved? {
        val modelDir = findModelDir(dir) ?: return null
        return resolveCandidate(modelDir)
    }

    data class Resolved(
        val modelDir: File,
        val unetFile: String?,
        val clipFile: String?,
        val vaeDecoderFile: String?,
        val tokenizerFile: String = TOKENIZER_FILE
    ) {
        fun probeTargets(): List<String> = listOfNotNull(unetFile, clipFile, vaeDecoderFile)
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

    /** unet 系マーカーでサブディレクトリを探索（最大 3 階層）。 */
    fun findModelDir(dir: File): File? {
        if (hasAnyUnetMarker(dir)) return dir

        fun search(current: File, depth: Int): File? {
            if (depth > MAX_SEARCH_DEPTH) return null
            current.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (hasAnyUnetMarker(sub)) return sub
                search(sub, depth + 1)?.let { return it }
            }
            return null
        }
        return search(dir, 0)
    }

    private fun pickFirst(dir: File, candidates: List<String>): String? =
        candidates.firstOrNull { File(dir, it).exists() }
}
