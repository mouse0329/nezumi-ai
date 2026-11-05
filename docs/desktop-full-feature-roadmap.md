# nezumi-ai デスクトップ完全機能化ロードマップ

更新日: 2026-11-05

## 進捗スナップショット (2026-11-05)

- `:shared`にAndroid/iOSターゲットがあり、Androidアプリから共通化候補のデータモデル、パーサ、Repository、Compose部品をcommonMainへ移設した。共通コードからAndroid/JVM APIへの直接参照を除き、必要な機種依存処理は既存のplatform実装に寄せている。
- Coil 3とKtor Clientへ移行し、OkHttp依存を削除した。AndroidはCIO、iOSはDarwin engineを使う構成。
- Windows/macOS/Linux向けVOICEVOX CORE 0.17.1 Java artifactsとiOS/macOS XCFrameworkアーカイブをリポジトリに配置した。これはSDK素材の確保までであり、Desktop targetへの組み込み・実行確認は未完了。
- **VOICEVOX Engineはライセンス上の理由で対象外。VOICEVOX COREをアプリから直接利用する構成に限定する。**各COREバイナリの配布条件、辞書/モデル等の同梱条件も、実装・配布前に個別確認する。
- 最新のWindows Gradle確認は環境側のロックファイル権限拒否およびGradle daemon接続失敗でコンパイラ起動前に停止した。Kotlinエラー4点は修正済みだが、修正後のcompile成功は確認できていない。Desktop開発着手前にWindowsのM1/M2ゲートを再実行する。

## 0. ゴール

WindowsとmacOSのデスクトップ版で、現在のAndroid版が提供している主要機能を一通り利用できる状態を目指します。

対象OSは次の2つです。

- Windows 11以降
- macOS 14以降、Apple Siliconを第一対象

最終的には、Android版と同じ共通KMPロジックを使いながら、デスクトップ固有のUI・ファイル・プロセス・GPU・VRAM・音声・権限処理をデスクトップ実装へ置き換えます。

画像生成のバックエンドはプラットフォームで分けます。モバイルは既存のMNN/Nezumi Kiln系を維持し、デスクトップはStable Diffusion.cppを標準バックエンドにします。デスクトップのローカルLLMはllama.cppを使い、WindowsではCUDA対応GPUを第一優先として、CUDA/VRAMの状態を確認・制御できるUIを追加します。

### 完成時に提供する機能

- クラウドチャット
- ローカルGGUFチャット（デスクトップはllama.cpp CUDA/CPU）
- ストリーミング応答
- Thinking表示と復旧
- Tool Calling / MCP
- セッション・メッセージ履歴
- プリセット管理
- Memory機能
- Skills機能
- モデル管理・ダウンロード
- 添付ファイル・画像入力
- 画像生成、txt2img、img2img（デスクトップはStable Diffusion.cpp）
- 生成画像の保存・閲覧
- VOICEVOX音声合成・読み上げ
- ログ表示
- APIキー・設定管理
- 文書変換
- ミニアプリ機能
- テーマ・表示設定
- Windows/macOSのファイル・クリップボード・通知連携

## 1. 重要な方針

### 1.1 Android UIをそのままデスクトップへ移植しない

Android版はFragment、RecyclerView、Android resource、Intent、ActivityResult、Android Viewに結合しています。これらをデスクトップへ直接移植するのではなく、次の3層へ分離します。

```text
commonMain
  ドメインモデル / UseCase / Repository interface / State / Parser / Protocol

composeApp
  Compose Multiplatform画面 / ナビゲーション / 表示コンポーネント / Desktop UI

platformMain
  ファイル / DB / Clipboard / Window / Process / Audio / GPU / Secure storage
```

### 1.2 Android版を壊さず段階移行する

デスクトップ版の開発中もAndroid版を継続してビルドできるよう、Android固有実装はすぐに削除せず、Platform interfaceの背後へ移します。

### 1.3 ネイティブ推論はUIより後に接続する

