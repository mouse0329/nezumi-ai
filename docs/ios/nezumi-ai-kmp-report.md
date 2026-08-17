# nezumi-ai の Kotlin Multiplatform (KMP) 化 技術調査レポート

調査日: 2026-08-17
対象コミット: mouse0329/nezumi-ai (v2.3.0時点)

---

## 1. 現状の規模感

| 項目 | 規模 |
|---|---|
| Kotlinファイル | 211ファイル / 約69,500行 |
| C++/JNI関連ファイル (third_party除く) | 約497ファイル |
| ネイティブ .so (プリビルド含む) | 30個以上 (llama.cpp, MNN, VOICEVOX, QNN等) |
| ビルド構成 | 単一 `app` モジュール（AGP + Gradle、KMP未導入） |

現状は **AGP単一アプリモジュール構成**で、`expect/actual` や `commonMain/androidMain` のようなKMPのモジュール分割は一切行われていません。つまり「KMP化」は既存コードのリファクタリングというより、**新しいモジュール構造への段階的な移設プロジェクト**になります。

---

## 2. レイヤー別のKMP適合度

### 🟢 共有しやすい（commonMainに移せる可能性が高い）

| コンポーネント | 現状 | 理由 |
|---|---|---|
| クラウド推論エンジン群<br>(`data/inference/cloud/*`) | OkHttp直叩き。Claude/Gemini/OpenAI互換/LM Studio/Ollamaの各エンジン + SSEパーサ | ネットワークI/Oのみで、Android APIへの依存が薄い。OkHttpをKtorに置き換えれば大部分がcommonMain化可能 |
| Repository層<br>(`data/repository/*`, 8ファイル) | Room DAOを叩くだけの薄いラッパー | ORMをRoom→SQLDelight（KMP対応）に置き換えれば、Repositoryのロジック自体はほぼそのまま共有可能 |
| プロンプト構築・パーサ系<br>(`PromptBuilder`, `GgufToolCallParser`, `McpToolPromptBuilder`, `Gemma4ThinkingParser`等) | 純粋なテキスト処理ロジック | Android API非依存。ほぼそのままcommonMain化できる |
| MCPクライアント<br>(`data/mcp/*`) | Streamable HTTP / SSE | 通信層をKtorに寄せれば共有可能 |
| ドメインモデル・DTO類 | データクラス中心 | そのまま共有可能 |

### 🟡 部分的に共有可能（インターフェース分離が必要）

| コンポーネント | 現状 | 課題 |
|---|---|---|
| `AIInferenceEngine` インターフェースと `EngineManager` | 各エンジン実装を統括する抽象化層 | 設計自体はexpect/actualの受け皿として優秀。GGUF/LiteRT-LM実装をandroidMain/iosMainに振り分ける構造にしやすい |
| Room Database (19ファイル: Entity/DAO) | Room専用アノテーション | SQLDelightへの移行が前提。スキーマ設計は流用可能だが実装は書き直し |
| 設定・APIキー暗号化<br>(`CloudApiKeyStore`, Android Keystore) | `androidx.security` (EncryptedSharedPreferences) | iOS側はKeychainで代替実装が必要。インターフェースだけcommonMainに置きexpect/actual化 |
| ツールコール群<br>(アラーム/フラッシュライト/バッテリー等) | Android SDK直叩き | プラットフォームAPIが根本的に違うため、機能ごとにexpect/actualが必要。一部(アラーム等)はiOSでは仕様上実現不可/制限あり |

### 🔴 事実上の書き直し・大規模移植が必要

