package com.nezumi_ai.voicevox

/**
 * iOS 向け VOICEVOX 合成の仮実装。
 *
 * 本番では voicevox_core-ios-xcframework-cpu-0.16.4 の C API を
 * Objective-C++ から呼び、WAV バイト列を返す。
 * 現時点では未接続。
 */
class IosVoicevoxSynthesizer : VoicevoxSynthesizer {

    override fun isAvailable(): Boolean = false

    override suspend fun synthesize(text: String): ByteArray? = null

    override fun currentStyleId(): Int = VoicevoxSynthesizer.DEFAULT_STYLE_ID

    override fun setStyleId(styleId: Int) = Unit

    override fun currentCredit(): String = ""

    override fun release() = Unit
}
