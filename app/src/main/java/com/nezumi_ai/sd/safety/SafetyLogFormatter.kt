package com.nezumi_ai.sd.safety

/** Formats safety scores for platform logging without recording prompts or image data. */
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