まずクラウドチャットと共通UIを完成させ、その後にローカルGGUF、画像生成、音声合成を接続します。これにより、C++/JNIの遅延でデスクトップUI開発全体が止まらないようにします。

### 1.4 Androidと完全同一ではなく、デスクトップに合わせて再設計する

次のAndroid概念はデスクトップでは置き換えます。

| Android | Desktop |
|---|---|
| Fragment / Navigation XML | Compose Multiplatform navigation state |
| RecyclerView | LazyColumn / LazyVerticalGrid |
| ActivityResult | Compose Desktop file chooser / native dialog |
| Intent共有 | OS open/share process |
| Android Keystore | Windows Credential Manager / macOS Keychain |
| SharedPreferences | DataStore / platform settings file |
| Android Service | Desktop worker / coroutine / child process |
| WakeLock | Desktop power policy、通常は不要 |
| AlarmManager | Scheduled executor / OS scheduler |
| JNI | JVM desktop native binding、または別プロセスIPC |
| Android MediaPlayer | JVM/FFmpeg/native audio backend |
| Chaquopy | Desktop Python subprocess / embedded Python / JVM library |

## 2. 現状と主なギャップ

### 現在あるもの

- `:shared` KMPモジュール
- Android target
- `iosArm64` / `iosSimulatorArm64` target
- commonMainへ移行済みのドメイン・パーサ・プロトコル
- Compose Multiplatform依存
- commonMainへ移設済みの共通UI部品（この更新時点でコンパイル未検証）
- SwiftUIの最小iOSシェル

### まだ必要なもの

- `desktopApp`または`composeApp` Gradle module
- Windows/macOS Desktop target
- Compose Desktop application packaging
- Desktop用Platform interface実装
- Android resourceに依存しない共通テーマ・文字列・アイコン設計
- Desktop用ファイル・DB・Secure storage
- Desktop用ローカル推論バックエンド
- Desktop用画像生成バックエンド
- Desktop用音声合成バックエンド
- Android専用のChaquopy文書変換置き換え
- AndroidのFragment/RecyclerView依存の除去

## 3. 機能パリティの定義

### P0: 最初のデスクトップ版に必須

- 起動・ウィンドウ管理
- クラウドチャット
- ストリーミング表示
- セッション一覧・履歴
- Markdown表示
- Thinking表示
- Tool Calling表示
- MCP HTTP/SSE
- APIキー保存
- 設定・テーマ
- ログ表示
- テキスト・画像添付
- セッション削除・名前変更

### P1: Android版の主要機能として早期に揃える

- ローカルGGUF推論
- モデル管理・ダウンロード
- Memory
- Skills
- プリセット
- 画像生成
- 画像ギャラリー
- VOICEVOX CORE直接連携（VOICEVOX Engineは使わない）
- ファイル保存・エクスポート
- 文書変換

### P2: 完全パリティに必要

- ミニアプリ実行
- Background task
- 自動処理・タイマー
- 高度なGPUバックエンド
- 複数音声エンジン
- 高度な画像・動画閲覧
- OS共有メニュー
- 通知・トレイ常駐
- Windows/macOS固有の最適化

## 4. 到達マイルストーン

| ID | マイルストーン | 完了条件 |
|---|---|---|
| D0 | Desktop module設計 | Windows/macOSのCompose Desktop targetが定義される |
| D1 | Desktop空アプリ起動 | 両OSでウィンドウが開き、shared APIを呼べる |
| D2 | 共通チャットUI | セッション一覧、メッセージ、入力欄、Markdownが動く |
| D3 | クラウドチャット | 1プロバイダーのストリーミング送受信が動く |
| D4 | 履歴・設定 | DB、APIキー、テーマ、ログが動く |
| D5 | MCP/Tool Calling | ツール実行・結果表示・エラー表示が動く |
| D6 | Desktopローカル推論 | Windows/macOSでGGUF CPU推論が動く |
| D7 | モデル管理 | ダウンロード、検証、削除、切替が動く |
| D8 | 画像・文書 | 添付、保存、文書変換、画像生成が動く |
| D9 | 音声 | Voicevox音声合成、再生、停止が動く |
| D10 | ミニアプリ/自動処理 | Android版の主要自動化機能をDesktop workerへ移行 |
| D11 | 完全機能版 | P0/P1/P2の受け入れテストを通過 |

