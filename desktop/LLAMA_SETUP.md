# llama.cpp Setup Guide for nezumi-ai Desktop

## 必要なファイル

Desktop版でllama.cppを使用するには、以下のネイティブライブラリが必要です：

### Windows
- `llama.dll` (または `libllama.dll`)

### Linux
- `libllama.so`

### macOS
- `libllama.dylib`

---

## セットアップ手順

### Option 1: ビルド済みバイナリを使用（推奨）

#### 1. llama.cppをダウンロード

```bash
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp
```

#### 2. ビルド

**Windows (MSVC):**
```bash
cmake -B build -DBUILD_SHARED_LIBS=ON
cmake --build build --config Release
```

**Linux/macOS:**
```bash
cmake -B build -DBUILD_SHARED_LIBS=ON
cmake --build build --config Release
```

**GPU対応 (CUDA):**
```bash
cmake -B build -DBUILD_SHARED_LIBS=ON -DGGML_CUDA=ON
cmake --build build --config Release
```

**GPU対応 (Metal - macOS):**
```bash
cmake -B build -DBUILD_SHARED_LIBS=ON -DGGML_METAL=ON
cmake --build build --config Release
```

#### 3. ライブラリを配置

ビルドされたライブラリを以下のいずれかに配置：

**Option A: プロジェクトディレクトリ**
```
nezumi-ai/
├── desktop/
│   └── libs/
│       ├── windows/
│       │   └── llama.dll
│       ├── linux/
│       │   └── libllama.so
│       └── macos/
│           └── libllama.dylib
```

**Option B: システムライブラリパス**
- Windows: `C:\Windows\System32\` または `PATH`に追加
- Linux: `/usr/local/lib/` または `LD_LIBRARY_PATH`に追加
- macOS: `/usr/local/lib/` または `DYLD_LIBRARY_PATH`に追加

---

### Option 2: Android版のネイティブコードを再利用

Android版の`app/src/main/cpp/llama_rn/`をDesktop用にビルド：

```bash
cd app/src/main/cpp/llama_rn
cmake -B build -DBUILD_SHARED_LIBS=ON
cmake --build build --config Release
```

---

## モデルファイルの準備

### 1. GGUFモデルをダウンロード

Hugging Faceから推奨モデル：

```bash
# Gemma 2B (軽量)
huggingface-cli download google/gemma-2b-it-GGUF gemma-2b-it-q4_k_m.gguf

# Gemma 7B (高性能)
huggingface-cli download google/gemma-7b-it-GGUF gemma-7b-it-q4_k_m.gguf
```

### 2. モデルパスを設定

アプリの Settings → Model Path に設定：
```
C:\Users\<username>\models\gemma-2b-it-q4_k_m.gguf
```

---

## 実行

### Gradleから実行

```bash
./gradlew :desktop:run -Djava.library.path=desktop/libs/windows
```

### パッケージ版

```bash
./gradlew :desktop:packageDistributionForCurrentOS
```

生成されたインストーラーを実行すると、自動的にライブラリがバンドルされます。

---

## トラブルシューティング

### エラー: "Failed to load llama.cpp library"

**原因**: ネイティブライブラリが見つからない

**解決策**:
1. ライブラリが正しいパスに配置されているか確認
2. `java.library.path`を明示的に指定：
   ```bash
   ./gradlew :desktop:run -Djava.library.path=/path/to/libs
   ```

### エラー: "UnsatisfiedLinkError"

**原因**: ライブラリの依存関係が不足

**Windows**: Visual C++ Redistributableをインストール
```
https://aka.ms/vs/17/release/vc_redist.x64.exe
```

**Linux**: 必要なライブラリをインストール
```bash
sudo apt-get install libgomp1
```

**macOS**: Xcode Command Line Toolsをインストール
```bash
xcode-select --install
```

### GPU推論が動作しない

**CUDA (NVIDIA)**:
- CUDA Toolkit 11.8+がインストールされているか確認
- `nvidia-smi`でGPUが認識されているか確認

**Metal (macOS)**:
- macOS 13+が必要
- M1/M2/M3チップが必要

---

## パフォーマンス最適化

### スレッド数の調整

```kotlin
val engine = LlamaCppEngine()
engine.initialize(
    modelPath = "/path/to/model.gguf",
    nCtx = 2048,
    nThreads = 8  // CPUコア数に応じて調整
)
```

### GPU層の設定

```kotlin
engine.initialize(
    modelPath = "/path/to/model.gguf",
    nGpuLayers = 32  // GPUにオフロードする層数
)
```

---

## 次のステップ

1. ✅ llama.cppライブラリをビルド/ダウンロード
2. ✅ ライブラリを配置
3. ✅ GGUFモデルをダウンロード
4. ✅ アプリを起動してモデルパスを設定
5. ✅ チャットを開始

詳細は [desktop/README.md](README.md) を参照してください。
