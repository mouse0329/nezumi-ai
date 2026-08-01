# VOICEVOX 機能について（対応済み）

> **このドキュメントは歴史的経緯の記録です。**
> かつて VOICEVOX 音声読み上げは、同梱 ONNX Runtime が 4KB ページアライメントでビルドされていたため
> Android 15 以降の一部端末で `dlopen` に失敗する問題があり、一時的に無効化していました。
> **この問題は解決済み**で、現在 VOICEVOX は既定で有効です。
> アプリの UI からもランタイム互換性に関する注意書きは削除しています。

## 現在の状態

| 項目 | 状態 |
| --- | --- |
| `VoicevoxFeatureFlag.ENABLED` | `true`（既定で有効） |
| ネイティブランタイム | `libvoicevox_onnxruntime.so`（対応済み。UI での互換性判定・表示は廃止） |
| 標準話者 | ずんだもん / ノーマル（`0.vvm` / styleId `3`） |
| 音声モデルの取得 | `ModelDownloadWorker`（LLM モデルと共通の進捗・通知・中止機構） |
| OpenJTalk 辞書 | 音声モデル取得と同じフローで、未取得のときだけ続けて取得 |

## 音声モデルのダウンロード実装

音声モデルは LLM モデルと同じダウンロード機構に統合されています。

```
ModelSettingsFragment
  └─ startVoicevoxDownload(entry)
       └─ ModelDownloadWorker.enqueueVoicevoxModel(...)   ← WorkManager unique work
            ├─ フェーズ 1: <n>.vvm をダウンロード（進捗を setProgressAsync / 通知へ）
            │    └─ VoicevoxManager.installDownloadedModel(entry, tempFile)
            └─ フェーズ 2: OpenJTalk 辞書（未取得のときのみ）
                 └─ VoicevoxManager.ensureDictionary { downloaded, total -> ... }
```

UI 側は `TAG_VOICEVOX_DOWNLOAD` を購読して進捗バーを描画し、
`ModelDownloadWorker.cancelVoicevoxModel()` で中止できます。

## ライセンス

収録している音声ライブラリと必要なクレジット表記は
[VOICEVOX_TERMS.md](VOICEVOX_TERMS.md) にまとめています。
商用利用が認められていない音声ライブラリ（No.7 / ユーレイちゃん）はカタログから除外しています。

## 参考

- [VOICEVOX 利用規約](https://voicevox.hiroshiba.jp/term/)
- [Android 16KB Page Size Support](https://developer.android.com/guide/practices/page-sizes)
