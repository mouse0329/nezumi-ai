package com.nezumi_ai.sd.safety

/**
 * Open NSFW (ResNet-50) 出力:
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

/**
 * OwenElliott/image-safety-classifier-xs 出力 (softmax済み確率, 合計1.0):
 *   class_names = ["NSFL", "NSFW", "SFW"]  ← モデルカード記載の順序
 * NSFW = 性的/扇情的コンテンツ, NSFL = 暴力・グロテスク表現
 * Open NSFW ではカバーされない NSFL 検出を補完する目的で導入。
 */
data class ImageSafetyClassifierResult(
    val nsflScore: Float,
    val nsfwScore: Float,
    val sfwScore: Float
) {
    val verdict: SafetyResult.Verdict get() = SafetyPolicy.evaluateClassifierXs(this)
}
