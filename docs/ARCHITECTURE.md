# nezumi-ai アーキテクチャドキュメント

**バージョン**: 2.3.0
**更新日**: 2026-08-11
**ステータス**: 実装済み（継続開発中）

---

## 概要

nezumi-aiは、オンデバイス推論を軸にクラウド推論エンジンも選択可能な、ハイブリッド構成のAndroid AIチャットアプリケーションです。

### 主要機能

- **LLM推論**: llama.cpp (GGUF) / LiteRT-LM (TFLite) によるオンデバイス推論、Claude / Gemini / OpenAI互換API / LM Studio / Ollama によるクラウド推論
- **画像生成**: 独自開発の画像生成エンジン「Nezumi Kiln」（MNNベース、SD1.5・img2img対応、SDXLは試験対応）
- **マルチモーダル**: テキスト・画像・動画・音声・テキストファイルの複合入力
- **ツールコール**: AIが画像生成・アラーム・フラッシュライト・Web検索・ページ取得・ドキュメント変換・メモリ保存等を自律的に呼び出し
- **MCPクライアント**: Streamable HTTP / SSE のMCPサーバーを登録し、プリセットごとに紐付けて外部ツールを呼び出し
- **チャット履歴**: Room DBによる永続化
- **多言語UI**: 日本語 / 英語

---

## 技術スタック

### コア技術

| レイヤー | 技術 |
|---------|------|
| UI | Jetpack Compose (Material3) + 一部 View/XML（`fragment_chat.xml`等） |
| アーキテクチャ | Fragment + ViewModel + Repository |
| データベース | Room |
| 非同期処理 | Kotlin Coroutines + Flow |
| オンデバイスLLM推論 | llama.cpp (JNI, GGUF) / LiteRT-LM (TFLite, NPU対応) |
| クラウドLLM推論 | Claude API / Gemini API / OpenAI互換API / LM Studio / Ollama |
| 画像生成 | Nezumi Kiln (MNNベース独自エンジン) |
| 音声読み上げ | VOICEVOX |

### 対応モデル

| モデル | 種別 | 用途 |
|--------|------|------|
| Gemma 4 2B | オンデバイス (GGUF) | 軽量・高速チャット |
| Gemma 4 4B | オンデバイス (GGUF) | 高精度チャット |
| Gemma 3n E2B / E4B | オンデバイス (TFLite, レガシー) | 互換性維持用 |
| Claude / Gemini | クラウド | 高精度チャット（要APIキー） |
| OpenAI互換API / LM Studio / Ollama | クラウド | 任意のセルフホスト/外部モデル |
| Stable Diffusion 1.5 | Nezumi Kiln (MNN) | 画像生成 |
| SDXL | Nezumi Kiln (MNN, 試験対応) | 画像生成 |

---

## アーキテクチャ図

```
┌──────────────────────────────────────────────────────────────────┐
│                        Presentation Layer                         │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │ChatFragment│ │ImageGen    │ │Preset      │ │Model       │    │
│  │            │ │Screen      │ │Settings    │ │SettingsFr. │    │
│  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘    │
│        │              │              │              │            │
│  ┌─────▼──────────────▼──────────────▼──────────────▼──────┐    │
│  │                  各種 ViewModel                            │    │
│  └───────────────────────────┬────────────────────────────┘    │
└──────────────────────────────┼───────────────────────────────────┘
                                │
┌──────────────────────────────▼───────────────────────────────────┐
│                          Data Layer                                │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                  │
│  │  Session   │  │  Message   │  │   Preset   │  ...              │
│  │ Repository │  │ Repository │  │ Repository │                  │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘                  │
│        │               │               │                          │
│  ┌─────▼───────────────▼───────────────▼─────┐                  │
│  │              Room Database                  │                  │
│  │ (ChatSessionEntity / MessageEntity /         │                  │
│  │  PresetEntity / MemoryEntity / AlarmEntity)  │                  │
│  └───────────────────────────────────────────┘                  │
└──────────────────────────────┬───────────────────────────────────┘
                                │
┌──────────────────────────────▼───────────────────────────────────┐
│                        Inference Layer                             │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │Gguf        │ │LiteRtLm    │ │Cloud       │ │MnnSd       │    │
│  │Inference   │ │Engine      │ │EngineFactory│ │Module      │    │
│  │Engine      │ │(TFLite/NPU)│ │(Claude等)  │ │(Nezumi Kiln)│    │
│  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘    │
│        │              │              │              │            │
│  ┌─────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐    │
│  │nezumi_rnll │ │litert-lm   │ │ HTTPS API   │ │mnn_sd_jni  │    │
│  │ama_jni.so  │ │ (JNI)      │ │ (OkHttp)    │ │.so (JNI)   │    │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘    │
│                                                                     │
│  すべて EngineManager / ModelManager が統括し、共通の                 │
│  AIInferenceEngine インターフェース経由で呼び出される                  │
└─────────────────────────────────────────────────────────────────┘
```

