# nezumi-ai KMP化プロジェクト 進捗まとめ

最終更新: 2026-11-05

## 最新更新 (2026-11-05)

- commonMainへ移設する対象を拡張し、推論設定/プロンプト、メディア・文書処理、MiniApp manifest、プリセット/Skill、Repository、テーマ/Compose部品、安全判定などを追加。Android固有の安全ログ整形はapp側に残した。
- `LiteRtStructuredPrompt`とMiniApp manifestのJSON処理をkotlinx.serializationへ変更し、`org.json`をcommonMainから排除。共通モデル一覧からAndroid `LocalModel`型依存も除去した。
- Coil 3 + Ktor Clientへ移行し、OkHttp/OkHttp SSE依存を削除。AndroidはCIO、iOSはDarwin engine。
- `ToolCallHistoryRepository`はcommonMainの`CloudToolResultCard`で既存JSON配列を読み取る。コンパイラから報告された型参照エラーと、WebSearch/MCPの3件のKotlinエラーは修正済み。
- **ビルドは未確認。**Windows Gradle Wrapperは`%USERPROFILE%\\.gradle`のlockファイルアクセス拒否で起動できず、リポジトリ内Gradle user homeからGradle 9.3.1を直接起動した試行もdaemon接続エラーで終了した。修正後コンパイルは成功扱いにせず、WindowsでM1/M2を再実行する。
- VOICEVOX Engineはライセンス上採用せず、VOICEVOX CORE APIを直接使う方針に限定。CORE 0.17.1のDesktop Java artifactsとiOS/macOS XCFrameworkアーカイブを取得済みだが、KMP targetへの接続・実機検証は未着手。現在のAndroidアプリ依存は0.16.4。

旧セクションの進捗は当時の記録として残す。上記と矛盾するビルド成功/未着手表記は、2026-11-05時点の状態を優先する。

---

## 1. 全体方針

nezumi-aiをKotlin Multiplatform (KMP) 化し、Android/iOSでコード共有を目指すプロジェクト。開発体制は技術調査ベースで進行中。全体設計は「バックエンドロジック(推論エンジンの抽象化・データ層・クラウド通信)はKMPの恩恵を受けやすいが、UIとネイティブ最適化(NPU)部分は別実装前提」という方針。

### 実現性を再評価した項目（2026-08-17）

当初「対応困難」「新規開発が必要」「対象外」としていた4項目について、ユーザー指摘を受けて実コード精査とWeb検索で裏付けを取り直した結果、**いずれも当初評価より前向きな結果に訂正**した。詳細は「4. 実現性の再評価」を参照。

| 項目 | 当初評価 | 訂正後 |
|---|---|---|
| LiteRT-LM (Gemma 3n) | 対応困難、iOS版では除外 | 低〜中（移植候補。Swift API公式存在） |
| MNN (画像生成) | 中〜高、新規開発必要 | 低〜中（汎用CMakeフォールバック既存） |
| GGUF (llama.cpp) | 中 | 低〜中（llama.rn公式iOSブリッジが参考可能） |
| VOICEVOX (TTS) | 対象外(KMP化しない) | 中（移植候補。公式iOS xcframework配布あり、ただしSwift公式SDKは未整備） |

### 対象外と決定した機能

現時点で「KMP化しない」と確定した機能はない。当初VOICEVOX(TTS)を対象外としていたが、2026-08-17の再評価で移植候補に格上げしたため撤回した（詳細下記）。

### KMP化する領域（優先度順）

