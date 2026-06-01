# ビルドエラー修正ガイド

## 現在の状況

ネイティブライブラリのビルドエラーが発生しています。以下のライブラリが「不明なエラー」を報告しています：

```
lib/arm64-v8a/libLiteRt.so
lib/arm64-v8a/libstable_diffusion_core.so
lib/arm64-v8a/libvoicevox_core_java_api.so
lib/arm64-v8a/libLiteRtCIGIAccelerator.so
lib/arm64-v8a/libonnxruntime.so
lib/arm64-v8a/libc++_shared.so
lib/arm64-v8a/liblitertlm_jni.so
lib/arm64-v8a/librnllama_core.so
lib/arm64-v8a/libonnxruntime4j_jni.so
lib/arm64-v8a/libandroidx.graphics.path.so
lib/arm64-v8a/libnezumi_rnllama_jni.so
```

## 原因

これらのエラーは通常、以下のいずれかが原因です：

1. **16KBページサイズ対応の問題** - Android 15+の新しいページサイズ要件
2. **NDKバージョンの不一致**
3. **依存ライブラリの欠落**
4. **ビルド設定の不整合**

## 解決方法

### 方法1: クリーンビルド（推奨）

```bash
# Gradleキャッシュをクリア
cd c:\Users\mouse\AndroidStudioProjects\nezumiai
gradlew clean

# .cxxディレクトリを削除
rmdir /s /q app\.cxx

# 再ビルド
gradlew assembleDebug
```

### 方法2: NDK設定の確認

`local.properties`を確認：

```properties
sdk.dir=C\:\\Users\\mouse\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\mouse\\AppData\\Local\\Android\\Sdk\\ndk\\30.0.14904198
```

NDKバージョンが`30.0.14904198`であることを確認してください。

### 方法3: CMake引数の確認

`app/build.gradle.kts`の`externalNativeBuild`セクション：

```kotlin
externalNativeBuild {
    cmake {
        arguments.add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
    }
}
```

この設定が存在することを確認してください（既に設定済み）。

### 方法4: 段階的ビルド

特定のライブラリのみをビルドする場合：

```bash
# rnllamaのみビルド
gradlew :app:externalNativeBuildDebug

# ビルドログを確認
gradlew :app:externalNativeBuildDebug --info
```

## llama_bridge最適化について

最適化されたllama_bridge実装は以下に配置されています：

- `app/src/main/cpp/llama_bridge.cpp` - ネイティブ実装
- `app/src/main/java/com/nezumi_ai/data/inference/LlamaBridge.kt` - JNIブリッジ
- `app/src/main/java/com/nezumi_ai/data/inference/GgufInferenceEngine.kt` - 推論エンジン

現在、llama_bridgeは**コメントアウト状態**です。理由：

1. 既存のrnllama実装と競合を避けるため
2. vanilla llama.cppサブモジュールが必要なため

### llama_bridgeを有効化する場合

1. llama.cppをサブモジュールとして追加：

```bash
cd c:\Users\mouse\AndroidStudioProjects\nezumiai
git submodule add https://github.com/ggerganov/llama.cpp vendor/llama.cpp
git submodule update --init --recursive
```

2. `app/src/main/cpp/CMakeLists.txt`のコメントを解除：

```cmake
# Uncomment these lines:
add_library(llama_bridge SHARED llama_bridge.cpp)
target_include_directories(llama_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp/include
    ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp/ggml/include
)
target_link_libraries(llama_bridge PRIVATE llama ggml ${LOG_LIB} android)
```

3. llama.cppのビルド設定を追加：

```cmake
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
add_subdirectory(${CMAKE_SOURCE_DIR}/../vendor/llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)
```

## 現在の推奨アプローチ

**既存のrnllama実装を使用してください。**

最適化機能（PerformanceMonitor、OptimizationConfig）は既存のrnllama実装でも使用可能です：

```kotlin
// RnLlamaInferenceEngineで使用
val optimizationConfig = OptimizationConfig(context)
val config = optimizationConfig.getConfig("GPU")

// パフォーマンスモニタリング
PerformanceMonitor.startInference(sessionId, "GPU", promptTokens)
// ... 推論実行 ...
val metrics = PerformanceMonitor.endInference(sessionId)
```

## トラブルシューティング

### エラー: "unknown error"

```bash
# ビルドログを詳細表示
gradlew assembleDebug --stacktrace --info > build_log.txt
```

ログファイルを確認して具体的なエラーメッセージを特定してください。

### エラー: "page size"関連

Android 15+の16KBページサイズ要件です。CMakeLists.txtに以下が含まれていることを確認：

```cmake
target_link_options(target_name PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
```

### エラー: "libc++_shared.so"

```kotlin
// app/build.gradle.kts
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

この設定が存在することを確認してください（既に設定済み）。

## 次のステップ

1. **クリーンビルドを実行**
2. **ビルドログを確認**
3. **エラーメッセージを特定**
4. **必要に応じてNDK/CMake設定を調整**

詳細なビルドログが必要な場合は、以下のコマンドを実行してください：

```bash
gradlew assembleDebug --stacktrace --info --debug > full_build_log.txt 2>&1
```