| コンポーネント | 現状 | 課題 |
|---|---|---|
| UI全体<br>(Compose + 一部Fragment/XML, presentation層一式) | Jetpack Compose (Material3) | KMPでもCompose Multiplatformでの共有は理論上可能だが、Material3のAndroid最適化・XML Fragment併存という現状構成だと、iOS向けUIは**別実装（SwiftUI）が現実的**。Compose Multiplatform化するにも大規模書き直しが必要 |
| GGUFエンジン (llama.cpp)<br>(`app/src/main/cpp/llama_rn/*`, 約90ファイル) | **JNI呼び出しは`NezumiRnLlamaJni.cpp`1ファイルに集約**。コアロジック（`llama-*.cpp`, `ggml-*.cpp`）自体はJNI非依存の素のC++ | **朗報、さらに前例あり**: コアC++はプラットフォーム非依存。iOS向けにはJNIブリッジの代わりにC-ABI/Swift-Cブリッジを新規作成すればよく、コア部分の書き直しは不要。加えて`app/src/main/cpp/llama_rn/`は元々[llama.rn](https://github.com/mybigday/llama.rn)（React Native向けllama.cppラッパー）のC++ソース一式を取り込んだもの。ただし`jsi/`配下のReact Native/JSIブリッジはCMakeLists.txtのビルド対象に含まれておらず、`NezumiRnLlamaJni.cpp`もJSI経由ではなく`rn-llama.*`等のC++ラッパー層に直接JNIを書いて接続しているだけで、**React Native自体はnezumi-aiでは使われていない**。llama.rn自体はiOSへの移行実績を持つプロジェクトであり、iOS側のネイティブブリッジ実装（Objective-C++）を参考にできる可能性が高い |
| LiteRT-LM (Gemma 3n, NPU) | `com.google.ai.edge.litertlm:litertlm-android` | **Android専用ライブラリ**。iOS版のLiteRTは提供が限定的/QNN(Snapdragon NPU)はiOSでは動作不可。iOS版では別の推論経路（GGUF/CoreML等）に一本化する設計判断が必要 |
| Nezumi Kiln (画像生成, MNNベース)<br>(`mnn-sd-engine/*`) | `mnn-sd-engine/CMakeLists.txt`に`if(ANDROID) ... else() find_library(...)`という汎用フォールバック経路が既存 | **訂正(2026-08-17再調査)**: 当初「独自ラッパーが未実装で新規開発が必要」としていたが誤り。CMakeの`ANDROID`変数が未定義ならフォールバックで`MNN_ROOT/build`から`libMNN`を探す経路が既に用意されている。コア実装(`src/`, 4,855行)のAndroid依存は`mnn_session.cpp`1ファイルのみで、該当箇所も`#ifdef ANDROID`/`#else`で条件分岐済み・非Android向けフォールバック実装も既存。MNN本体はiOS公式対応済み(`project/ios`, `MNN.podspec`)なので、MNN_ROOTをiOSビルド成果物に向けてツールチェインを差し替えれば、コード変更なしでビルドが通る可能性が高い。JNIブリッジ(`android/jni/mnn_sd_jni.cpp`)のみ別ディレクトリに分離済みで、iOS向けはここだけObjective-C++/Swiftブリッジの新規実装で足りる見込み |
| VOICEVOX音声合成 | `libs/voicevoxcore-android-0.16.4.aar` (Android AAR) | **訂正(2026-08-17再調査)**: 当初「要確認」としていたが確認完了。VOICEVOX CORE公式が`voicevox_core-ios-xcframework-cpu-0.16.4.zip`というiOS向けxcframeworkを配布しており、使用中のAndroid AARとバージョンが一致している。`VoicevoxManager.kt`は高レベルAPI(`Synthesizer`等)のみ使用でJNI直叩きなし。ただしiOS側は公式Swift SDKが未整備（C API + 非公式ラッパー`voicevox_core.swift`頼み）で、自前のブリッジ層とonnxruntimeのiOS版xcframeworkが別途必要 |
| ドキュメント変換<br>(Apache POI, PDFBox-android) | JVM/Android向けライブラリ | 共にAndroid/JVM専用。iOSではKMP対応の代替ライブラリ選定、または軽量な自前実装が必要 |
| Room→SQLDelightのマイグレーション自体 | - | 191行のスキーマ(Entity数: Session/Message/Preset/Memory/Alarm等)を移行するマイグレーション設計・データ移行パスの検討が必要 |

---

## 3. 特筆すべき技術的発見

1. **llama.cpp部分は移植の見通しが良く、公式に参考実装もある**
   `app/src/main/cpp/llama_rn/` 配下でJNIEnvへの直接依存は `NezumiRnLlamaJni.cpp` の1ファイルのみ。コアの推論エンジン（llama.cpp本体 + ggml）はプラットフォーム非依存なC++として書かれているため、**iOS側は新規JNIブリッジではなくC-ABI経由のSwift連携層を書くだけ**で済む可能性が高いです。KMPの`cinterop`機構とも相性が良い構造です。
   
   さらに、この`llama_rn`ディレクトリは元々[llama.rn](https://github.com/mybigday/llama.rn)（React Native向けのllama.cppバインディング）のC++ソースをそのまま取り込んだものです。nezumi-aiでは`jsi/`配下のReact Native/JSIブリッジは使われておらず（`CMakeLists.txt`のビルド対象外、`NezumiRnLlamaJni.cpp`もJSI非経由）、代わりに独自のJNIブリッジを`rn-llama.*`等のC++ラッパー層に直接書いて接続しています。ただし**llama.rn自体は公式にiOS移植実績を持ち**、iOS側のネイティブブリッジは`ios/RNLlama.mm`・`ios/RNLlamaJSI.mm`（Objective-C++）として実装されています。React Native/JSIをそのまま使うわけではありませんが、「同じC++コア層(`rn-llama.*`等)をObjective-C++からどう呼び出しているか」という実装パターンは、nezumi-ai独自のiOSブリッジを書く際の実践的な参考例になります。

2. **MNNは公式にiOS対応しており、独自レイヤーも実は移植しやすい構造だった(訂正)**
   `third_party/MNN`（サードパーティ本体）は `MNN.podspec` や `project/ios` を含み、iOSビルドの実績があるライブラリです。当初、nezumi-ai独自の`mnn-sd-engine`（画像生成のラッパー）はAndroid JNI向けのみで新規開発が必要と評価していましたが、実際にコードを精査したところ誤りでした。CMakeには`if(ANDROID) ... else() find_library(...)`という汎用フォールバックが既に用意されており、コア実装(4,855行)のAndroid依存は`mnn_session.cpp`1ファイルのみ、しかもその箇所も`#ifdef`で条件分岐・非Android向けフォールバックが既に書かれています。iOS向けはMNN_ROOTの向き先とCMakeツールチェインの差し替えが中心で、JNIブリッジ相当のObjective-C++/Swift層を新規に書く以外はコード変更が少なくて済む見込みです。

3. **LiteRT-LM/QNN(NPU)はiOSで実質的に代替不可**
   Snapdragon NPU向けのQNNライブラリ群(`libQnnHtp*.so`)はAndroid端末のハードウェア前提の設計です。iOS版では「Gemma 3n(レガシー)+ NPU高速化」の経路をまるごと落とし、GGUF経由の推論（CPU/Metal GPU）に一本化するのが現実的です。iOSにはApple Neural EngineがありCoreMLという選択肢もありますが、これは実質的に**別エンジンの新規開発**になります。

4. **クラウド推論エンジン群は最も「KMP化のうまみ」が大きい**
   Claude/Gemini/OpenAI互換/LM Studio/OllamaのSSEストリーミング処理はOkHttpベースですが、ロジック自体はプラットフォーム非依存です。ここをKtorClientに置き換えるだけで、大きな書き直しなしにcommonMain化でき、**投資対効果が一番高い領域**です。

---

## 4. 推奨する段階的ロードマップ

KMP化を「全部同時に」進めるのはリスクが高いため、依存の薄い層から段階的に切り出すことを推奨します。

### フェーズ0: 土台づくり（1〜2週間規模の調査+設計）
- `shared` モジュール（commonMain/androidMain/iosMain）の追加、既存`app`モジュールとの共存構成を設計
- Room→SQLDelight移行の設計（スキーマ移行パス含む）
- OkHttp→Ktor移行の設計

### フェーズ1: クラウド推論エンジン層のKMP化（投資対効果:高、リスク:低）
- `data/inference/cloud/*` をKtorベースで `commonMain` に移設
- ここでKMPの開発フロー・CI・テストの型を確立する

### フェーズ2: データ層のKMP化
- SQLDelightでRoom Entityを再設計
- Repository層をcommonMain化

### フェーズ3: GGUFエンジン(llama.cpp)のiOS対応
- `NezumiRnLlamaJni.cpp` に相当するiOS向けブリッジ（C-ABI/Objective-C++）を新規実装
- コアのllama.cpp/ggmlはほぼ流用

### フェーズ4: 画像生成(Nezumi Kiln)のiOS対応
- `mnn-sd-engine` にiOS向けCMake分岐・ブリッジを追加
- MNN本体は公式iOS対応版を利用

### フェーズ5: UI層
- Compose MultiplatformでUIごと共有するか、SwiftUIでiOSネイティブUIを別途実装するかの方針決定
- 現状Fragment/XML併存があるため、まずAndroid側をフルCompose化してから検討すると移行しやすい

### 対象外候補（iOS版では機能を落とす/後回しにする）
- LiteRT-LM / QNN(NPU)経由のGemma 3n推論
- VOICEVOX（訂正: 当初「iOS版ライブラリの提供状況次第」としていたが、公式iOS xcframeworkの存在を確認済み。移植候補に格上げ）
- ドキュメント変換(POI/PDFBox) — iOS向け代替ライブラリの選定が別途必要

---

## 5. まとめ

| 領域 | KMP化の難易度 | 備考 |
|---|---|---|
| クラウド推論 | 低 | 最優先で着手すべき |
| Repository/DB | 中 | SQLDelight移行が前提 |
| GGUF (llama.cpp) | 低〜中 | コアはほぼ流用可、ブリッジのみ新規。llama.rn公式のiOSブリッジ実装(ios/RNLlama.mm等)が参考にできる |
| 画像生成 (MNN) | 低〜中(訂正) | 汎用CMakeフォールバックが既存、コア実装のAndroid依存は最小限。JNIブリッジ相当のみ新規実装 |
| LiteRT-LM (Gemma 3n) | 低〜中(訂正) | 当初「対応困難」としていたが誤り。SDKは高レベルAPIのみで低レベルネイティブ呼び出しなし。Swift API(iOS/macOS)が公式に存在し同じEngine/Conversation概念を共有。QNNはnezumi-aiの直接依存ではなくAndroid版SDK内部の実装詳細 |
| UI | 高 | 事実上の別実装が必要 |
| VOICEVOX | 中(訂正) | 公式iOS xcframework配布あり(バージョン一致)。Swift公式SDK未整備のため自前ブリッジが必要 |
| 文書変換 | 中〜高 | ライブラリ差し替えが必要 |

全体として、**「バックエンドロジック（推論エンジンの抽象化・データ層・クラウド通信）はKMPの恩恵を受けやすいが、UIとネイティブ最適化(NPU)部分は別実装前提」**という設計方針が現実的です。