| 領域 | 難易度 | 状態 |
|---|---|---|
| KMP土台 (sharedモジュール) | 低 | **完了**（iOS化の第一歩・2026-08-17） |
| クラウド推論エンジン(Claude/Gemini/OpenAI互換等) | 低 | 共通engine実装とKtor Client移行は実施済み。Android CIO / iOS Darwin構成。プラットフォーム別コンパイル・通信回帰は未確認 |
| ChatViewModelの責務分割 | 中 | **進行中**（詳細は下記） |
| Repository/DB (Room) | 中 | 方針: Room をそのまま KMP 化（SQLDelight は採用しない）。未着手 |
| GGUF (llama.cpp) | 低〜中(更新) | 未着手（コアはほぼ流用可、ブリッジのみ新規。取り込み元のllama.rnが公式iOSブリッジ実装(ios/RNLlama.mm等)を持ち参考にできる） |
| 画像生成 (Nezumi Kiln / MNN) | 低〜中(訂正) | 未着手（当初「独自ラッパーが未実装」としていたが誤り。CMakeに汎用フォールバックが既存、コア実装のAndroid依存は最小限） |
| VOICEVOX (TTS) | 中(訂正) | 未着手（当初「対象外」としていたが誤り。公式iOS xcframework配布あり、Kotlin側は高レベルAPIのみ使用。ただしSwift公式SDKが未整備で自前ブリッジが必要） |
| UI (Compose Multiplatform化) | 高 | 調査済み（詳細は下記） |

### KMP土台構築（sharedモジュール新設・2026-08-17 完了）

iOS 化の最初の成果物として、AGP 9 + Kotlin 2.3.20 の新構成に対応した `:shared` モジュール（Kotlin Multiplatform ライブラリ）を新設し、Android / iOS で共有可能な「純粋 Kotlin ロジック（プロンプト構築・パーサ系）」を commonMain へ移設した。

- プラグイン: `org.jetbrains.kotlin.multiplatform` + `com.android.kotlin.multiplatform.library`（AGP 9.0 から従来の `com.android.library` 併用は非互換のため新プラグインを使用）
- ターゲット: `androidLibrary`（compileSdk 37 / minSdk 30）+ `iosArm64` + `iosSimulatorArm64`
- iOS 側は `binaries.framework { baseName = "shared" }` で Xcode 組み込み用 framework を生成可能
- commonMain へ移設したファイル（いずれも Android / JVM 依存ゼロに正規化済み）:
  - `InferenceStreamProtocol`（ストリーム制御マーカーの encode/decode + chunk 分割）
  - `ThinkingLeakSalvage`（Thinking 途中停止時の content 復旧ヘルパー）
  - `Gemma4ThinkingParser`（Gemma 4 シンキング出力の分解・表示テキスト正規化。`BuildConfig.DEBUG` 依存と `android.util.Log` 依存を除去して純粋化）
  - `StringExtensions`（`stripGemmaTokens` / `stripTxtFileBlocks` / `stripVideoBlocks`）
- app モジュールは `implementation(project(":shared"))` で共有モジュールに依存し、元ソースは削除（重複定義なし）
- `TextTokenEstimator` は JVM 依存（`codePointAt` / `Character.getType` / `UnicodeBlock`）を共通 stdlib（`CharCategory`、CJK ブロックの範囲判定）に置き換えて commonMain へ移設。**パッチ適用後のビルド確認待ち**

---

## 2. ChatViewModel責務分割の進捗

### 背景
`ChatViewModel`(5,555行、114メソッド)はAndroid Framework(`Context`, `Bitmap`, `PowerManager`, `MediaCodec`, `SharedPreferences`等)に深く依存しており、このままではcommonMain化できない。8つの機能クラスタ(A〜H)に分解し、`Platform*Provider`インターフェース + UseCase/Coordinatorクラスへの委譲という構成で段階的に切り出す設計とした。

### クラスタ別の状態

