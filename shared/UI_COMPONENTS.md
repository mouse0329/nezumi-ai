# 共通UIコンポーネント

Compose Multiplatformを使用してAndroidとDesktop間でUIコンポーネントを共有します。

## 構成

```
shared/src/commonMain/kotlin/com/nezumi_ai/shared/ui/
├── components/          # 再利用可能なUIコンポーネント
│   ├── MessageBubble.kt
│   └── ChatInput.kt
├── screen/              # 画面レベルのコンポーネント
│   ├── ChatScreen.kt
│   └── SettingsScreen.kt
└── theme/               # テーマとカラー設定
    ├── Color.kt
    └── Theme.kt
```

## コンポーネント一覧

### MessageBubble
チャットメッセージを表示するバブルコンポーネント

```kotlin
@Composable
fun MessageBubble(message: ChatMessage)
```

- ユーザー/AI メッセージで色分け
- 最大幅600dpで自動折り返し
- Material3デザイン準拠

### ChatInput
メッセージ入力欄と送信ボタン

```kotlin
@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true
)
```

- 最大5行の複数行入力対応
- 送信ボタンの有効/無効制御
- 日本語プレースホルダー

### ChatScreen
完全なチャット画面

```kotlin
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onSendMessage: (String) -> Unit
)
```

- メッセージリスト（LazyColumn）
- 自動スクロール
- ストリーミング対応

### SettingsScreen
設定画面

```kotlin
@Composable
fun SettingsScreen(
    libraryDownloaded: Boolean,
    onDownloadLibrary: () -> Unit,
    availableModels: List<ModelInfo>,
    onDownloadModel: (String) -> Unit,
    downloadProgress: DownloadProgress?,
    useGpu: Boolean,
    onGpuToggle: (Boolean) -> Unit,
    downloadedModels: List<String>,
    onLoadModel: (String) -> Unit,
    currentModel: String?
)
```

- llama.cppライブラリ管理
- モデルダウンロード
- GPU/CPU切り替え
- ダウンロード済みモデル一覧

## テーマ

### NezumiTheme
アプリ全体のテーマ

```kotlin
@Composable
fun NezumiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
)
```

**カラーパレット:**
- NezumiGray: `#808080`
- NezumiDarkGray: `#404040`
- NezumiLightGray: `#B0B0B0`
- NezumiAccent: `#64B5F6` (青)

## プラットフォーム固有の実装

### Desktop
```kotlin
// desktop/src/main/kotlin/com/nezumi_ai/desktop/ui/screen/ChatScreen.kt
@Composable
fun ChatScreen() {
    val viewModel = remember { ChatViewModel() }
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    
    SharedChatScreen(
        messages = messages,
        isGenerating = isGenerating,
        onSendMessage = { viewModel.sendMessage(it) }
    )
}
```

### Android
```kotlin
// app/src/main/kotlin/com/nezumi_ai/ui/screen/ChatScreen.kt
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    
    SharedChatScreen(
        messages = messages,
        isGenerating = isGenerating,
        onSendMessage = { viewModel.sendMessage(it) }
    )
}
```

## コード共有率

| レイヤー | 共有率 | 備考 |
|---------|--------|------|
| **UIコンポーネント** | 100% | 完全共通化 |
| **画面レイアウト** | 100% | 完全共通化 |
| **テーマ** | 100% | 完全共通化 |
| **ViewModelラッパー** | 95% | DI部分のみ異なる |

## 使用方法

### 1. 共通コンポーネントをインポート
```kotlin
import com.nezumi_ai.shared.ui.components.*
import com.nezumi_ai.shared.ui.screen.*
import com.nezumi_ai.shared.ui.theme.*
```

### 2. テーマでラップ
```kotlin
@Composable
fun App() {
    NezumiTheme(darkTheme = true) {
        // アプリコンテンツ
    }
}
```

### 3. 共通画面を使用
```kotlin
SharedChatScreen(
    messages = messages,
    isGenerating = isGenerating,
    onSendMessage = { text -> /* 処理 */ }
)
```

## 利点

✅ **コード重複ゼロ**: UI実装を1回書くだけ  
✅ **一貫性**: Android/Desktop で同じUX  
✅ **保守性**: バグ修正・機能追加が1箇所で完結  
✅ **Material3**: 最新のデザインシステム  
✅ **型安全**: Kotlinの型システムで安全  

## 今後の拡張

- [ ] iOS対応（Compose Multiplatform iOS）
- [ ] Web対応（Compose for Web）
- [ ] アニメーション共通化
- [ ] アクセシビリティ対応
