# nezumi-ai iOS 移行計画

最終更新: 2026-08-24

## 1. 目的

nezumi-ai の Android 版を維持しながら、iOS 版を段階的に実装する。
単純な Android コードの移植ではなく、Kotlin Multiplatform (KMP) を共有ロジックの基盤として利用し、iOS 固有機能は Swift / Objective-C++ / iOS SDK 側に分離する。

現在のリポジトリでは `:shared` KMP モジュールが既に作成され、`androidLibrary` / `iosArm64` / `iosSimulatorArm64` をターゲットとしている。commonMain には推論ストリーム処理、Thinking 復旧、Gemma 4 Thinking parser、文字列拡張などの純粋 Kotlin ロジックが移設済み。

## 2. 基本方針

- Android 版を壊さず、iOS 対応を段階的に追加する。
- Android Framework 依存コードを commonMain に無理に持ち込まない。
- ネットワーク・ドメイン・プロンプト・ストリーム処理は可能な限り KMP で共有する。
- Room は iOS で直接利用せず、SQLDelight を中心に共通 DB 層を再構成する。
- Android Keystore / SharedPreferences は iOS の Keychain / UserDefaults 等に置換する。
- llama.cpp / MNN / VOICEVOX は既存 C/C++ コアを最大限再利用し、JNI の代わりに iOS 向け C-ABI / Objective-C++ / Swift ブリッジを用意する。
- LiteRT-LM + Qualcomm QNN の Android/NPU 経路は iOS へそのまま移植せず、iOS ではまず GGUF + CPU/Metal を標準経路とする。
- UI は既存 Android UI と iOS UI の責務を分離しつつ、共有状態・UseCase・Repository を KMP 化する。Compose Multiplatform で全面統一する場合は別途評価する。

## 3. 現状

### 完了

- `:shared` KMP モジュール新設
- `iosArm64` / `iosSimulatorArm64` ターゲット追加
- `InferenceStreamProtocol` の commonMain 移設
- `ThinkingLeakSalvage` の commonMain 移設
- `Gemma4ThinkingParser` の Android 依存除去・commonMain 移設
- `StringExtensions` の commonMain 移設
- ChatViewModel の責務分割を開始
- Prompt building / media I/O / WakeLock / session-title 周辺を UseCase / Provider / Coordinator に分離

### 未完了

- `TextTokenEstimator` の JVM API 依存除去
- クラウド推論エンジンの KMP 化
- Repository / Room の KMP 化
- KeyStore / 設定管理の iOS 実装
- GGUF llama.cpp の iOS ブリッジ
- Nezumi Kiln (MNN) の iOS ブリッジ
- VOICEVOX CORE の iOS ブリッジ
- iOS UI
- iOS のファイル・メディア・権限・共有機能
- iOS 実機での統合テスト

## 4. 移行対象と実装方針

| 領域 | Android | iOS 方針 | 優先度 |
|---|---|---|---|
| ドメインモデル / DTO | Kotlin | commonMain | P0 |
| Prompt / Parser | Kotlin | commonMain | P0 |
| クラウド推論 | OkHttp | Ktor Client + commonMain | P0 |
| MCP | Android/Kotlin | Ktor + commonMain、iOS transport を追加 | P1 |
| ChatViewModel | Android Framework 依存 | Coordinator / UseCase / State を commonMain 化 | P0 |
| DB | Room | SQLDelight | P0 |
| API Key | Android Keystore | Keychain actual | P1 |
| 設定 | SharedPreferences | UserDefaults actual | P1 |
| GGUF | llama.cpp + JNI | llama.cpp + C-ABI/ObjC++/Swift bridge | P0 |
| MNN / Nezumi Kiln | C++ + JNI | C++ + ObjC++/Swift bridge | P1 |
| LiteRT-LM / QNN | Android NPU | iOS では初期対象外。GGUF 経路を優先 | P2 |
| VOICEVOX | Android AAR | iOS xcframework + bridge | P2 |
| UI | Compose / Android | SwiftUI を第一候補 | P0 |
| ドキュメント変換 | Apache POI / PDFBox | iOS/KMP 対応ライブラリを再選定 | P2 |

## 5. 実装フェーズ

### Phase 0: KMP 基盤の安定化

- [ ] `:shared` の Android / iOS コンパイルを macOS で確認
- [ ] `TextTokenEstimator` を commonMain 対応にする
- [ ] commonMain / androidMain / iosMain の責務境界を確定
- [ ] CI に Android + iOS Simulator コンパイルを追加

### Phase 1: クラウド・ドメイン層

- [ ] OkHttp 依存を Ktor Client に置換
- [ ] Claude / Gemini / OpenAI 互換 / LM Studio / Ollama を commonMain 化
- [ ] SSE / NDJSON ストリーミング処理を共通化
- [ ] MCP HTTP/SSE transport を commonMain 化
- [ ] Cloud API key store を expect/actual 化
- [ ] Android 側の既存クラウド推論回帰テスト

### Phase 2: DB / Repository / ChatViewModel