| クラスタ | 内容 | 状態 |
|---|---|---|
| **C. プロンプト構築** | `PromptBuildingUseCase`へ移管 | ✅ 完了・ビルド確認済み |
| **A. メディアI/O** | `PlatformMediaLoader` (Android実装) | ✅ 完了・ビルド確認済み |
| **H. 電源管理(WakeLock)** | `PlatformWakeLock` (Android実装) | ✅ 完了・ビルド確認済み |
| **G. セッション/DB(タイトル生成部分)** | `ChatSessionCoordinator`へ移管 | ✅ 静的レビュー完了、**Windowsビルド確認は未実施**。`touchSession`の未配線について確認待ち |
| **B. 音声合成(TTS)** | `PlatformTtsPlayer` | 🔄 **再評価により対象復帰**。`PlatformTtsPlayer`インターフェースは用意済み(Android実装のみ)。VOICEVOX CORE公式iOS xcframeworkの存在を確認したため、iOS実装追加も選択肢に入った |
| **D. コンテキスト圧縮** | `ContextCompressionUseCase` | ❌ 廃止（機能廃止のため対象外） |
| **E. モデル/エンジン管理** | `ModelSessionCoordinator` | 🔶 一部着手（`isGgufEngineModel`, `shouldDeleteLocalModelFileOnLoadError`のみ委譲済み） |
| **F. 画像生成ツール連携** | `ImageToolInvoker` | 🔶 一部着手（SDモデルパス解決のみ委譲済み） |

### 実装の経緯（6コミット、5コミット目までビルド確認済み）

1. `refactor: ChatViewModel 責務分割の基盤を追加` — Platform Providerインターフェース群 + Android実装 + UseCase群を新規ファイルとして追加
2. `refactor: ChatViewModel の各クラスタを UseCase/Platform Provider へ委譲` — ChatViewModel側の呼び出しを新実装に差し替え
3. `refactor: 残りの純粋ロジックを PromptBuildingUseCase へ委譲` — クラスタCの残りメソッドを移管
4. `refactor: 未使用の *Legacy メソッド25件と壊れた suffixPrefixOverlapConservative を削除` — レビューで発見したバグ・未使用コードの除去
5. `fix: coroutines API 修正と未配線コード appendToolDefinitionsIfNeeded の削除` — ビルドエラー2件の修正
6. `refactor: クラスタG (セッション/DB) を ChatSessionCoordinator に統合` — セッション作成・タイトル生成ロジックの一本化（**Windowsビルド未確認**）

**5コミット目まで: `./gradlew assembleDebug` ビルド成功（Windows環境で実機確認済み）。**
**6コミット目（クラスタG）: 静的レビューは完了、Windowsビルド確認は依頼中。ChatViewModelは5,555行→5,115行（440行減、6コミット目時点）。**

### レビュー過程で発見・修正した問題

開発エージェントとの往復レビューで、静的解析により以下を実際に発見・修正した。

1. **バグ**: `suffixPrefixOverlapConservative`が`if (...) 0 else 0`という実装ミスで常に0を返す状態だった。委譲先(`PromptBuildingUseCase`)は正しい実装で、幸い実害はなかったが削除。
2. **未使用コード**: `*Legacy`サフィックス付きの重複メソッドが26件、削除されずに残存していた。全て参照ゼロを確認の上削除。
3. **未配線コード**: `appendToolDefinitionsIfNeeded`という、`suspend`が必要な処理を非suspend関数として定義してしまった未使用メソッドが混入。呼び出し元が存在しないことを確認し削除（本格移管は別タスクとして先送り）。
4. **coroutines API不整合**: `kotlinx-coroutines-core 1.11.0`で`continuation.resume(Unit)`の1引数版が使えず、2引数版(`resume(value, onCancellation)`)への修正が必要だった。
5. **重複ロジックの発見・一本化(クラスタG)**: `maybeUpdateSessionTitleFromUserMessage`と`maybeGenerateSessionTitle`が、判定ロジック(既定名かどうかの比較→タイトル生成→DB更新)がほぼ同一の重複コードだった。`ChatSessionCoordinator.generateTitleIfDefault`に一本化し、`buildSessionTitle(`の直接呼び出しは0件になったことを確認。UI状態(`_sessionTitle.value`)の更新はCoordinator側では行わず、呼び出し元(ChatViewModel)の責務として正しく維持されていることも確認済み。
6. **未配線コード(クラスタG、指摘中)**: `ChatSessionCoordinator.touchSession`が定義されているが、ChatViewModel側から一度も呼ばれていない。実害のあるバグではない（単純な1行委譲でビルドは通る）が、前回指摘した「未配線コードを残さない」方針との一貫性のため、エージェントに使用予定の有無を確認中。

### 今後の進め方

