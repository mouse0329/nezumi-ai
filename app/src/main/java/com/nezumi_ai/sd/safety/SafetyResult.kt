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

/**
 * 安全判定の可観測性向けに、数値評価と最終判定を固定形式で整形する。
 * プロンプト、画像パス、画像内容は記録しない。
 */
internal object SafetyLogFormatter {
    fun format(
        openNsfw: SafetyResult,
        classifierXs: ImageSafetyClassifierResult,
        finalVerdict: SafetyResult.Verdict
    ): String = String.format(
        java.util.Locale.ROOT,
        "Safety: scores open_nsfw(normal=%.4f, nsfw=%.4f, verdict=%s), " +
            "classifier_xs(nsfl=%.4f, nsfw=%.4f[ignored], sfw=%.4f, verdict=%s), final=%s",
        openNsfw.normalScore,
        openNsfw.nsfwScore,
        openNsfw.verdict,
        classifierXs.nsflScore,
        classifierXs.nsfwScore,
        classifierXs.sfwScore,
        classifierXs.verdict,
        finalVerdict
    )
}
