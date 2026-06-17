package com.nezumi_ai.sd.safety

/**
 * AdamCodd/vit-base-nsfw-detector 出力:
 *   index 0 = normal スコア
 *   index 1 = nsfw   スコア
 */
data class SafetyResult(
    val normalScore: Float,
    val nsfwScore: Float
) {
    enum class Verdict { ALLOW, BLUR, BLOCK }

    val verdict: Verdict get() = SafetyPolicy.evaluate(this)
}
