# ChatViewModel 責務分割案（Compose Multiplatform化の前提整理）

対象: `app/src/main/java/com/nezumi_ai/presentation/viewmodel/ChatViewModel.kt`
現状: 5,555行 / 114メソッド / コンストラクタ引数に`Context`直受け

## 0. 分割の狙い

`ChatViewModel`は「チャットのビジネスロジック」「メッセージのプロンプト構築」「Android固有のI/O(画像/音声/DB/電源管理)」が1クラスに同居しています。これをこのままCompose Multiplatformの`commonMain`に持っていくことはできません。

狙いは、**ChatViewModel本体を`commonMain`に残せる「調整役」まで痩せさせ、Android専用I/Oは全部インターフェースの向こう側に追い出す**ことです。

```
[現状]
ChatViewModel (Android依存だらけ, 5555行, commonMain化不可)

[分割後]
ChatViewModel (commonMain, 状態管理とユースケース呼び出しに専念)
  ├─ 各種 UseCase / Manager (commonMain, ロジックのみ)
  │    └─ Platform*Provider インターフェース経由でOSリソースにアクセス
  └─ androidMain: Platform*ProviderのAndroid実装
       iosMain:   Platform*ProviderのiOS実装（後日）
```

---

## 1. 114メソッドの機能クラスタ分類

grepした全メソッドを機能単位でグルーピングすると、以下の8クラスタに分かれます。

| クラスタ | 代表メソッド | 行数目安 | Android依存度 |
|---|---|---|---|
| **A. メディアI/O** | `loadBitmapFromUri`, `loadAudioBytesFromUri`, `scaleBitmapTo1024`, `saveBitmapToGallery`, `addMediaToMessage`, `removeMediaFromMessage`, `updatePendingMediaPreview` | 中 | 高（`Bitmap`, `ContentResolver`, `MediaStore`, `MediaCodec`） |
| **B. 音声合成(TTS)** | `synthesizeText`, `playAudio`, VOICEVOX関連 | 中 | 高（`VoicevoxManager`は`MyApplication`から取得） |
| **C. プロンプト構築** | `buildPromptFromMessages`, `buildPromptWithCompressedSummary`, `buildPromptWithSessionContext`, `trimPromptToWindow`, `sanitizeMessageContentForPrompt`, `stripThinkSectionsForDisplay`, `extractJsonObject`など | 大 | 低（純粋テキスト処理） |
| **D. コンテキスト圧縮/メモリ** | `compressContextManually`, `requestCompressedContextSummary`, `buildRelevantMemoryBlock`, `buildMemorySearchQuery`, `enqueueMemoryExtraction`, `ensureEmbeddingFilesAvailable` | 中 | 中（Worker/埋め込みDL部分がAndroid依存） |
| **E. モデル/エンジン管理** | `loadModelWithOverlay`, `switchModel`, `preloadActivePresetModel`, `proceedWithModelLoad`, `handleModelLoadIssue`, `refreshContextWindowForModel`, `isGgufEngineModel`, `getEngineModelSizeBytes` | 大 | 中（`ModelManager`経由、一部Context要） |
| **F. 画像生成ツール連携** | `invokeGenerateImageFromTool`, `performGenerateImageFromTool`, `queueGenerateImageFromTool`, `findAvailableSdModelPath`, `resolveSdModelPathByName` | 中 | 中（ファイルパス解決がAndroid依存） |
| **G. セッション/DB操作** | `createAndActivateSession`, `setCurrentSession`, `syncSessionTitleFromDb`, `maybeGenerateSessionTitle`, `ensureValidCurrentSession`、Repository呼び出し全般 | 中 | 低〜中（Repository越しなので本来は疎結合になりうるが、`NezumiAiDatabase.getInstance(appContext)`を直接呼んでいる箇所がある） |
| **H. 画面状態/電源管理/UI調整** | `acquireScreenWakeLock`, `releaseScreenWakeLock`, `startModelLoadingIndicator`, `composeModelLoadingLabel`, 各種`StateFlow`, `sendMessage`, `stopGeneration` | 大 | 高（`PowerManager`）+ 低（StateFlow自体はKMPで問題なし） |

**重要な観察**: CとGの一部（プロンプト構築・セッション操作のロジック）は元々Android非依存で書かれています。ここは分割さえすればほぼそのまま`commonMain`に移動可能です。逆にA・B・Hは構造的にAndroid固有I/Oであり、必ずインターフェース越しにする必要があります。

---

## 2. 分割後のクラス設計

### 2.1 Android固有I/Oを隠す `Platform*Provider` インターフェース群（commonMain定義）

