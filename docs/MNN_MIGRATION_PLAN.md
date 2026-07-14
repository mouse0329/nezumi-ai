# MNN/QNN Stable Diffusion 移行プラン

## 概要

stable-diffusion.cpp (ggml) から MNN/QNN (Alibaba/Qualcomm) への移行

## 移行理由

- **Vulkan対応の困難**: stable-diffusion.cppのVulkanビルドがクロスコンパイル時に失敗
- **GPU加速の確実性**: MNN OpenCL / QNN NPUは実績あり
- **パフォーマンス**: Snapdragon 8 Gen 2で5-10秒/画像 (vs 106秒/step CPU)

## 必要なコンポーネント

### 1. ネイティブライブラリ

#### libstable_diffusion_core.so
- **入手元**: https://github.com/xororz/stable-diffusion.cpp-mnn (ビルド必要)
- **配置先**: `app/src/main/jniLibs/arm64-v8a/`

#### QNNライブラリ (NPU用)
```
app/src/main/assets/qnnlibs/
├── libQnnHtp.so
├── libQnnSystem.so
```

### 2. モデル形式

#### MNN形式 (CPU/OpenCL)
```
model_dir/
├── clip.mnn
├── unet.mnn
├── vae_decoder.mnn
└── tokenizer.json
```

#### QNN形式 (NPU)
```
model_dir/
├── clip.bin
├── unet.bin
├── vae_decoder.bin
└── tokenizer.json
```

### 3. モデル入手

- **MNN**: https://huggingface.co/xororz/sd-mnn
- **QNN**: https://huggingface.co/xororz/sd-qnn

## 実装ステップ

### Step 1: SDエンジンモジュール作成

`app/src/main/java/com/nezumi_ai/sd/SdEngineModule.kt`:

```kotlin
package com.nezumi_ai.sd

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class SdEngineModule(private val context: Context) {
    
    companion object {
        private const val TAG = "SdEngineModule"
        private const val SERVER_PORT = 18081
        private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
    }
    
    private var serverProcess: Process? = null
    private var isServerReady = false
    
    suspend fun loadModel(modelPath: String, backend: String = "auto"): Boolean = withContext(Dispatchers.IO) {
        stopServer()
        
        val modelDir = File(modelPath)
        if (!modelDir.exists()) return@withContext false
        
        val isCpu = backend == "mnn" || backend == "auto"
        val executable = extractExecutable() ?: return@withContext false
        
        val command = buildCommand(executable, modelDir, isCpu)
        val env = buildEnvironment()
        
        val processBuilder = ProcessBuilder(command).apply {
            directory(executable.parentFile)
            redirectErrorStream(true)
            environment().putAll(env)
        }
        
        serverProcess = processBuilder.start()
        
        // サーバー起動待機
        isServerReady = waitForServer(120000)
        isServerReady
    }
    
    suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        onProgress: (Int, Int) -> Unit
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!isServerReady) return@withContext null
        
        val url = URL("http://127.0.0.1:$SERVER_PORT/generate")
        val conn = url.openConnection() as HttpURLConnection
        
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        
        val body = buildJsonBody(prompt, negativePrompt, width, height, steps, cfg, seed)
        conn.outputStream.write(body.toByteArray())
        
        // SSE受信 + 進捗通知
        var resultBitmap: Bitmap? = null
        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("data: ")) {
                    val json = parseJson(line!!.substring(6))
                    when (json["type"]) {
                        "progress" -> {
                            val step = json["step"] as Int
                            val totalSteps = json["total_steps"] as Int
                            onProgress(step, totalSteps)
                        }
                        "complete" -> {
                            resultBitmap = decodeImage(json["image"] as String, width, height)
                        }
                    }
                }
            }
        }
        
        conn.disconnect()
        resultBitmap
    }
    
    fun stopServer() {
        serverProcess?.destroy()
        serverProcess = null
        isServerReady = false
    }
    
    private fun extractExecutable(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val executable = File(nativeDir, EXECUTABLE_NAME)
        return if (executable.exists()) executable else null
    }
    
    private fun buildCommand(executable: File, modelDir: File, isCpu: Boolean): List<String> {
        return if (isCpu) {
            listOf(
                executable.absolutePath,
                "--clip", File(modelDir, "clip.mnn").absolutePath,
                "--unet", File(modelDir, "unet.mnn").absolutePath,
                "--vae_decoder", File(modelDir, "vae_decoder.mnn").absolutePath,
                "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                "--port", SERVER_PORT.toString(),
                "--text_embedding_size", "768",
                "--cpu"
            )
        } else {
            listOf(
                executable.absolutePath,
                "--clip", File(modelDir, "clip.bin").absolutePath,
                "--unet", File(modelDir, "unet.bin").absolutePath,
                "--vae_decoder", File(modelDir, "vae_decoder.bin").absolutePath,
                "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                "--port", SERVER_PORT.toString(),
                "--text_embedding_size", "768"
            )
        }
    }
    
    private fun buildEnvironment(): Map<String, String> {
        return mapOf(
            "LD_LIBRARY_PATH" to "/system/lib64:/vendor/lib64"
        )
    }
    
    private suspend fun waitForServer(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val url = URL("http://127.0.0.1:$SERVER_PORT/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return true
            } catch (_: Exception) {}
            delay(500)
        }
        return false
    }
    
    private fun buildJsonBody(
        prompt: String, negPrompt: String, width: Int, height: Int,
        steps: Int, cfg: Float, seed: Long
    ): String {
        return """
            {
                "prompt": "$prompt",
                "negative_prompt": "$negPrompt",
                "width": $width,
                "height": $height,
                "steps": $steps,
                "cfg": $cfg,
                "seed": ${if (seed < 0) (Math.random() * Int.MAX_VALUE).toInt() else seed},
                "scheduler": "dpm",
                "show_diffusion_process": true,
                "show_diffusion_stride": 2
            }
        """.trimIndent()
    }
    
    private fun parseJson(data: String): Map<String, Any> {
        // 簡易JSONパーサー (本番ではGsonなど使用)
        return mapOf()
    }
    
    private fun decodeImage(base64Rgb: String, width: Int, height: Int): Bitmap? {
        // Base64 RGB → Bitmap変換
        return null
    }
}
```

### Step 2: ImageGenViewModel書き換え

主な変更点:
- `EngineManager.acquireSd()` → `localDream.loadModel()`
- `sd.generate()` → `localDream.generateImage()`

### Step 3: ライブラリビルド

```bash
# stable-diffusion.cpp-mnnをクローン
git clone https://github.com/xororz/stable-diffusion.cpp-mnn
cd stable-diffusion.cpp-mnn

# Android NDKでビルド
mkdir build && cd build
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-30 \
      ..
make -j8

# 生成されたlibstable_diffusion_core.soをコピー
cp libstable_diffusion_core.so $PROJECT/app/src/main/jniLibs/arm64-v8a/
```

## タイムライン

- **Phase 1 (1-2日)**: ライブラリビルド + 動作確認
- **Phase 2 (2-3日)**: LocalDreamModule実装 + 統合
- **Phase 3 (1-2日)**: テスト + 最適化

## 推奨アプローチ

**段階的移行**:
1. 現在のstable-diffusion.cpp版を残す
2. MNN/QNN版を並行実装
3. 動作確認後に切り替え

---

**ステータス**: 計画中