## 5. Phase 0: Desktop moduleを作る

### 5.1 推奨構成

```text
:shared       KMP共通ロジック、Repository、State、Protocol
:desktopApp   Compose Multiplatform Desktopアプリ
:app          Androidアプリ
:iosApp       iOS SwiftUIアプリ
```

将来的にAndroid/iOS/DesktopでUIまで共有する場合は、UIを次のように整理します。

```text
shared/src/commonMain/.../domain
shared/src/commonMain/.../data
shared/src/commonMain/.../ui
shared/src/desktopMain/.../platform
shared/src/androidMain/.../platform
shared/src/iosMain/.../platform
```

ただし、アプリのエントリーポイントとWindow設定は各プラットフォーム側に置きます。

### 5.2 Desktop target

Kotlin MultiplatformのDesktop JVM targetを追加し、最低限以下を用意します。

- Windows x64
- macOS arm64
- 必要に応じてmacOS x64

最初はJVM Desktopとして実装し、ネイティブ推論だけ別バックエンドにします。

### 5.3 D0完了条件

- Windowsで`desktopApp`が起動する
- macOSで`desktopApp`が起動する
- 共通の`NezumiAppState`を参照できる
- Android moduleのビルドに影響しない
- Debug実行と配布用パッケージ作成の入口がある

## 6. Phase 1: Desktop共通UIの基盤

### 6.1 画面構成

AndroidのFragment構成をそのまま移植せず、Desktopでは次のNavigation stateを使います。

```text
AppShell
├── ChatRoute
├── SessionRoute
├── ImageGenerationRoute
├── ModelManagementRoute
├── PresetRoute
├── MemoryRoute
├── SkillsRoute
├── SettingsRoute
├── LogsRoute
└── Help/AboutRoute
```

### 6.2 先に移行するUI

1. `MarkdownText`
2. `StatusBarSpacer`はDesktop用WindowInsets処理へ調整
3. `SvgSpinner`
4. `ThemeModeCard`
5. `InlineToolCallMessageBody`
6. `ToolResultCardContent`
7. `AttachmentOptionsSheet`
8. `SessionListScreen`
9. `HistorySearchModal`
10. `ChatScreen`

### 6.3 リソース方針

Androidの`R.string`、`R.color`、`R.drawable`をcommonMainへ持ち込みません。

以下のいずれかに統一します。

- Kotlinの共通文字列定義 + 将来の多言語リソース層
- Compose Multiplatform Resources
- UIへ表示文字列・色・アイコンを引数で注入

### 6.4 D2完了条件

- Desktopでチャット画面が表示される
- メッセージをLazyColumnで表示できる
- 入力欄と送信ボタンがある
- Markdown/Thinking/Tool結果が同じ画面に表示される
- Session一覧からセッションを切り替えられる
- AndroidのRecyclerView AdapterはDesktopから参照しない

## 7. Phase 2: クラウドチャットを完成させる

### 7.1 共通化対象

- Prompt building
- Cloud inference interface
- Ktor Client
- SSE/NDJSON parser
- Streaming state
- Thinking recovery
- Tool call protocol
- Error model
- Cancellation

### 7.2 Platform差分

| 機能 | Desktop方針 |
|---|---|
| HTTP | Ktor CIOまたは平台対応engine |
| API key | OS secure storage |
| Cancellation | Coroutine Job cancellation |
| Proxy | JVM system proxy / app setting |
| Certificate | JVM/OS trust store |
| Network error | 共通`InferenceError`へ変換 |

### 7.3 D3完了条件

- OpenAI互換、Gemini、Claudeのうち最低1つで送受信
- ストリーミング表示
- 停止ボタン
- 再生成
- 会話タイトル生成
- APIキーの保存と削除
- ネットワークエラー表示

## 8. Phase 3: DB・設定・履歴

