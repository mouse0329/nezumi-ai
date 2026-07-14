# nezumi-ai アーキテクチャドキュメント

**バージョン**: 2.0  
**更新日**: 2025-01-XX  
**ステータス**: 実装中

---

## 概要

nezumi-aiは完全オフラインで動作するAndroid AIチャットアプリケーションです。

### 主要機能

- **LLM推論**: llama.cpp (GGUF) による高速推論
- **画像生成**: MNN 画像生成エンジンによる高速画像生成
- **マルチモーダル**: テキスト + 画像入力対応
- **ツールコール**: Gemmaがツールとして画像生成・アラーム・フラッシュライト等を呼び出し
- **チャット履歴**: Room DBによる永続化

---

## 技術スタック

### コア技術

| レイヤー | 技術 |
|---------|------|
| UI | Jetpack Compose (Material3) |
| アーキテクチャ | MVVM + Repository |
| データベース | Room |
| 非同期処理 | Kotlin Coroutines + Flow |
| LLM推論 | llama.cpp (JNI) |
| 画像生成 | MNN 画像生成エンジン |

### 対応モデル

| モデル | サイズ | 用途 |
|--------|--------|------|
| Gemma 4 | ~2GB | 高精度チャット |
| Gemma 3n E2B | ~900MB | 軽量・高速チャット |
| Gemma 3n E4B | ~2.1GB | 高精度チャット |
| Stable Diffusion 1.5 | ~1.5GB | 画像生成 (MNN) |

---

## アーキテクチャ図

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation Layer                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ SessionList  │  │  ChatScreen  │  │  ImageGen    │  │
│  │  Fragment    │  │   Fragment   │  │  Fragment    │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                 │                  │          │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐  │
│  │ SessionList  │  │    Chat      │  │  ImageGen    │  │
│  │  ViewModel   │  │  ViewModel   │  │  ViewModel   │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                      Domain Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │   Session    │  │   Message    │  │   Settings   │  │
│  │  Repository  │  │  Repository  │  │  Repository  │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘  │
│         │                 │                             │
│  ┌──────▼─────────────────▼───────┐                    │
│  │         Room Database           │                    │
│  │  (ChatSession / Message / etc)  │                    │
│  └─────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────┐
│                     Inference Layer                      │
│  ┌──────────────────────┐  ┌──────────────────────┐    │
│  │     LlmEngine        │  │   MnnSdModule         │    │
│  │  (llama.cpp JNI)     │  │   (MNN/QNN Server)   │    │
│  └──────────┬───────────┘  └──────────┬───────────┘    │
│             │                          │                │
│  ┌──────────▼───────────┐  ┌──────────▼───────────┐    │
│  │ librnllama.so        │  │ libstable_diffusion  │    │
│  │ (llama.cpp native)   │  │ _core.so (MNN/QNN)   │    │
│  └──────────────────────┘  └──────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

---

## データベーススキーマ

### ChatSession

```kotlin
@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelName: String,
    val isPinned: Boolean = false
)
```

### Message

```kotlin
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ChatSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val role: Role, // USER, ASSISTANT, SYSTEM
    val content: String,
    val imageUri: String? = null,
    val timestamp: Long,
    val toolCalls: String? = null, // JSON
    val toolResults: String? = null // JSON
)
```

---

## LLM推論フロー

### 1. モデルロード

```kotlin
// LlmEngine.kt
suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
    nativeInit(modelPath, threads = 4)
}
```

### 2. 推論実行

```kotlin
// ChatViewModel.kt
fun sendMessage(text: String, imageUri: Uri? = null) {
    viewModelScope.launch {
        val response = llmEngine.generate(
            prompt = text,
            imageUri = imageUri,
            onToken = { token -> 
                // ストリーミング表示
                _messages.value = _messages.value + token
            }
        )
    }
}
```

### 3. ツールコール処理

