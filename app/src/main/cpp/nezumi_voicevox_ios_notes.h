/**
 * iOS 向け VOICEVOX CORE ブリッジの入口メモ。
 *
 * 方針:
 * - VOICEVOX Engine は採用しない。CORE API のみ。
 * - Android AAR (voicevoxcore-android-0.16.4) と
 *   iOS xcframework (voicevox_core-ios-xcframework-cpu-0.16.4) でバージョンを揃える。
 * - 公式 Swift SDK は未整備のため、C API を Objective-C++ から呼ぶ。
 * - onnxruntime の iOS xcframework も別途必要。
 *
 * 完成基準 (Phase 5):
 * - テキスト → WAV 合成
 * - 再生と中断 (PlatformTtsPlayer の iOS actual)
 * - クレジット表記は VoicevoxLicense を共有
 *
 * 後段:
 * - 0.17.1 への上げ (Desktop / iOS アーカイブは取得済みだが未接続)
 * - Metal 等の高速化は対象外 (CPU xcframework を使う)
 */
#pragma once

#define NEZUMI_VOICEVOX_CORE_VERSION "0.16.4"