### 8.1 DB

Room KMPの共通Entity/DAOを利用し、DB builderだけPlatformごとに実装します。

- Android: Android DB file
- Windows: `%APPDATA%/nezumi-ai/`
- macOS: `~/Library/Application Support/nezumi-ai/`

DB migrationはAndroid版と共通schemaを維持します。

### 8.2 設定

共通の設定モデルを作り、保存先をPlatformごとに分けます。

- テーマ
- 言語
- 選択モデル
- 推論パラメータ
- MCP server
- API endpoint
- 音声設定
- 画像生成設定
- Memory/Skills設定

### 8.3 Secure storage

- Windows: Credential ManagerまたはDPAPIを使うDesktop実装
- macOS: Keychain
- テスト環境: 暗号化された開発用ストア

平文APIキーを設定ファイルへ保存しないことを完了条件にします。

### 8.4 D4完了条件

- アプリ再起動後にセッションが復元される
- テーマ・設定が復元される
- APIキーがOSのsecure storageへ保存される
- DB migrationが成功する
- Windows/macOSで保存先が正しく分離される

## 9. Phase 4: MCP・Tool Calling・Skills・Memory

### 9.1 MCP

共通層:

- MCP server model
- JSON-RPC message
- HTTP/SSE transport
- Tool schema
- Tool call/result state
- Timeout/cancellation

Desktop層:

- 外部MCP server接続
- ローカルMCP process起動
- stdin/stdout transport
- process終了・再起動
- 権限確認

### 9.2 Tool Calling UI

- Running
- Success
- Error
- Truncated
- Tool arguments
- Tool result
- Document save action

`InlineToolCallMessageBody`と`InlineToolCallCard`を共通表示モデルに分離します。コピー、保存、外部リンク、プロセス操作はPlatform callbackにします。

### 9.3 Skills/Memory

- Skill markdownの読み込み
- Skill directory選択
- Skill validation
- Memory injection
- Memory save mode
- セッション単位/全体設定

### 9.4 D5完了条件

- HTTP MCP serverへ接続
- Desktop local process MCPへ接続
- Tool callの進行/成功/失敗を表示
- Skillsを読み込んでPromptへ反映
- Memoryの保存・注入が動く

## 10. Phase 5: ローカルGGUF推論

### 10.1 推奨バックエンド設計

JVM DesktopからC++を直接JNIで呼ぶ方法と、ローカル推論daemonを別プロセスで起動する方法を比較します。

#### 初期推奨: 別プロセスIPC

```text
desktopApp JVM
  └── LocalInferenceClient
        └── localhost IPC / stdin-stdout
              └── nezumi-inference native process
                    └── llama.cpp
```

理由:

- Windows/macOSのJNI差分を小さくできる
- クラッシュがUIプロセスへ波及しにくい
- GPU backendを後で差し替えやすい
- llama.cppのビルドをDesktopアプリから分離できる

#### 後段: direct JNI

性能や配布サイズに明確なメリットが出た場合に、Windows DLL/macOS dylibをJNIで接続します。

### 10.2 推論機能

1. モデルファイル選択
2. GGUF metadata読み込み
3. モデルロード進捗
4. Context設定
5. backend選択（CPU / CUDA / macOS Metal）
6. GPU layer数・VRAM使用量設定
7. CPU/CUDA/Metal推論
8. Token streaming
9. Stop/cancel
10. Thinking表示
11. Tool calling
12. モデルアンロード

### 10.3 Desktop差分

- Windows: llama.cpp DLL、CPU AVX2/AVX512、CUDAを第一優先。CUDA Toolkit/Driverの検出と互換性確認を行う
- macOS: dylib、Apple Silicon CPU/Metal。CUDAは対象外
- Linuxは当初対象外。バックエンド設計が固まった後に追加

### 10.4 D6完了条件

- WindowsでGGUF CPU推論とllama.cpp CUDA推論が動く
- macOS Apple SiliconでGGUF CPU/Metal推論が動く
- 同じ共通streaming stateに接続できる
- 途中停止、再生成、履歴保存が動く
- UIが推論backendを直接参照しない

