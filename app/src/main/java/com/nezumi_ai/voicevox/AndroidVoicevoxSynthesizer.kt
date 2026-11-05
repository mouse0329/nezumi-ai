package com.nezumi_ai.voicevox

/**
 * Android 向け VOICEVOX 合成。既存の VoicevoxManager に委譲する。
 *
 * iOS 側は IosVoicevoxSynthesizer が CORE xcframework 経由で同じ契約を満たす。
 */
class AndroidVoicevoxSynthesizer(
    private val manager: VoicevoxManager
) : VoicevoxSynthesizer {

    override fun isAvailable(): Boolean =
        VoicevoxFeatureFlag.ENABLED && manager.isCurrentSelectionReady()

    override suspend fun synthesize(text: String): ByteArray? {
        if (!VoicevoxFeatureFlag.ENABLED) return null
        return manager.synthesize(text)
    }

    override fun currentStyleId(): Int = manager.getSavedStyleId()

    override fun setStyleId(styleId: Int) {
        manager.setSelectedStyleId(styleId)
    }

    override fun currentCredit(): String = manager.currentCredit()

    override fun release() = manager.release()
}
