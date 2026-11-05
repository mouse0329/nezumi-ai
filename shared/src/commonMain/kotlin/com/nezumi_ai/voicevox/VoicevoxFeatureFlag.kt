package com.nezumi_ai.voicevox

/**
 * VOICEVOX 機能の有効/無効を制御するフラグ。
 *
 * false の場合:
 * - voicevoxcore AAR および libvoicevox_onnxruntime.so をロードしない
 * - UI から音声読み上げ機能を非表示にする
 * - VoicevoxManager はスタブとして動作する
 *
 * 通常は true のままで問題ない。デバッグ目的で音声合成まわりを丸ごと切り離したいときだけ
 * false にする。
 */
object VoicevoxFeatureFlag {
    const val ENABLED = true
}