### 10.5 Desktop GPU/VRAM・メモリ管理

Desktopでは、モデルサイズと複数の推論エンジンがシステムRAM/VRAMを大きく消費します。Androidの固定的な端末メモリ判定を移植せず、実行中のリソースを観測しながらロード・アンロードするDesktop専用の管理層を作ります。

共通層には状態モデルとポリシーだけを置き、GPU列挙・VRAM取得・プロセス情報はPlatform実装に分けます。

```text
DesktopMemoryManager
  ├── SystemMemorySnapshot
  ├── GpuMemorySnapshot
  ├── ModelResidencyPolicy
  ├── InferenceMemoryBudget
  └── Eviction / unload events
```

必須機能:

- システムRAM使用量・空き容量
- GPU名、ドライバー、CUDA version
- VRAM total/free/used
- llama.cppのoffload layer数
- KV cache/contextによるメモリ見積もり
- Stable Diffusion.cppのモデル・latent・作業バッファ使用量
- メモリ予算の設定
- モデルロード前の予測と警告
- 画像生成とLLMを同時実行する場合の排他/共有ポリシー
- 低メモリ時の自動アンロード
- OOM発生時の安全な復帰
- 推論終了後のVRAM解放確認

Desktop UIには、設定画面と推論中の簡易ステータスを追加します。

- GPU backend: CPU / CUDA / Metal
- GPU device選択
- VRAM使用量ゲージ
- GPU layer数
- Context size / KV cache
- 画像生成の解像度・batchによるメモリ見積もり
- メモリ制限超過時の事前警告
- 「LLM優先」「画像生成優先」「自動」のリソースポリシー

完了条件:

- CUDA非対応環境でもCPUへ安全にfallbackする
- VRAM不足でアプリ全体が落ちず、ロードを中止して理由を表示する
- LLMと画像生成の同時実行ポリシーが動く
- Windows/macOSの実機でメモリ使用量が表示される

## 11. Phase 6: モデル管理・ファイル・添付

### 11.1 モデル管理

- Hugging Face等からダウンロード
- resume
- checksum/size検証
- progress
- pause/cancel
- delete
- model directory設定
- 空き容量チェック

### 11.2 Desktop file provider

共通interface例:

```kotlin
interface PlatformFileProvider {
    suspend fun pickFile(filters: List<FileFilter>): SelectedFile?
    suspend fun pickDirectory(): SelectedDirectory?
    suspend fun saveFile(name: String, bytes: ByteArray): SavedFile?
    suspend fun openFile(file: SelectedFile): ByteArray
}
```

Platform実装:

- Windows native file dialog
- macOS NSOpenPanel/NSSavePanel bridge
- Desktop JVM fallback chooser

### 11.3 D7完了条件

- モデルを任意ディレクトリへ保存できる
- 添付ファイルをチャットへ追加できる
- 画像・テキスト・PDF等の取り込みができる
- 保存先をユーザーが選べる
- パスや権限エラーを表示できる

## 12. Phase 7: 画像生成

### 12.1 モバイルとDesktopのバックエンド分離

画像生成はモバイルとDesktopで最適化方針を分けます。モバイルは既存のMNN/Nezumi Kiln系を維持し、DesktopはStable Diffusion.cppを標準バックエンドにします。AndroidのMNN/JNI実装をDesktopへそのまま持ち込まず、共通の画像生成engine interfaceの裏側だけをPlatformごとに差し替えます。

```kotlin
interface ImageGenerationEngine {
    suspend fun generate(request: ImageGenerationRequest): Flow<ImageGenerationEvent>
    suspend fun cancel(requestId: String)
}
```

### 12.2 Desktop backendの順序

1. Stable Diffusion.cppのDesktop CPU backend
2. Windows CUDA backend
3. Windows CUDA/VRAM管理とメモリ予測UI
4. macOS Apple SiliconのMetal backend
5. 必要に応じた外部API fallback

