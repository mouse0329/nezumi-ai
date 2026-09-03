# GGUF推論エンジンの状態

## 現在の状態

**GgufInferenceEngine は `libllama_bridge.so` 経由の vendor/llama.cpp を使用しています。**

### 現在の動作

`.gguf` の絶対パスを指定したモデルは、以下の経路で処理されます：

```kotlin
ModelManager -> GgufInferenceEngine -> LlamaCppContext -> LlamaBridge
           -> libllama_bridge.so -> vendor/llama.cpp
```

## 最適化機能の使用

GgufInferenceEngineは無効化されていますが、最適化機能（PerformanceMonitor、OptimizationConfig）は**RnLlamaInferenceEngine**で使用できます。

### 使用例

```kotlin
// 1. 最適化設定を取得
val optimizationConfig = OptimizationConfig(context)
val config = optimizationConfig.getConfig("GPU")

// 2. RnLlamaInferenceEngineで最適化設定を使用
val nativeCtx = RnLlamaNative.nativeCreateContext(
    modelPath = modelPath,
    nCtx = inferenceConfig.contextWindow,
    nBatch = config.batchSize,           // 最適化されたバッチサイズ
    nUbatch = config.batchSize / 2,
    nThreads = config.threadCount,       // 最適化されたスレッド数
    nGpuLayers = config.gpuLayers,       // 最適化されたGPU層数
    useMmap = config.useMmap,
    useMlock = config.useMlock,
    ropeFreqBase = 0f,
    ropeFreqScale = 0f,
    mmprojPath = null
)

// 3. パフォーマンスモニタリング
PerformanceMonitor.startInference(sessionId, "GPU", promptTokens)

// 推論実行...

PerformanceMonitor.recordToken(sessionId)

// 推論終了
val metrics = PerformanceMonitor.endInference(sessionId)
Log.i(TAG, "TPS: ${metrics?.tokensPerSecond}, TTFT: ${metrics?.ttftMs}ms")
```

## RnLlamaInferenceEngineの最適化統合

RnLlamaInferenceEngineに最適化機能を統合する手順：

### 1. OptimizationConfigの統合

```kotlin
class RnLlamaInferenceEngine(private val context: Context) : AIInferenceEngine {
    
    private val optimizationConfig = OptimizationConfig(context)
    
    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        val optConfig = optimizationConfig.getConfig(config.backendType)
        
        // 最適化されたパラメータでモデルをロード
        val nativeCtx = RnLlamaNative.nativeCreateContext(
            modelPath = modelPath,
            nCtx = config.contextWindow,
            nBatch = optConfig.batchSize,
            nUbatch = optConfig.batchSize / 2,
            nThreads = optConfig.threadCount,
            nGpuLayers = optConfig.gpuLayers,
            useMmap = optConfig.useMmap,
            useMlock = optConfig.useMlock,
            ropeFreqBase = 0f,
            ropeFreqScale = 0f,
            mmprojPath = null
        )
        
        // ...
    }
}
```

### 2. PerformanceMonitorの統合

```kotlin
override suspend fun inferenceWithMedia(
    sessionId: Long,
    prompt: String,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    config: InferenceConfig
): Flow<String> = callbackFlow {
    
    // パフォーマンスモニタリング開始
    val promptTokens = prompt.length / 4  // 概算
    PerformanceMonitor.startInference(sessionId, config.backendType, promptTokens)
    
    try {
        // 推論実行
        // ... (既存のrnllama実装)
        
        // トークンごとに記録
        PerformanceMonitor.recordToken(sessionId)
        
    } finally {
        // 推論終了
        val metrics = PerformanceMonitor.endInference(sessionId)
        if (metrics != null) {
            Log.i(TAG, "Performance: ${metrics.toLogString()}")
        }
    }
    
    awaitClose()
}
```

## GgufInferenceEngineを有効化する場合

将来的にGgufInferenceEngineを有効化する場合の手順：

### 1. llama.cppサブモジュールを追加

```bash
cd c:\Users\mouse\AndroidStudioProjects\nezumiai
git submodule add https://github.com/ggerganov/llama.cpp vendor/llama.cpp
git submodule update --init --recursive
```

### 2. CMakeLists.txtを更新

`app/src/main/cpp/CMakeLists.txt`のコメントを解除：

```cmake
# llama.cppのビルド設定
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
add_subdirectory(${CMAKE_SOURCE_DIR}/../vendor/llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)

# llama_bridgeのビルド
add_library(llama_bridge SHARED llama_bridge.cpp)
target_include_directories(llama_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp/include
    ${CMAKE_SOURCE_DIR}/../vendor/llama.cpp/ggml/include
)
target_link_libraries(llama_bridge PRIVATE llama ggml ${LOG_LIB} android)
target_compile_options(llama_bridge PRIVATE -O3 -DNDEBUG -pthread)
target_link_options(llama_bridge PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
```

### 3. ModelManager.ktを更新

```kotlin
private fun shouldUseGgufEngine(modelName: String): Boolean {
    val trimmed = modelName.trim()
    val lowered = trimmed.lowercase()
    val isAbsoluteGguf = lowered.endsWith(".gguf") && java.io.File(trimmed).isAbsolute
    return isAbsoluteGguf
}
```

### 4. ビルドと確認

```bash
.\gradlew.bat clean
.\gradlew.bat assembleDebug
```

ビルドログで`libllama_bridge.so`が生成されることを確認。

## パフォーマンス比較

### RnLlamaInferenceEngine（現在使用中）
- **利点**: 安定性、マルチモーダル対応、既存実装との互換性
- **最適化**: OptimizationConfigで自動調整可能
- **期待TPS**: 10-25 tok/s（デバイス依存）

### GgufInferenceEngine（将来的）
- **利点**: vanilla llama.cpp、最新機能、カスタマイズ性
- **最適化**: バッチ再利用、サンプラーキャッシング
- **期待TPS**: 15-30 tok/s（最適化後）

## まとめ

現在はRnLlamaInferenceEngineで十分な性能が得られます。最適化機能（PerformanceMonitor、OptimizationConfig）を統合することで、さらなる性能向上が期待できます。

GgufInferenceEngineは将来的な拡張オプションとして保持されています。

---

**更新日**: 2026年6月1日  
**状態**: RnLlamaInferenceEngine使用中、GgufInferenceEngine無効化
