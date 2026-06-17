package com.nezumi_ai.sd.safety

import com.nezumi_ai.sd.safety.SafetyResult.Verdict

object SafetyPolicy {
    // AdamCodd モデルは softmax 前の raw logits を出力する
    // logit 0.0 = 50%, 0.5 ≈ 62%, 1.0 ≈ 73%, 1.5 ≈ 82%, 2.0 ≈ 88%
    private const val BLOCK_THRESHOLD = 1.0f  // logit > 1.0 → nsfw 73%以上 → BLOCK
    private const val BLUR_THRESHOLD  = 0.0f  // logit > 0.0 → nsfw 50%以上 → BLUR

    fun evaluate(result: SafetyResult): Verdict = when {
        result.nsfwScore >= BLOCK_THRESHOLD -> Verdict.BLOCK
        result.nsfwScore >= BLUR_THRESHOLD  -> Verdict.BLUR
        else                                -> Verdict.ALLOW
    }

    /** AdamCodd モデル出力 FloatArray[2] → SafetyResult */
    fun fromRawOutput(scores: FloatArray): SafetyResult {
        val normal = if (scores.size > 0) scores[0] else 1f
        val nsfw   = if (scores.size > 1) scores[1] else 0f
        return SafetyResult(normalScore = normal, nsfwScore = nsfw)
    }
}
