package com.nezumi_ai.sd.safety

import com.nezumi_ai.sd.safety.SafetyResult.Verdict

object SafetyPolicy {
    // Open NSFW (ResNet-50) は softmax 済みの確率 [normal, nsfw] を出力する
    private const val BLOCK_THRESHOLD = 0.85f
    private const val BLUR_THRESHOLD  = 0.55f

    fun evaluate(result: SafetyResult): Verdict = when {
        result.nsfwScore >= BLOCK_THRESHOLD -> Verdict.BLOCK
        result.nsfwScore >= BLUR_THRESHOLD  -> Verdict.BLUR
        else                                -> Verdict.ALLOW
    }

    /** Open NSFW モデル出力 FloatArray[2] → SafetyResult */
    fun fromRawOutput(scores: FloatArray): SafetyResult {
        val normal = if (scores.size > 0) scores[0] else 1f
        val nsfw   = if (scores.size > 1) scores[1] else 0f
        return SafetyResult(normalScore = normal, nsfwScore = nsfw)
    }

    // --- image-safety-classifier-xs (NSFL/NSFW/SFW) ---
    // classifier-xs の NSFW 出力はモデル特性による誤検知があるため、
    // 安全判定には使用しない。NSFW値は監査用ログには残すが、判定根拠はNSFLのみとする。
    private const val NSFL_BLOCK_THRESHOLD = 0.75f
    private const val NSFL_BLUR_THRESHOLD  = 0.45f

    fun evaluateClassifierXs(result: ImageSafetyClassifierResult): Verdict {
        return when {
            result.nsflScore >= NSFL_BLOCK_THRESHOLD -> Verdict.BLOCK
            result.nsflScore >= NSFL_BLUR_THRESHOLD  -> Verdict.BLUR
            else                                      -> Verdict.ALLOW
        }
    }

    /**
     * image-safety-classifier-xs の ONNX 出力(softmax済み確率, index順 [NSFL, NSFW, SFW])
     * → ImageSafetyClassifierResult
     */
    fun fromClassifierXsOutput(scores: FloatArray): ImageSafetyClassifierResult {
        val nsfl = if (scores.size > 0) scores[0] else 0f
        val nsfw = if (scores.size > 1) scores[1] else 0f
        val sfw  = if (scores.size > 2) scores[2] else 1f
        return ImageSafetyClassifierResult(nsflScore = nsfl, nsfwScore = nsfw, sfwScore = sfw)
    }

    /**
     * Open NSFW と image-safety-classifier-xs の NSFL 判定を統合する。
     * classifier-xs の NSFW 値は誤検知対策のため、この統合には使用しない。
     * どちらか一方でも BLOCK/BLUR と判定されればその結果を採用する(OR結合)。
     * これにより Open NSFW 単体ではカバーできない暴力・グロ表現(NSFL)を補完する。
     */
    fun combine(a: Verdict, b: Verdict): Verdict = maxOf(a, b, compareBy { it.ordinal })
}
