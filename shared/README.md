# Shared Module

Android版とDesktop版で共有されるコードモジュールです。

## 構成

```
shared/
├── src/
│   ├── commonMain/          # 共通コード（100%共有）
│   │   └── kotlin/
│   │       └── com/nezumi_ai/shared/
│   │           ├── model/           # データモデル
│   │           ├── repository/      # Repository インターフェース
│   │           └── inference/       # LLM エンジンインターフェース
│   │
│   ├── androidMain/         # Android固有実装
│   │   └── kotlin/
│   │       └── com/nezumi_ai/shared/
│   │           └── inference/
│   │               └── PlatformLlmEngine.kt  # LiteRT-LM実装
│   │
│   └── desktopMain/         # Desktop固有実装
│       └── kotlin/
│           └── com/nezumi_ai/shared/
│               └── inference/
│                   └── PlatformLlmEngine.kt  # llama.cpp JNA実装
```

## 共有されるコード

### 1. データモデル (100%共有)

```kotlin
// ChatMessage, ChatSession, ModelConfig, InferenceConfig
import com.nezumi_ai.shared.model.*

val message = ChatMessage(
    id = "1",
    sessionId = "session1",
    content = "Hello",
    isUser = true
)
```

### 2. Repository インターフェース (100%共有)

```kotlin
import com.nezumi_ai.shared.repository.ChatRepository

interface ChatRepository {
    suspend fun getAllSessions(): List<ChatSession>
    suspend fun getMessages(sessionId: String): List<ChatMessage>
    // ...
}
```

実装は各プラットフォームで：
- Android: Room Database
- Desktop: Exposed (SQLite)

### 3. LLM エンジン (インターフェース共有、実装は個別)

```kotlin
import com.nezumi_ai.shared.inference.PlatformLlmEngine

val engine = PlatformLlmEngine()
engine.initialize(modelPath, config)
engine.generate(prompt, config).collect { token ->
    println(token)
}
```

実装：
- Android: LiteRT-LM または llama.cpp (JNI)
- Desktop: llama.cpp (JNA)

## コード共有率

| レイヤー | 共有率 | 備考 |
|---------|--------|------|
| **データモデル** | 100% | 完全共通 |
| **Repository IF** | 100% | インターフェースのみ |
| **LLM Engine IF** | 100% | インターフェースのみ |
| **ViewModel** | 90%+ | StateFlow使用で共通化可能 |
| **UI (Compose)** | 95%+ | Compose Multiplatformで共通化 |

## 使用方法

### Android版

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":shared"))
}

// 使用例
import com.nezumi_ai.shared.model.ChatMessage
import com.nezumi_ai.shared.inference.PlatformLlmEngine

val engine = PlatformLlmEngine() // Android実装が使われる
```

### Desktop版

```kotlin
// desktop/build.gradle.kts
dependencies {
    implementation(project(":shared"))
}

// 使用例
import com.nezumi_ai.shared.model.ChatMessage
import com.nezumi_ai.shared.inference.PlatformLlmEngine

val engine = PlatformLlmEngine() // Desktop実装が使われる
```

## 次のステップ

1. ✅ 共通データモデル作成
2. ✅ Repository インターフェース定義
3. ✅ LLM Engine インターフェース定義
4. ⏳ Android実装の移行
5. ⏳ Desktop実装の移行
6. ⏳ ViewModelの共通化
7. ⏳ UIの共通化（Compose Multiplatform）

## メリット

- **コード重複の削減**: データモデル、ビジネスロジックを1箇所で管理
- **一貫性**: 両プラットフォームで同じロジック
- **保守性向上**: バグ修正が両方に反映
- **開発速度向上**: 新機能を1回実装すれば両方で使える
