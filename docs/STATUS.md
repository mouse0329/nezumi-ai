# nezumi-ai 開発ステータス

**更新日**: 2026-08-11
**現在のバージョン**: v2.3.0

---

## 実装済み機能

### コア機能
- [x] llama.cpp統合 (GGUF対応)
- [x] LiteRT-LM統合 (TFLite / NPUアクセラレーション対応)
- [x] クラウド推論エンジン統合 (Claude / Gemini / OpenAI互換API / LM Studio / Ollama)
- [x] チャット画面 (ストリーミング表示)
- [x] チャット履歴管理 (Room DB)
- [x] セッション作成・削除・ピン留め
- [x] セッション分岐対応
- [x] チャット履歴検索（`HistorySearchModal`）
- [x] マルチモーダル入力 (画像/動画/音声/テキストファイル)
- [x] シークレットモード (履歴を残さないセッション)
- [x] 多言語UI (日本語 / 英語)

### 画像生成 (Nezumi Kiln)
- [x] 独自MNNベース画像生成エンジン
- [x] OpenCL/CPUフォールバック
- [x] SD1.5, img2img対応
- [x] SDXL試験対応
- [x] 画像生成専用画面
- [x] 生成前確認ダイアログ（モデル・ステップ数変更可）
- [x] 進捗表示・キャンセル機能

### ツールシステム
- [x] ツールコール基本実装
- [x] インラインツールコールカード表示
- [x] `image_generation` ツール（ユーザー承認フロー付き）
- [x] `alarm` / `timer` / `flashlight` / `time` / `battery` ツール
- [x] `memory` / `memory_save` ツール（`MemoryTextEmbedder`によるONNXベースの埋め込み検索、RAGの中核部分は実装済み）
- [x] `web_search` / `web_fetch` ツール
- [x] `convert_md_to_document` ツール（Markdown→Word/Excel/PDF）
- [x] MCPクライアント（Streamable HTTP / SSE、プリセットごとの紐付け）
- [ ] `calendar` ツール（実装済みだがプリセット画面では無効化中）
- [ ] `gmail` / `switchbot` / `app_launch` ツール（定数定義のみ、未公開）

### プリセット機能
- [x] プリセットの作成・編集・削除・並び替え
- [x] プリセットごとのモデル・システムプロンプト・ツール・MCPサーバー設定
- [x] プリセットごとのメモリ機能ON/OFF

### UI/UX
- [x] Material3 デザイン（Jetpack Compose）
- [x] ダークモード対応
- [x] ナビゲーションドロワー（プリセット・シークレットモード・履歴）
- [x] セッション一覧
- [x] メッセージ表示 (テキスト/画像/動画/音声)
- [x] メッセージ編集・削除（`MessageRepository`経由、プロンプト取り消し・再生成含む）
- [x] ツール実行結果カード

### 音声
- [x] VOICEVOX音声読み上げ（標準話者: ずんだもん / ノーマル）
- [x] 音声モデル・OpenJTalk辞書のダウンロード機構（進捗表示・中止対応）

---

## 開発中・未公開の機能

- [ ] `calendar` ツールの一般公開
- [ ] `gmail` / `switchbot` / `app_launch` ツールの実装完了・公開
- [ ] セッションエクスポート/インポート
- [ ] LoRA対応

---

## パフォーマンス目標

| 指標 | 目標値 |
|-----|--------|
| 起動時間 | < 3秒 |
| 初回推論時間（Gemma 4 2B） | < 3秒 |
| 初回推論時間（Gemma 4 4B） | < 8秒 |
| ピークメモリ（2B） | < 3GB |
| ピークメモリ（4B） | < 5GB |

クラウド推論エンジン使用時は選択したプロバイダー・ネットワーク環境に依存するため対象外です。実機での定量測定値は今後この節に追記します。

---

## テスト状況

### 単体テスト（実装済み、`app/src/test/`）
- `GgufInferenceEngineTest` / `InferenceConfigTest` / `PromptBuilderTest`
- `Gemma4ThinkingParserTest` / `GgufToolCallParserSegmentTest` / `ThinkingLeakSalvageTest`
- `ModelFileManagerValidationTest`
- `MnnSdModuleTest` / `LocalDreamModuleTest`
- `ChatViewModelTitleTest` / `SettingsHelperTest`
- `MemoryEmbedderFallbackTest`

### インストルメンテーションテスト（実装済み、`app/src/androidTest/`）
- `MnnSdProbeInstrumentedTest`
- `OptimizationIntegrationTest`

### 未着手領域
- [ ] Repository層の網羅的なテスト
- [ ] クラウド推論エンジン（`CloudEngineFactory`等）のテスト
- [ ] MCPクライアントのテスト
- [ ] E2Eレベルのチャットフロー・ツールコールフローテスト

---

## リリース履歴

詳細は [`docs/release-notes/`](release-notes/) を参照してください。

- **v2.3.0**（現在） - クラウド推論エンジン対応、インラインツールコールカード、ドキュメント変換ツール、多言語対応の強化
- **v2.2.2以前** - 詳細は [GitHub Releases](https://github.com/mouse0329/nezumi-ai/releases) を参照

---

## 貢献ガイドライン

### 開発環境
- Android Studio（最新の安定版を推奨）
- JDK 21
- Kotlin 2.3.20
- Gradle 9.3.1
- NDK 30.0.14904198

### ブランチ戦略
- `main` - 開発・リリース双方の主軸ブランチ

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
- [MNN_SD_ENGINE_PLAN.md](MNN_SD_ENGINE_PLAN.md) - Nezumi Kiln（画像生成エンジン）計画
- [MCP_CLIENT.md](MCP_CLIENT.md) - MCPクライアント仕様
- [release-notes/](release-notes/) - リリースノート一覧
- [README.md](../README.md) - プロジェクト概要