- **直近の課題**: クラスタGの`touchSession`未配線の扱い確定 + Windowsビルド確認（この2点が完了すればクラスタGも完了扱いにできる）
- **次のクラスタ**: E/F(モデル管理・画像生成ツール)の残りメソッドへの着手を推奨。GGUF/MNNの実現性評価が好転したため、続けて着手する価値が上がっている
- クラスタ単位でコミットが積み上がった節目ごとに、Windows環境で`./gradlew assembleDebug`によるビルド確認を挟む運用が機能している。今後もこのサイクルを継続する。

---

## 3. UI面 (Compose Multiplatform化) の調査結果

### 総評
11個のFragmentのうち10個は「`onCreateView`が丸ごと1枚の`ComposeView`を返すだけ」という薄いラッパーで、実質フルCompose画面。唯一複雑な`ChatFragment`(3,618行)も、内部の`MessageAdapter`(RecyclerView)のViewHolderが最終的に`setContent{}`でComposeに委譲する構造。**画面の見た目を作るロジックはほぼ100%既にComposableとして書かれている。**

### 難易度別の内訳

| 難易度 | 画面 | 対応方針 |
|---|---|---|
| 🟢 低 | ImageGen, SessionList, Settings系(4画面), SetupWizard, Help, Logs, License (10画面) | Fragmentの皮を剥いでComposable呼び出しに差し替えるだけ |
| 🟡 中〜高 | ChatFragment + MessageAdapter | RecyclerView→LazyColumn化が必要（最大の作業量） |
| 🟡 中〜高 | Benchmark | 純XML実装（ただし廃止・作り直し予定のため移行対象外） |
| 🟢 低 | ModelManagement, ModelErrorDialog | 訂正: 「純XML」判定は誤り。ModelManagement は7行で ModelSettingsFragment を継承するだけのエイリアス（既に Compose 化済み・実質作業不要）、ModelErrorDialog は XML 未使用の素の Dialog API（Compose 版 AlertDialog への置き換えのみ・小規模） |

### 推奨する進め方（5ステップ、未着手）

1. Navigation基盤の入れ替え（`navigation-compose`導入）
2. 難易度の低い10画面から順にNavHostへ移設
3. `ChatFragment`のComposeView8箇所を統合 → `MessageAdapter`を`LazyColumn`に置き換え
4. 残り3つの純XML画面を新規Compose化
5. ここまで完了して初めて`commonMain`への実移動に着手

**重要**: この第1段階(Android内でのFragment撤去)自体はiOS対応と無関係に単体で価値がある技術的負債解消であり、KMP化を最終的にやらなくても着手して損はない。

---

## 4. 実現性の再評価（2026-08-17）

以下3項目について、ユーザーからの指摘を受けて実コード精査・Web検索で再調査し、評価を訂正した。**「対応が絶望的」とされていた領域はなくなり、iOS化全体の見通しは当初より明るい。**

