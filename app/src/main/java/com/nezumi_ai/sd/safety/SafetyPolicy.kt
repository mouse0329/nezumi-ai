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
    // 2026-08-16 実データ81枚(生成画像)での検証結果を踏まえて再設定:
    //   ・NSFWスコア分布は平均0.875と高く偏っており、閾値0.55だと
    //     境界ケース(NSFW=0.34〜0.38, argmax上はSFW側)を見逃すことが判明。
    //   ・0.30を下回るのは明確に安全な画像(NSFW=0.04, 0.22)のみだったため、
    //     BLUR閾値を0.55→0.30に引き下げ、argmaxではなくスコア自体で判定する。
    //   ・NSFL は学習データが少なくモデルカード自身が精度向上の余地を認めているため、
    //     NSFWよりやや保守的な閾値(低め)にして見逃しを防ぐ。
    private const val NSFW_BLOCK_THRESHOLD = 0.85f
    private const val NSFW_BLUR_THRESHOLD  = 0.30f
    private const val NSFL_BLOCK_THRESHOLD = 0.75f
    private const val NSFL_BLUR_THRESHOLD  = 0.45f

    fun evaluateClassifierXs(result: ImageSafetyClassifierResult): Verdict {
        val nsfwVerdict = when {
            result.nsfwScore >= NSFW_BLOCK_THRESHOLD -> Verdict.BLOCK
            result.nsfwScore >= NSFW_BLUR_THRESHOLD  -> Verdict.BLUR
            else                                      -> Verdict.ALLOW
        }
        val nsflVerdict = when {
            result.nsflScore >= NSFL_BLOCK_THRESHOLD -> Verdict.BLOCK
            result.nsflScore >= NSFL_BLUR_THRESHOLD  -> Verdict.BLUR
            else                                      -> Verdict.ALLOW
        }
        // 2軸のうち、より厳しい方の判定を採用(論理和)
        return maxOf(nsfwVerdict, nsflVerdict, compareBy { it.ordinal })
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
     * Open NSFW と image-safety-classifier-xs、両方の判定結果を統合する。
     * どちらか一方でも BLOCK/BLUR と判定されればその結果を採用する(OR結合)。
     * これにより Open NSFW 単体ではカバーできない暴力・グロ表現(NSFL)を補完する。
     */
    fun combine(a: Verdict, b: Verdict): Verdict = maxOf(a, b, compareBy { it.ordinal })
}