```kotlin
// ToolExecutor.kt
suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
    return when (toolCall.name) {
        "generateImage" -> {
            val approved = awaitUserConfirmation(toolCall.args["prompt"])
            if (approved) {
                imageGenerator.generateImage(...)
            } else {
                ToolResult.Text("キャンセルされました")
            }
        }
        "setAlarm" -> alarmManager.setAlarm(...)
        "toggleFlashlight" -> flashlightManager.toggle()
        else -> ToolResult.Error("Unknown tool")
    }
}
```

---

## 画像生成フロー

### 1. サーバー起動

```kotlin
// LocalDreamModule.kt
suspend fun loadModel(modelPath: String, backend: String): Boolean {
    val executable = extractExecutable() // libstable_diffusion_core.so
    val command = buildCommand(executable, modelPath, backend)
    serverProcess = ProcessBuilder(command).start()
    return waitForServer(timeout = 120000)
}
```

### 2. 画像生成リクエスト

```kotlin
suspend fun generateImage(
    prompt: String,
    negativePrompt: String,
    width: Int,
    height: Int,
    steps: Int,
    cfg: Float,
    seed: Long,
    onProgress: (Int, Int) -> Unit
): Bitmap? {
    val url = URL("http://127.0.0.1:18081/generate")
    val conn = url.openConnection() as HttpURLConnection
    
    // SSE (Server-Sent Events) で進捗受信
    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
        // progress / complete イベントを処理
    }
}
```

### 3. バックエンド自動選択

```
QNN (NPU) → 利用可能ならQNN
    ↓ (失敗)
MNN (CPU/OpenCL) → フォールバック
```

---

## ツールシステム

### 対応ツール

| ツール名 | 説明 | 承認 |
|---------|------|------|
| `generateImage` | 画像生成 | 必要 |
| `setAlarm` | アラーム設定 | 必要 |
| `toggleFlashlight` | フラッシュライト切替 | 不要 |
| `webSearch` | Web検索 | 必要 |
| `getWeather` | 天気取得 | 不要 |

### ツール定義例

```kotlin
Tool(
    name = "generateImage",
    description = "Generate an image from a text prompt",
    parameters = mapOf(
        "prompt" to "Detailed English prompt",
        "negative_prompt" to "Things to avoid (optional)",
        "width" to "256, 512, or 768 (default 512)",
        "height" to "256, 512, or 768 (default 512)"
    )
)
```

---

## パフォーマンス目標

| 指標 | 目標値 | 現状 |
|-----|--------|------|
| 起動時間 | < 3秒 | ~2秒 |
| 初回推論時間 (E2B) | < 5秒 | ~4秒 |
| 初回推論時間 (E4B) | < 10秒 | ~8秒 |
| ピークメモリ (E2B) | < 3GB | ~2.5GB |
| ピークメモリ (E4B) | < 5GB | ~4GB |
| 画像生成時間 (QNN) | < 10秒 | ~7秒 |
| 画像生成時間 (MNN) | < 60秒 | ~45秒 |

---

## セキュリティ

### データプライバシー

- すべての推論はオンデバイスで実行
- ネットワーク通信なし（モデルダウンロード時を除く）
- チャット履歴はローカルDBに暗号化保存（予定）

### パーミッション

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.FLASHLIGHT" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.INTERNET" /> <!-- モデルDLのみ -->
```

---

## 今後の予定

### Phase 1 (現在)
- [x] llama.cpp統合
- [x] LocalDream (MNN/QNN) 統合
- [x] ツールコール基本実装
- [ ] チャット履歴UI改善

### Phase 2
- [ ] RAG (Retrieval-Augmented Generation)
- [ ] カレンダー連携
- [ ] Gmail連携
- [ ] SwitchBot連携

### Phase 3
- [ ] マルチモーダル強化 (音声入力/出力)
- [ ] LoRA対応
- [ ] モデル量子化最適化

---

## 参考資料

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [stable-diffusion.cpp-mnn](https://github.com/xororz/stable-diffusion.cpp-mnn)
- [Gemma Terms](https://ai.google.dev/gemma/terms)
