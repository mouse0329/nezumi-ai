package com.nezumi_ai.sd

/**
 * Stable Diffusion scheduler selection shared across UI / metadata / engines.
 *
 * Updated to support the schedulers exposed by the MNN engine patch:
 * - DPM++ 2M
 * - DPM++ 2M Karras
 * - LCM
 * - Euler a
 * - UniPC
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
        nativeValue = 3,
    ),
    DPM_PLUS_PLUS_2M_KARRAS(
        id = "dpmpp_2m_karras",
        displayName = "DPM++ 2M Karras",
        httpValue = "dpm++ 2m karras",
        nativeValue = 4,
    ),
    LCM(
        id = "lcm",
        displayName = "LCM (高速・低steps)",
        httpValue = "lcm",
        nativeValue = 5,
    ),
    EULER_A(
        id = "euler_a",
        displayName = "Euler a (Ancestral)",
        httpValue = "euler_a",
        nativeValue = 6,
    ),
    UNIPC(
        id = "unipc",
        displayName = "UniPC",
        httpValue = "unipc",
        nativeValue = 7,
    );

    companion object {
        val DEFAULT: SdScheduler = DPM_PLUS_PLUS_2M

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
                "dpm++2m", "dpmpp2m", "dpmpp_2m" -> DPM_PLUS_PLUS_2M
                "dpm++2mkarras", "dpmpp2mkarras", "dpmpp_2m_karras", "karras" -> DPM_PLUS_PLUS_2M_KARRAS
                "lcm" -> LCM
                "eulera", "euler_a", "eulera" -> EULER_A
                "unipc", "unipc" -> UNIPC
                else -> DEFAULT
            }
        }

        fun fromNativeValue(value: Int): SdScheduler {
            return when (value) {
                0 -> EULER
                1 -> DDIM
                2 -> DPM
                3 -> DPM_PLUS_PLUS_2M
                4 -> DPM_PLUS_PLUS_2M_KARRAS
                5 -> LCM
                6 -> EULER_A
                7 -> UNIPC
                else -> DEFAULT
            }
        }
    }
}