```kotlin
// commonMain
interface PlatformMediaLoader {
    suspend fun loadImageBytes(uriString: String): ByteArray?
    suspend fun loadAudioBytes(uriString: String): ByteArray?
    suspend fun scaleImageTo1024(bytes: ByteArray): ByteArray
    suspend fun saveImageToGallery(bytes: ByteArray, fileName: String): Boolean
}

interface PlatformTtsPlayer {
    suspend fun speakStreaming(text: String, onChunk: (ByteArray) -> Unit)
    fun stop()
    suspend fun playAudio(audioData: ByteArray)
}

interface PlatformWakeLock {
    fun acquire()
    fun release()
}

interface PlatformKeyValueStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    // variantPrefs 等、SharedPreferences直叩き箇所の置き換え先
}
```

- **androidMain**: `PlatformMediaLoaderImpl`が`BitmapFactory`/`ContentResolver`/`MediaStore`を使って実装。`PlatformTtsPlayerImpl`が既存の`VoicevoxManager`/`VoicevoxStreamingTts`をラップ。`PlatformWakeLockImpl`が`PowerManager`を使用。
- **iosMain**（フェーズ4以降）: `UIImage`/`AVFoundation`ベースで同インターフェースを実装。TTSはVOICEVOXのiOS版有無に応じて別エンジンに差し替え可能な設計にしておく。

これにより**クラスタA・B・H（電源管理部分）はほぼそのままAndroid実装として横に退避でき、ChatViewModel本体からは消えます。**

### 2.2 ロジック層のUseCase/Manager分割（commonMain, Android非依存）

| 新クラス | 引き取るクラスタ | 役割 |
|---|---|---|
| `PromptBuilderUseCase` | C | メッセージ配列からプロンプト文字列を組み立てる。既存の`PromptBuilder.kt`と統合できる可能性が高い（重複ロジックがあれば一本化） |
| `ContextCompressionUseCase` | D(圧縮部分) | 会話コンテキストの要約・圧縮ロジック。埋め込みDLなどI/Oが絡む箇所だけ`PlatformMediaLoader`的なインターフェースに逃がす |
| `ModelSessionCoordinator` | E | モデルロード/切り替え/エンジン選択のオーケストレーション。`ModelManager`（既存）とセットで`commonMain`に寄せられる |
| `ImageToolInvoker` | F | 画像生成ツール呼び出しのキューイング・パス解決ロジック。ファイルパス解決部分のみプラットフォーム抽象化 |
| `ChatSessionCoordinator` | G | セッション作成/切替/タイトル同期。Repository層はフェーズ2で既にSQLDelight化想定なので、ここは素直に`commonMain`化しやすい |

### 2.3 ChatViewModel本体に残るもの

- 上記UseCase/Managerの呼び出し順序の調整
- `StateFlow`/`SharedFlow`によるUI状態の保持・公開（Compose Multiplatformの`ViewModel`はKMP対応のライフサイクルライブラリ`androidx.lifecycle:lifecycle-viewmodel`のマルチプラットフォーム版で共有可能）
- `sendMessage`, `stopGeneration`, `regenerateLastResponse`などの高レベルなユーザーアクション受付とディスパッチ

---

## 3. 段階的移行の順序（リスクが低い順）

1. **クラスタC（プロンプト構築）を先に切り出す** — 元々Android非依存なので、ほぼ移動するだけ。テストも書きやすく、KMP移行の最初の成功体験にしやすい。
2. **クラスタH中、電源管理(`PlatformWakeLock`)を切り出す** — インターフェース化が単純で影響範囲が狭い。
3. **クラスタA（メディアI/O）を`PlatformMediaLoader`に切り出す** — Android実装のみでOK。iOS実装は後回しにできる（インターフェースだけ先に用意）。
4. **クラスタB（TTS）を`PlatformTtsPlayer`に切り出す** — VOICEVOXのiOS対応状況が固まってから本格着手。それまではAndroid実装のみ。
5. **クラスタG（セッション/DB）** — SQLDelight移行(フェーズ2)と合わせて着手するのが効率的。
6. **クラスタE・F（モデル管理・画像生成ツール）** — GGUF/MNNのiOS対応(フェーズ3・4)と歩調を合わせる。

この順序であれば、**「まずUIとViewModelの土台をKMP対応にする」→「あとから各推論エンジンのiOS実装を差し込んでいく」**という進め方ができ、途中のどの時点でもAndroid版は動き続けます。

---

## 4. 補足: なぜ一括書き換えではなく段階分割か

- `ChatViewModel`は分岐が複雑（モデルロード状態、圧縮、メモリ抽出、画像生成、ツールコールが並行して絡む）で、一度に書き直すと退行バグのリスクが高い。
- インターフェース分離さえ先にやっておけば、**iOS版の実装が存在しない機能はダミー実装(`TODO`/`NotImplementedError`)で仮置きしてビルドを通せる**ため、UI・状態管理側のKMP化を先行させられる。
- 既存のAndroid版の挙動を壊さずに進められるのが最大のメリット。
