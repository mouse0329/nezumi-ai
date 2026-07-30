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

    val UNET_CANDIDATES = listOf("unet.mnn", "unet_asym_block32.mnn", "unet_min.bin")
    // SDXL のビルドによっては CLIP-L を "clip1.mnn" と命名する (SDXL は 1/2 のペア)。
    //   SD1.5 の clip.mnn と共存させるため、clip1.mnn を先頭に入れても後方互換は保つ。
    val CLIP_CANDIDATES = listOf("clip1.mnn", "clip_v2.mnn", "clip_fp16.mnn", "clip.mnn")
    // SDXL: CLIP-G (第 2 テキストエンコーダ) の候補。C++ 側 sdxl-support.patch と同じ順序。
    val CLIP2_CANDIDATES = listOf("clip2_fp16.mnn", "clip2.mnn", "text_encoder_2.mnn")
    val VAE_DECODER_CANDIDATES = listOf("vae_decoder_fp16.mnn", "vae_decoder.mnn", "vae_decoder_min.bin")
    // img2img 用 VAE encoder。convert_sd15_to_mnn.py --img2img の出力と完全一致:
    //   vae_encoder_fp16.mnn (+ .weight) / vae_encoder.mnn (fp32 バジョン)
    // このリストは mnn-sd-engine/src/model_config.cpp:pick_vae_encoder_file と同順序にすること。
    val VAE_ENCODER_CANDIDATES = listOf("vae_encoder_fp16.mnn", "vae_encoder.mnn")
    const val TOKENIZER_FILE = "tokenizer.json"
    // SDXL: CLIP-G 用 tokenizer
    const val TOKENIZER2_FILE = "tokenizer_2.json"
    const val MODEL_JSON_FILE = "model.json"
    private const val MAX_SEARCH_DEPTH = 3
    // CLIP-L の埋め込みテーブル。xororz 形式は "token_emb.bin" / "pos_emb.bin"、
    //   SDXL は 1/2 を揃えるため "token_emb1.bin" / "pos_emb1.bin" とするビルドもあるので両方受け入れる。
    private val TOKEN_EMB_CANDIDATES = listOf("token_emb.bin", "token_emb1.bin")
    private val POS_EMB_CANDIDATES = listOf("pos_emb.bin", "pos_emb1.bin")
    // 後方互換用: 既存コードが参照している定数。ファイル存在チェックは候補リスト側で行う。
    private const val TOKEN_EMB_FILE = "token_emb.bin"
    private const val POS_EMB_FILE = "pos_emb.bin"
    // SDXL: CLIP-G 用 埋め込みテーブル
    private const val TOKEN_EMB2_FILE = "token_emb2.bin"
    private const val POS_EMB2_FILE = "pos_emb2.bin"
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
            // token_emb.bin / token_emb1.bin のどちらか + pos_emb.bin / pos_emb1.bin のどちらかがあれば OK。
            val hasEmbeddings = TOKEN_EMB_CANDIDATES.any { File(candidate, it).exists() } &&
                                POS_EMB_CANDIDATES.any { File(candidate, it).exists() }
            if (!hasTokenizer && !hasEmbeddings) {
                return ValidationResult(false, null, "missing tokenizer or embeddings")
            }
            // MNN 新形式の外部ウェイト (.mnn.weight) がある場合、ペアで存在することを確認する。
            //   ユーザの SDXL zip は unet.mnn + unet.mnn.weight など 3.4GB に及ぶ外部ウェイトを伴う。
            //   .weight が欠けているとロード時に native 側でクラッシュするので、ここで先回しに検出する。
            //   img2img: vaeEncoderFile も同じ pair チェックに入れておく (ファイル名食い違いによる島后クラッシュ防止)。
            val missingWeight = listOf(resolved.unetFile, resolved.clipFile, resolved.vaeDecoderFile,
                                       resolved.vaeEncoderFile,
                                       if (resolved.isSdxl) resolved.clip2File else null)
                .filterNotNull()
                .firstOrNull { mnn ->
                    val weight = File(candidate, "$mnn.weight")
                    // .weight が既に取り込まれている (= .mnn 単体で self-contained) パターンもあるので、
                    // .mnn ファイルサイズが十分に大きい (例: 10MB 以上) なら .weight 不要とみなす。
                    val mnnFile = File(candidate, mnn)
                    val looksSelfContained = mnnFile.length() > 10L * 1024L * 1024L
                    !weight.exists() && !looksSelfContained
                }
            if (missingWeight != null) {
                return ValidationResult(false, null, "missing weight for $missingWeight")
            }
            // SDXL の場合は CLIP-G / tokenizer_2 が揃っているか追加検証する。
            //   model.json の "base":"sdxl" もしくは clip2 系ファイルの存在で SDXL とみなす。
            if (resolved.isSdxl) {
                if (resolved.clip2File == null) {
                    return ValidationResult(false, null, "missing clip2 (required for sdxl)")
                }
                val hasTokenizer2 = File(candidate, resolved.tokenizer2File).exists()
                val hasEmbeddings2 = File(candidate, TOKEN_EMB2_FILE).exists() &&
                                     File(candidate, POS_EMB2_FILE).exists()
                if (!hasTokenizer2 && !hasEmbeddings2) {
                    return ValidationResult(false, null, "missing tokenizer2 or embeddings2 (required for sdxl)")
                }
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
        val clip2File = override?.clip2?.takeIf { File(modelDir, it).exists() }
            ?: pickFirst(modelDir, CLIP2_CANDIDATES)
        // img2img: model.json の "vae_encoder" キーがあれば優先し、なければ candidates から自動検出する。
        //   convert_sd15_to_mnn.py --img2img は model.json に "vae_encoder": "vae_encoder_fp16.mnn"
        //   を書き出すので、このパスで百発百中する。
        val vaeEncoderFile = override?.vaeEncoder?.takeIf { File(modelDir, it).exists() }
            ?: pickFirst(modelDir, VAE_ENCODER_CANDIDATES)
        // SDXL 判定:
        //   1) model.json の "base":"sdxl" が最優先 (C++ 側 model_config.cpp と統一)
        //   2) それが無い場合は clip2 系ファイルの存在で自動判定
        val isSdxl = when {
            override?.base?.equals("sdxl", ignoreCase = true) == true -> true
            override?.base?.equals("sd1.5", ignoreCase = true) == true -> false
            else -> clip2File != null
        }
        return Resolved(
            modelDir = modelDir,
            unetFile = override?.unet?.takeIf { File(modelDir, it).exists() }
                ?: pickFirst(modelDir, UNET_CANDIDATES),
            clipFile = override?.clip?.takeIf { File(modelDir, it).exists() }
                ?: pickFirst(modelDir, CLIP_CANDIDATES),
            vaeDecoderFile = override?.vaeDecoder?.takeIf { File(modelDir, it).exists() }
                ?: pickFirst(modelDir, VAE_DECODER_CANDIDATES),
            vaeEncoderFile = vaeEncoderFile,
            tokenizerFile = override?.tokenizer
                ?.takeIf { File(modelDir, it).exists() }
                ?: TOKENIZER_FILE,
            isSdxl = isSdxl,
            clip2File = clip2File,
            tokenizer2File = override?.tokenizer2
                ?.takeIf { File(modelDir, it).exists() }
                ?: TOKENIZER2_FILE
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
        // img2img: null なら vae_encoder 未同梱 (txt2img only)。
        val vaeEncoderFile: String? = null,
        val tokenizerFile: String = TOKENIZER_FILE,
        // --- SDXL only (isSdxl=false のときは無視される) ---
        val isSdxl: Boolean = false,
        val clip2File: String? = null,
        val tokenizer2File: String = TOKENIZER2_FILE
    ) {
        fun probeTargets(): List<String> {
            val base = listOfNotNull(unetFile, clipFile, vaeDecoderFile, vaeEncoderFile)
            return if (isSdxl) base + listOfNotNull(clip2File) else base
        }

        /** img2img に対応しているか (vae_encoder が同梱されているか)。 */
        val supportsImg2img: Boolean get() = vaeEncoderFile != null
    }

    private data class ModelJsonOverride(
        val clip: String?,
        val unet: String?,
        val vaeDecoder: String?,
        val vaeEncoder: String?,
        val tokenizer: String?,
        val base: String?,
        val clip2: String?,
        val tokenizer2: String?
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
                // convert_sd15_to_mnn.py --img2img は model.json に
                //   "vae_encoder": "vae_encoder_fp16.mnn"
                //   "img2img": true
                // を書き出す。本当の single source of truth はこのキー。
                vaeEncoder = obj.optString("vae_encoder").ifBlank { null },
                tokenizer = obj.optString("tokenizer").ifBlank { null },
                base = obj.optString("base").ifBlank { null },
                clip2 = obj.optString("clip2").ifBlank { null },
                tokenizer2 = obj.optString("tokenizer_2").ifBlank {
                    obj.optString("tokenizer2").ifBlank { null }
                }
            )
        }.onFailure {
            Log.w(TAG, "model.json の読み込みに失敗: ${f.absolutePath}", it)
        }.getOrNull()
    }

    /**
     * このモデルディレクトリが SDXL かどうかを、model.json + ファイル配置から判定する。
     * findModelDir で解決できなかった場合は false。
     */
    fun isSdxlModelDir(dir: File): Boolean = resolve(dir)?.isSdxl == true

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
