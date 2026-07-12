package com.nezumi_ai.sd

/**
 * Stable Diffusion scheduler selection shared across UI / metadata / engines.
 *
 * Note:
 * - The HTTP-compatible LocalDream path can forward [httpValue] as-is.
 * - The current JNI MNN path only exposes Euler / DDIM / DPM. For schedulers
 *   that are not implemented natively yet (for example DPM++ 2M), [nativeValue]
 *   intentionally falls back to the closest supported solver.
 */
enum class SdScheduler(
    val id: String,
    val displayName: String,
    val httpValue: String,
    val nativeValue: Int,
) {
    EULER(
        id = "euler",
        displayName = "Euler",
        httpValue = "euler",
        nativeValue = 0,
    ),
    DDIM(
        id = "ddim",
        displayName = "DDIM",
        httpValue = "ddim",
        nativeValue = 1,
    ),
    DPM(
        id = "dpm",
        displayName = "DPM",
        httpValue = "dpm",
        nativeValue = 2,
    ),
    DPM_PLUS_PLUS_2M(
        id = "dpmpp_2m",
        displayName = "DPM++ 2M",
        httpValue = "dpm++ 2m",
        nativeValue = 2,
    );

    companion object {
        val DEFAULT: SdScheduler = DPM

        fun fromId(raw: String?): SdScheduler {
            val normalized = raw
                ?.trim()
                ?.lowercase()
                ?.replace("_", "")
                ?.replace("-", "")
                ?.replace(" ", "")
                ?: return DEFAULT

            return when (normalized) {
                "euler" -> EULER
                "ddim" -> DDIM
                "dpm" -> DPM
                "dpm++2m", "dpmpp2m" -> DPM_PLUS_PLUS_2M
                else -> DEFAULT
            }
        }
    }
}