- [ ] Room Entity / DAO のスキーマを SQLDelight に移行
- [ ] 既存 DB から SQLDelight へのデータ移行戦略を実装
- [ ] Session / Message / Preset / Memory / Alarm 等の repository を commonMain 化
- [ ] ChatViewModel の残りの Android API 依存を Coordinator / Platform Provider に分離
- [ ] Android / iOS 共通の状態モデルを確定

### Phase 3: GGUF / llama.cpp

- [ ] llama.cpp の iOS ビルドを作成
- [ ] Android JNI と共有する C++ コア境界を確定
- [ ] iOS C-ABI または Objective-C++ bridge を実装
- [ ] KMP 側から iOS engine を呼び出す interface / actual を追加
- [ ] CPU 推論を最初の完成基準とする
- [ ] Metal GPU 最適化は CPU 経路の安定後に実施
- [ ] Gemma 4 を含む GGUF モデルでストリーミング・Thinking・Tool Calling を検証

### Phase 4: Nezumi Kiln / MNN

- [ ] MNN の iOS ビルド成果物を用意
- [ ] `mnn-sd-engine` の Android JNI 依存を iOS bridge から分離
- [ ] C++ API を iOS から呼び出せる Objective-C++ / Swift 層を作成
- [ ] SD1.5 txt2img を最初の完成基準とする
- [ ] img2img を追加
- [ ] SDXL を追加検証

### Phase 5: VOICEVOX

- [ ] 使用中の VOICEVOX CORE バージョンと iOS xcframework を一致させる
- [ ] iOS C API bridge を実装
- [ ] `PlatformTtsPlayer` の iOS actual を追加
- [ ] 音声合成・再生・中断を実機で検証

### Phase 6: iOS UI

- [ ] SwiftUI アプリシェルを作成
- [ ] shared framework を Xcode から組み込む
- [ ] Chat state / session / streaming state を iOS UI に接続
- [ ] チャット画面を実装
- [ ] モデル選択画面を実装
- [ ] 設定 / API key / MCP 管理画面を実装
- [ ] 添付ファイル・画像入力を実装
- [ ] iOS 権限モデルに合わせてカメラ・マイク・写真アクセスを実装

## 6. iOS での機能差

### 標準実装として維持するもの

- ローカルチャット
- GGUF 推論
- クラウド推論
- チャット履歴
- MCP
- 画像入力
- ストリーミング表示
- Thinking 表示 / 復旧
- ツールコールの共通プロトコル

### iOS 固有実装が必要なもの

- Keychain
- UserDefaults
- ファイルアクセス / Files app
- 写真・カメラ・マイク権限
- 通知・タイマー等の iOS API
- Metal / GPU 最適化
- Audio session
- SwiftUI UI

### Android の仕様をそのまま持ち込まないもの

- Android WakeLock
- Android AlarmManager
- Android Keystore
- SharedPreferences
- JNI-only API
- Qualcomm QNN / Snapdragon NPU 前提の処理

## 7. 完成条件

以下を満たした時点で「iOS 初期版」とする。

1. Xcode からビルド可能な iOS アプリが存在する。
2. SwiftUI のチャット画面から `:shared` の共通ロジックを利用できる。
3. GGUF モデルを iOS 実機でロードし、CPU 推論で回答をストリーミング表示できる。
4. クラウド推論を少なくとも 1 プロバイダー利用できる。
5. Session / Message の保存と復元ができる。
6. API key を Keychain に保存できる。
7. Android の既存ビルド・主要機能を維持できる。
8. iOS Simulator と実機の両方で主要な回帰テストを実施する。

## 8. リスクと注意点

- Kotlin/Native と Android/JVM では利用可能な標準 API が異なるため、commonMain 化は「コードを移動するだけ」にしない。
- llama.cpp / MNN / VOICEVOX はネイティブブリッジが主要な移植ポイントになる。C++ コアそのものを不用意に fork しない。
- iOS のバックグラウンド実行制約は Android と異なるため、常時推論・長時間処理を Android と同じライフサイクルで設計しない。
- App Store 配布を想定する場合、モデルサイズ、組み込みモデル、ライセンス、暗号化輸出規制、第三者 SDK の利用条件をリリース前に確認する。
- 初期版では「Android と完全同一機能」を目標にせず、GGUF + クラウド + チャット履歴を優先して完成させる。

## 9. 参照ドキュメント

- `docs/ios/nezumi-ai-kmp-foundation.md`
- `docs/ios/nezumi-ai-kmp-progress-summary.md`
- `docs/ios/nezumi-ai-kmp-report.md`
- `docs/ios/chatviewmodel-split-plan.md`
- `docs/ios/compose-unification-execution-plan.md`
- `docs/ios/ui-compose-migration-plan.md`
- `docs/ARCHITECTURE.md`

## 10. 次に着手する作業

最初の実装単位は **Phase 1: クラウド・ドメイン層** とする。理由は Android / iOS の双方で共有効果が大きく、ネイティブ推論エンジンよりもリスクが低いためである。

ただし iOS 実機側の早期検証を止めないため、Phase 0 の macOS ビルド確認と SwiftUI の最小アプリシェルを並行して進める。