Stable Diffusion.cppのモデルロード、scheduler、sampler、progress、cancel、画像保存をDesktop側の標準経路にします。WindowsとmacOSのGPU backendを同時に完成させる必要はありませんが、共通interfaceは最初から両方を想定します。

### 12.3 機能

- txt2img
- img2img
- seed
- sampler/scheduler
- steps
- CFG
- resolution
- negative prompt
- progress
- cancel
- save image
- image gallery
- safety check

### 12.4 D8完了条件

- DesktopでStable Diffusion.cppのCPU txt2imgが動く
- WindowsでStable Diffusion.cppのCUDA txt2imgが動く
- macOSではCPUまたはMetalへ安全にfallbackできる
- 生成画像を保存・閲覧できる
- Androidと共通のImageGeneration stateを利用できる
- モバイルMNN/Nezumi KilnとDesktop Stable Diffusion.cppを交換可能な構造になっている
- LLMとの同時実行時にメモリ管理ポリシーが機能する

## 13. Phase 8: 文書変換

Android版のChaquopy/MarkItDownに依存せず、Desktopでは次の順に検討します。

### 優先順位

1. JVMライブラリで処理できる形式
2. Desktop Python subprocess
3. 変換helper process
4. 外部変換サービス

### 対象

- PDF
- DOCX
- XLSX
- PPTX
- HTML
- Markdown

### Desktop Python方針

- Pythonバージョンを3.13に固定するか、埋め込み環境を同梱
- virtualenvをアプリごとに管理
- subprocessはタイムアウト・キャンセル可能にする
- 入出力は一時ディレクトリで分離
- 変換失敗を共通`DocumentConversionError`へ変換

## 14. Phase 9: VOICEVOX・音声

### 14.1 共通interface

```kotlin
interface PlatformTts {
    suspend fun synthesize(request: TtsRequest): AudioResult
    suspend fun play(audio: AudioResult)
    fun stop()
}
```

### 14.2 Desktop実装候補

- Voicevox engineを別プロセスで起動
- Voicevox HTTP APIへ接続
- Voicevox COREのWindows/macOS native libraryを後段で接続
- OS標準TTSをfallbackにする

### 14.3 D9完了条件

- テキストから音声を生成
- 再生・停止
- 話者・速度・音量設定
- チャット回答の自動読み上げ
- native engineが無い環境でも明確なエラーまたはfallback

## 15. Phase 10: ミニアプリ・自動処理

AndroidのMiniApp機能は、Desktopでは権限制御を強化して移行します。

### 機能

- MiniApp packageのインストール
- manifest検証
- RPC dispatch
- app storage
- network permission
- process sandbox
- runner UI
- uninstall/update

### Desktop方針

- 信頼されていないコードをJVM内で直接実行しない
- 可能なら別プロセス・権限制限付きで実行
- Windows/macOSのファイルアクセス範囲を明示する
- リソース上限・タイムアウト・強制終了を設ける

### D10完了条件

- 簡単なMiniAppのインストール/実行/停止
- RPC timeout
- 権限確認
- app data削除
- クラッシュしても本体UIが継続

## 16. Phase 11: Desktop UX・配布

### UI/UX

- リサイズ対応
- 最小/最大ウィンドウサイズ
- 複数ウィンドウ
- ドラッグ&ドロップ
- キーボードショートカット
- コンテキストメニュー
- tray/menu bar
- 通知
- ダーク/ライトテーマ
- 高DPI
- スケーリング
- アクセシビリティ

### Windows配布

- `.msi`または`.exe`
- 署名
- Windows Defender検証
- Visual C++ runtime依存の整理
- DLL同梱
- アップデータ

### macOS配布

- `.app`
- `.dmg`
- Apple code signing
- notarization
- hardened runtime
- entitlements
- dylib/framework同梱
- Apple Silicon対応

### D11完了条件

- クリーンなWindows環境でインストール・起動
- クリーンなmacOS環境で起動
- 署名とnotarizationが完了
- アップデートと設定保持が動く
- アンインストール後にユーザーデータを意図通り扱える

## 17. 機能別パリティ表

