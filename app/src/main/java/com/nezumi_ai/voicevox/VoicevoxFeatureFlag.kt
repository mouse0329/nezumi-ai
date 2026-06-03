package com.nezumi_ai.voicevox

/**
 * VOICEVOX 機能の有効/無効を制御するフラグ。
 *
 * false の場合:
 * - voicevoxcore AAR および libvoicevox_onnxruntime.so をロードしない
 * - UI から音声読み上げ機能を非表示にする
 * - VoicevoxManager はスタブとして動作する
 *
 * これにより Android 15+ の 16KB ページサイズデバイスで
 * 4KB アライン版 ORT 1.17.3 の dlopen 失敗を回避できる。
 */
object VoicevoxFeatureFlag {
    /**
     * VOICEVOX 機能を有効化する場合は true に設定。
     * 
     * true にする際は以下も必要:
     * 1. app/build.gradle.kts の excludes と implementation コメントを削除
     * 2. 16KB アライン対応版 ONNX Runtime (1.18.0+) にアップグレード
     */
    const val ENABLED = false
}
