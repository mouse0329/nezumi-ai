package com.nezumi_ai.voicevox

/**
 * VOICEVOX CORE 合成の共通インターフェース。
 *
 * Android は既存の VoicevoxManager (Java API 0.16.4) をラップする。
 * iOS は公式 xcframework (voicevox_core-ios-xcframework-cpu-0.16.4) の C API を
 * Objective-C++ から呼ぶ実装を後で差し込む。
 *
 * 方針:
 * - VOICEVOX Engine はライセンス上採用しない。CORE API のみ。
 * - まず Android と同じ 0.16.4 で揃える。0.17.1 への上げは別タスク。
 * - 完成基準はテキスト → WAV bytes の合成と再生・中断。
 */
interface VoicevoxSynthesizer {

    fun isAvailable(): Boolean

    /** 選択中の styleId でテキストを合成する。失敗時は null。 */
    suspend fun synthesize(text: String): ByteArray?

    fun currentStyleId(): Int

    fun setStyleId(styleId: Int)

    /** 生成音声に付与すべきクレジット文字列。 */
    fun currentCredit(): String

    fun release()

    companion object {
        /** Android AAR / iOS xcframework で揃えるバージョン。 */
        const val CORE_VERSION = "0.16.4"

        /** 既定話者: ずんだもん / ノーマル。 */
        const val DEFAULT_STYLE_ID = 3
    }
}