| Android機能 | Desktop実装 | 優先度 | 完了条件 |
|---|---|---:|---|
| Cloud chat | Ktor Desktop engine | P0 | streaming回答 |
| Local GGUF | native helper/JNI | P1 | CPU推論 |
| Chat history | Room KMP | P0 | 再起動後復元 |
| Preset | common repository | P0 | 作成/編集/適用 |
| Memory | common UseCase | P1 | 保存/注入 |
| Skills | common parser + file provider | P1 | 読込/検証/適用 |
| MCP HTTP/SSE | Ktor | P0 | server接続 |
| MCP local process | Desktop process provider | P1 | 起動/停止 |
| Markdown | Compose common UI | P0 | 表示/選択 |
| Thinking | common state + Compose UI | P0 | streaming表示 |
| Tool calling | common protocol + UI | P0 | result/error |
| Attachments | Desktop file provider | P0 | text/image |
| Image generation | Desktop: Stable Diffusion.cpp / Mobile: MNN・Nezumi Kiln | P1 | txt2img |
| Desktop GPU memory | CUDA/Metal/CPU resource manager | P1 | VRAM/RAM表示・OOM防止 |
| Model manager | Desktop file/network provider | P1 | download/delete |
| Voicevox | process/API then native | P1 | synth/play/stop |
| Document conversion | JVM/Python helper | P1 | PDF/DOCX/XLSX |
| MiniApp | sandboxed process | P2 | install/run/stop |
| Logs | common log model + Desktop viewer | P0 | search/export |
| Settings | DataStore/settings file | P0 | persist |
| API key | Credential Manager/Keychain | P0 | secure storage |
| Notifications | Desktop notification provider | P2 | receive notification |

## 18. テスト計画

### 共通テスト

- Parser
- Prompt building
- Tool call segmentation
- Thinking recovery
- Serialization
- Repository
- Settings normalization
- Model metadata
- Document conversion request
- GPU/VRAM budget calculation
- Model residency and eviction policy

### Desktop JVMテスト

- Path normalization
- File provider
- Process lifecycle
- Download resume
- Secure storage adapter
- Window-independent UI state
- Cancellation/timeout

### Windows実機テスト

- Windows path with spaces
- Unicode user name/path
- Defender/permission restriction
- Model file large than 4GB
- CUDA driver/toolkit mismatch
- GPU unavailable CPU fallback
- VRAM exhaustion and model unload
- File dialog
- Audio output

### macOS実機テスト

- Apple Silicon
- App sandbox/entitlement
- Keychain access
- Metal unavailable fallback
- Stable Diffusion.cpp CPU/Metal fallback
- Unified memory pressure and model eviction
- Files permission
- Gatekeeper/notarized build
- Retina/high-DPI

## 19. CI/CDロードマップ

### Pull Request

- `:shared:compileAndroidMain`
- `:app:compileDebugKotlin`
- `:desktopApp:compileKotlin`
- common unit tests

### Windows runner

- Desktop compile
- Android compile
- Python 3.13 document conversion tests
- optional native backend build
- installer smoke test

### macOS runner

- Desktop compile
- iOS Simulator framework compile
- macOS package build
- code signing only on release branch

### Release candidate

- Windows installer
- macOS signed app
- notarization
- model backend smoke test
- cloud chat smoke test
- history migration test

## 20. 最初に実装するチケット

### T1: `desktopApp` moduleの新設

- Compose Desktop plugin
- Windows/macOS run task
- `Main.kt`
- shared dependency
- 仮のAppShell

### T2: Desktop Platform interface

- FileProvider
- SecureStore
- Clipboard
- ExternalLink
- ProcessRunner
- AudioPlayer
- Notification

### T3: App stateとNavigation

- `DesktopAppState`
- route model
- session state
- settings state
- error state

### T4: Chat画面

- LazyColumn
- message item model
- input field
- send/stop/retry
- Markdown/Thinking/Tool card

### T5: クラウド最小版

- Ktor Desktop engine
- OpenAI互換1 provider
- streaming
- API key保存
- session persistence

