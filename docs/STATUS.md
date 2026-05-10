# nezumi-ai 開発ステータス

**更新日**: 2025-01-XX

---

## 実装済み機能

### コア機能
- [x] llama.cpp統合 (GGUF対応)
- [x] チャット画面 (ストリーミング表示)
- [x] チャット履歴管理 (Room DB)
- [x] セッション作成・削除
- [x] 画像入力対応 (カメラ/ギャラリー)

### 画像生成
- [x] LocalDreamModule (MNN/QNN)
- [x] QNN NPUバックエンド (Snapdragon)
- [x] MNN CPU/OpenCLフォールバック
- [x] 画像生成専用画面
- [x] 進捗表示
- [x] キャンセル機能

### ツールシステム
- [x] ツールコール基本実装
- [x] generateImage ツール
- [x] ユーザー承認フロー
- [x] setAlarm ツール
- [x] toggleFlashlight ツール
- [ ] webSearch ツール (実装中)
- [ ] getWeather ツール (実装中)

### UI/UX
- [x] Material3 デザイン
- [x] ダークモード対応
- [x] ナビゲーションドロワー
- [x] セッション一覧
- [x] メッセージ表示 (テキスト/画像)
- [x] ツール実行結果カード

---

## 開発中機能

### Phase 1 (現在)
- [ ] チャット履歴検索
- [ ] メッセージ編集・削除
- [ ] セッションエクスポート/インポート
- [ ] 設定画面改善

### Phase 2
- [ ] Web検索統合
- [ ] 天気情報取得
- [ ] カレンダー連携
- [ ] Gmail連携
- [ ] SwitchBot連携

### Phase 3
- [ ] RAG (Retrieval-Augmented Generation)
- [ ] 音声入力/出力
- [ ] LoRA対応
- [ ] モデル量子化最適化

---

## 既知の問題

### 高優先度
- [ ] 長時間推論時のメモリリーク
- [ ] QNNバックエンド初期化失敗時のクラッシュ
- [ ] 画像生成キャンセル後の再生成失敗

### 中優先度
- [ ] ダークモードでの一部UI色調整
- [ ] 大量メッセージ時のスクロールパフォーマンス
- [ ] モデル切り替え時の遅延

### 低優先度
- [ ] 一部端末でのキーボード表示遅延
- [ ] セッション削除時のアニメーション

---

## パフォーマンス

### 現在の測定値 (Pixel 8a)

| 指標 | 目標 | 実測 | ステータス |
|-----|------|------|-----------|
| 起動時間 | < 3秒 | ~2秒 | ✅ |
| 初回推論 (E2B) | < 5秒 | ~4秒 | ✅ |
| 初回推論 (E4B) | < 10秒 | ~8秒 | ✅ |
| メモリ (E2B) | < 3GB | ~2.5GB | ✅ |
| メモリ (E4B) | < 5GB | ~4GB | ✅ |
| 画像生成 (QNN) | < 10秒 | ~7秒 | ✅ |
| 画像生成 (MNN) | < 60秒 | ~45秒 | ✅ |

---

## テスト状況

### 単体テスト
- [ ] Repository層
- [ ] ViewModel層
- [ ] ToolExecutor

### 統合テスト
- [ ] チャットフロー
- [ ] 画像生成フロー
- [ ] ツールコールフロー

### 端末互換性テスト
- [x] Pixel 8a (Android 14)
- [x] Pixel 9 エミュレータ (Android 15)
- [ ] Snapdragon 8 Gen 2端末
- [ ] Snapdragon 8 Gen 3端末
- [ ] MediaTek端末

---

## リリース計画

### v0.1.0 (Alpha) - 完了
- 基本チャット機能
- llama.cpp統合

### v0.2.0 (Alpha) - 完了
- 画像生成機能
- LocalDream統合

### v0.3.0 (Beta) - 現在
- ツールシステム
- UI/UX改善

### v1.0.0 (Stable) - 予定
- 全機能安定化
- パフォーマンス最適化
- ドキュメント整備

---

## 貢献ガイドライン

### 開発環境
- Android Studio Ladybug | 2024.2.1+
- Kotlin 2.1.0
- Gradle 8.11.1
- NDK 26.x

### ブランチ戦略
- `main` - 安定版
- `develop` - 開発版
- `feature/*` - 機能開発
- `fix/*` - バグ修正

### コミットメッセージ
```
feat: 新機能追加
fix: バグ修正
docs: ドキュメント更新
refactor: リファクタリング
test: テスト追加
chore: ビルド・設定変更
```

---

## 参考資料

- [ARCHITECTURE.md](ARCHITECTURE.md) - アーキテクチャ詳細
- [MNN_MIGRATION_PLAN.md](MNN_MIGRATION_PLAN.md) - MNN/QNN移行計画
- [README.md](../README.md) - プロジェクト概要