`EngineManager`と`ModelManager`が推論エンジンの選択・切り替え・フォールバックを一元管理し、上位のViewModelは`AIInferenceEngine`インターフェースのみを意識すればよい構成になっています。

---

## データベーススキーマ

実体は `app/src/main/java/com/nezumi_ai/data/database/entity/` 配下。主要なもののみ抜粋。

### ChatSessionEntity

```kotlin
@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createDate: Long,
    val lastUpdated: Long,
    val selectedModel: String = "E2B",
    val isIncognito: Boolean = false,
    val isPinned: Boolean = false
)
```

### MessageEntity

```kotlin
@Entity(
    tableName = "message",
    indices = [Index(value = ["sessionId"])],
    foreignKeys = [ForeignKey(
        entity = ChatSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user" or "assistant"
    val content: String,
    val thinkingContent: String? = null, // Gemma 4 シンキング（内部用のみ）
    val imageUri: String? = null,
    val imageDescription: String? = null,
    val audioUri: String? = null,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    // ツール実行結果のJSON配列
    // e.g. [{"toolName":"setalarm","success":true,"payload":{...}}]
    val toolResults: String? = null
)
```

その他、`PresetEntity`（プリセット管理）、`MemoryEntity` / `MemorySessionEntity`（メモリ機能）、`AlarmEntity`（アラームツール）、`ChatChunkEntity`（長文の分割保存）、`SettingsEntity`が存在します。

---

## LLM推論フロー

### 1. エンジン選択とモデルロード

```kotlin
// EngineManager.kt (概略)
suspend fun loadModel(modelId: String, config: InferenceConfig): Boolean {
    val engine = when {
        CloudModelId.parse(modelId) != null ->
            CloudEngineFactory.get(context, modelId)
        modelId.endsWith(".task") || modelId.contains("litert") ->
            liteRtLmEngine
        else -> ggufInferenceEngine
    }
    return engine?.loadModel(modelId, config) ?: false
}
```

実際にはモデルの種類（GGUF / TFLite / クラウド識別子）に応じて`ModelManager`が適切な`AIInferenceEngine`実装（`GgufInferenceEngine` / `LiteRtLmEngine` / `CloudEngineFactory`が返すエンジン）を選び、NPU/GPU失敗時はCPUへの自動フォールバックを行います。

`GgufInferenceEngine`は`RnLlamaContext` / `RnLlamaNative`経由で`libnezumi_rnllama_jni.so`をロードし、その内部で`librnllama_core.so`（llama.cpp本体、`app/src/main/cpp/CMakeLists.txt`でビルド）にリンクしています。同じCMakeLists.txtからはもう一つのJNIブリッジ`libllama_bridge.so`（`LlamaBridge.kt`）も生成されますが、現在の実推論経路では利用されていません。

### 2. 推論実行とツールコール

チャット送信は`ChatFragment`配下のViewModelから、選択中の`AIInferenceEngine`にストリーミングで問い合わせます。モデルがツール呼び出しを要求した場合、`ToolSystemController`が対応するツール実装（`WebSearchTool`, `WebFetchTool`, `GenerateImageToolBridge`, `DocumentToolBridge`, `CalendarTool`, MCPツール等）を呼び出し、結果を`<tool_response>`としてモデルに返します。ツール呼び出しの経過・結果はインラインのツールコールカードとしてチャット吹き出し内に表示されます。