### T6: Windows/macOSパッケージ

- run/debug
- package
- clean machine smoke test

### T7: llama.cpp CUDAとDesktopメモリ管理

- CUDA対応llama.cpp backendのビルド
- CUDA Toolkit/Driver/GPU capability検出
- CPU fallback
- VRAM/RAM snapshot
- GPU layer数、context、KV cacheの設定UI
- LLM/画像生成のメモリ予算
- 低メモリ時のアンロードと復帰

### T8: Stable Diffusion.cpp画像生成

- Desktop用Stable Diffusion.cpp adapter
- CPU txt2img
- Windows CUDA txt2img
- macOS CPU/Metal fallback
- sampler/scheduler、seed、steps、CFG、resolution
- progress、cancel、save、gallery
- Mobile MNN/Nezumi Kiln backendとの分離確認

## 21. 主要リスクと対策

| リスク | 対策 |
|---|---|
| Android resourceがcommonMainへ漏れる | ResourceProviderまたはCompose Multiplatform Resourcesに統一 |
| JNI移植が長期化する | native helper processを先に採用 |
| Room KMPがDesktopで詰まる | DB interfaceを先に定義し、SQLite実装を差し替え可能にする |
| Python依存が配布を複雑にする | JVM変換、helper process、optional featureへ分割 |
| 大型モデルで起動/配布が重くなる | モデルを外部配置、ダウンロード管理にする |
| CUDA Toolkit、GPU driver、llama.cpp backendの組み合わせ差 | capability検出、CPU fallback、起動時の互換性診断 |
| LLMと画像生成が同時にVRAMを消費する | DesktopMemoryManagerと明示的な優先度/排他ポリシー |
| Stable Diffusion.cppのモデル形式・sampler差 | adapterで共通request/eventへ変換し、モデル変換を別工程にする |
| macOS notarizationが遅れる | Debug appとrelease signingを別ゲートにする |
| Windows/macOSのファイル権限差 | PlatformFileProviderに閉じ込める |
| Android版の回帰 | Android compile/testを各移行タスクの必須ゲートにする |
| UI全面移行が長期化する | P0チャット画面を先に完成し、設定/補助画面は段階移行 |

## 22. 完成条件

デスクトップ完全機能版は、次を満たした時点とします。

1. WindowsとmacOSで署名可能なアプリを生成できる
2. クラウドチャットがストリーミングで動く
3. セッション・メッセージ・設定が再起動後に復元される
4. APIキーをOS secure storageへ保存できる
5. MCPとTool Callingが動く
6. SkillsとMemoryが動く
7. テキスト・画像・主要文書を添付できる
8. ローカルGGUF推論がWindows/macOSで動く（Windows CUDA、macOS CPU/Metal fallback）
9. モデル管理が動く
10. DesktopでStable Diffusion.cppの画像生成と画像保存が動く
11. LLM/画像生成のRAM・VRAM表示とOOM防止が動く
12. VOICEVOXまたはDesktop音声fallbackが動く
13. 文書変換が動く
14. ミニアプリまたは同等の自動処理を安全に実行できる
15. Android版の主要回帰テストが通る
16. Windows/macOSのクリーン環境でインストール・起動できる

## 23. 現実的な優先順位

最短で使えるデスクトップ版を提供する場合は、次の順にします。

```text
Desktop shell
  ↓
Cloud chat + history + settings
  ↓
MCP + Tool Calling + Skills + Memory
  ↓
Attachments + Markdown + logs
  ↓
GGUF local inference
  ↓
CUDA + VRAM management UI
  ↓
Model manager
  ↓
Stable Diffusion.cpp image generation
  ↓
VOICEVOX
  ↓
Document conversion
  ↓
MiniApp / automation
  ↓
Packaging / signing / notarization
```

この順序なら、C++バックエンドが遅れても、Windows/macOSで使えるクラウドチャット中心のデスクトップ版を先に出せます。その後、ローカル推論・画像生成・音声・文書変換を段階的に追加し、最終的にAndroid版の主要機能へ到達します。