| 項目 | 訂正内容 |
|---|---|
| **LiteRT-LM (Gemma 3n)** | `LiteRtLmEngine.kt`(1,730行)はSDKの高レベルAPI(`Engine`/`Conversation`等)のみで構成され、`JNIEnv`等の低レベル呼び出しは皆無。LiteRT-LMはSwift API(iOS/macOS)を公式提供しており、同じ`Engine`/`Conversation`概念を共有（Google AI Edge Galleryアプリが実例）。QNNはnezumi-aiが直接依存しているのではなく、Android版SDK内部がNPUバックエンドとして使っているだけ。iOS移植は「Swift版SDKへのexpect/actualラップ」という設計になり、GGUFと似た位置づけの移植候補。ただし`Bitmap`(Android型)を使うマルチモーダル入力部分の置き換えや、細部のAndroid依存(Context等)は未精査 |
| **MNN (画像生成、Nezumi Kiln)** | `mnn-sd-engine/CMakeLists.txt`に`if(ANDROID) ... else() find_library(...)`という汎用フォールバック経路が既存。コア実装(4,855行)のAndroid依存は`mnn_session.cpp`1ファイルのみで、該当箇所も`#ifdef ANDROID`/`#else`で条件分岐済み・非Android向けフォールバックも既に実装されている。MNN本体はiOS公式対応済み(`project/ios`, `MNN.podspec`)なので、MNN_ROOTをiOSビルド成果物に向けてツールチェインを差し替えれば、コード変更なしでビルドが通る可能性が高い。JNIブリッジ(`mnn_sd_jni.cpp`)相当のみObjective-C++/Swiftで新規実装すればよい見込み |
| **GGUF (llama.cpp)** | `app/src/main/cpp/llama_rn/`は[llama.rn](https://github.com/mybigday/llama.rn)（React Native向けllama.cppバインディング）のC++ソースを取り込んだもの。ただし`jsi/`配下のReact Native/JSIブリッジはビルド対象外で、`NezumiRnLlamaJni.cpp`もJSI非経由の独自JNI実装。**React Native自体はnezumi-aiでは使われていない。** llama.rn自体は公式にiOS移植実績を持ち、iOS側ブリッジは`ios/RNLlama.mm`・`ios/RNLlamaJSI.mm`(Objective-C++)として実装されている。同じC++コア層をObjective-C++からどう呼び出すかの実践的な参考例として使える |
| **VOICEVOX (TTS)** | VOICEVOX CORE公式が`voicevox_core-ios-xcframework-cpu-0.16.4.zip`というiOS向けxcframeworkを配布しており、nezumi-aiが使っているAndroid AAR(`voicevoxcore-android-0.16.4.aar`)と**バージョンが一致**している。`VoicevoxManager.kt`(728行)は`jp.hiroshiba.voicevoxcore.blocking`パッケージの高レベルAPI(`Synthesizer`/`OpenJtalk`/`Onnxruntime`/`VoiceModelFile`)のみを使用し、低レベルJNI直叩きの痕跡はない。ただしiOS側はC API+非公式Swiftラッパー(`voicevox_core.swift`)という状態で、公式Swift SDKはまだ整備されていない。別途onnxruntimeのiOS向けxcframeworkも必要。App Store提出時のSwiftSupport関連の既知の問題があるが、静的リンクへの切り替えで対処可能とされている |

再評価の詳細な調査ログは`nezumi-ai-kmp-report.md`に反映済み。

---

## 5. 未着手・今後の論点

- commonMain移設対象は拡張済み。現時点でコンパイル成功は未確認のため、Windows環境を整えて`:shared:compileAndroidMain`と`:app:compileDebugKotlin`を再実行する
- クラウド推論: Ktor依存への置換と一部共通層移行は実施済み。Claude/Gemini/OpenAI互換等の全エンジンをcommonMainへ揃える作業と通信回帰確認は残る
- Room の KMP 化: DB は Room のまま。Entity / DAO / Database を commonMain へ移し、DB ビルダーのみ expect/actual で分ける（スキーマ変更なし）
- UI面のFragment撤去（設計は完了、実装は未着手）
- ChatViewModel分割の残り（クラスタDは廃止。E/F本格着手、クラスタGの`touchSession`確認とビルド確認）
- GGUF/MNN/LiteRT-LMの実際のiOSブリッジ実装 — 今回の再評価で「やりやすそう」と判明したが、まだコードは書かれていない
- VOICEVOX COREのKMP/iOS/Desktop接続。VOICEVOX Engineは採用しない。CORE配布物と音声モデルのライセンス条件を個別に確認する

### 次の一手候補

1. Windows Gradle環境のロック/daemon問題を解決し、`:shared:compileAndroidMain`と`:app:compileDebugKotlin`を実行
2. macOSで`:shared:compileKotlinIosSimulatorArm64`と`:shared:compileKotlinIosArm64`を実行
3. クラスタGの`touchSession`未配線の扱いを確定
4. クラスタE/F(モデル管理・画像生成ツール)の続きに着手
5. 評価が好転したGGUF/MNN/LiteRT-LMについて、実際にiOS向けブリッジのプロトタイプ着手を検討
6. UI面(Fragment撤去)に着手