---

## 画像生成フロー（Nezumi Kiln）

### 1. エンジンロード

`MnnSdModule` (Kotlin) が JNI 経由で `libmnn_sd_jni.so` をロードし、そこから実際の推論を担う `libmnn_sd_engine.so` を呼び出します（`jniLibs/arm64-v8a/` に両方が同梱されます）。詳細は [`mnn-sd-engine/README.md`](../mnn-sd-engine/README.md) を参照してください。

### 2. バックエンド自動選択

```
OpenCL (GPU) → 利用可能ならOpenCL
    ↓ (失敗)
CPU → フォールバック
```

### 3. AIによる自動生成

モデルが `image_generation` ツールを呼び出すと、確認ダイアログ（Compose実装、モデル・ステップ数を変更可能）でユーザーの承認を得たうえで生成が開始されます。進捗はカードUIでリアルタイム表示されます。

---

## ツールシステム

### プリセット編集画面で選択可能なツール

`PresetSettingsFragment`の`toolOptions`（`app/src/main/java/com/nezumi_ai/data/preset/PresetConstants.kt`のID定義に対応）。

| ツールID | 説明 | 承認 |
|---------|------|------|
| `time` | 現在時刻取得 | 不要 |
| `battery` | バッテリー残量取得 | 不要 |
| `alarm` | アラーム設定 | 必要 |
| `timer` | タイマー設定 | 必要 |
| `flashlight` | フラッシュライト切替 | 不要 |
| `image_generation` | 画像生成（Nezumi Kiln） | 必要 |
| `memory` | メモリ検索 | 不要 |
| `memory_save` | メモリへの明示的な保存 | 不要 |
| `web_search` | Web検索 | 不要 |
| `web_fetch` | 指定URLの本文取得（HTML→Markdown変換） | 不要 |
| `convert_md_to_document` | Markdown→Word/Excel/PDF変換 | 不要 |

このほか、MCPサーバーに登録した外部ツールをプリセットごとに有効化できます。

`calendar` / `gmail` / `switchbot` / `app_launch` は定数として定義済みですが、本稿時点ではプリセット編集画面の選択肢に公開されていません（`calendar`はコード上で無効化コメント付き）。

---

## パフォーマンス目標

| 指標 | 目標値 |
|-----|--------|
| 起動時間 | < 3秒 |
| 初回推論時間（Gemma 4 2B） | < 3秒 |
| 初回推論時間（Gemma 4 4B） | < 8秒 |
| ピークメモリ（2B） | < 3GB |
| ピークメモリ（4B） | < 5GB |

クラウド推論エンジン使用時は、選択したプロバイダーおよびネットワーク環境に依存するため、上記目標値の対象外です。

---

## セキュリティ

### データプライバシー

- オンデバイス推論を選ぶ限り、推論・フィルタリング・保存はすべて端末内で完結し、外部送信は行いません
- クラウド推論エンジンを選択した場合のみ、そのプロバイダーにチャット内容が送信されます
- MCP・Web取得ツールなど外部へのHTTPリクエストには、意図しない内部ネットワークへのアクセスを防ぐプライベートIPバリデーションを実装

### パーミッション

`AndroidManifest.xml`に実際に宣言されているもの（抜粋）:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.FLASHLIGHT" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.WRITE_CALENDAR" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

---

## 参考資料

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [Gemma Terms](https://ai.google.dev/gemma/terms)
- [`mnn-sd-engine/README.md`](../mnn-sd-engine/README.md) - Nezumi Kiln（画像生成エンジン）
- [`docs/MCP_CLIENT.md`](MCP_CLIENT.md) - MCPクライアント仕様
- [`docs/STATUS.md`](STATUS.md) - 開発ステータス
- [`docs/release-notes/`](release-notes/) - リリースノート一覧
