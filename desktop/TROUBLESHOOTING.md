# nezumi-ai Desktop トラブルシューティング

## "Invalid memory access" エラー

### 症状
```
✗ Failed to initialize llama.cpp backend: Error: Invalid memory access
  llama.dll may be incompatible or missing dependencies
```

### 原因
1. **llama.dllのバージョン不一致** - JNA構造体定義と実際のllama.cppのバージョンが合っていない
2. **Visual C++ Redistributableの不足** - 必要なランタイムライブラリがない
3. **CPU非対応** - AVX2命令セットが必要

---

## 解決方法

### 方法1: アプリ内自動ダウンロード（推奨）

1. アプリを起動
2. **Settings** タブを開く
3. **"Download llama.cpp"** ボタンをクリック
4. ダウンロード完了後、モデルをロード

これで互換性のあるllama.dllが自動的にダウンロードされます。

---

### 方法2: Visual C++ Redistributableのインストール

llama.dllは Visual C++ ランタイムに依存しています。

**ダウンロード:**
https://aka.ms/vs/17/release/vc_redist.x64.exe

**インストール後:**
1. PCを再起動
2. アプリを再起動

---

### 方法3: 互換性のあるllama.dllを手動ダウンロード

#### 推奨ソース

**Option A: 公式ビルド（最新版）**
```
https://github.com/ggerganov/llama.cpp/releases
```
- `llama-<version>-bin-win-avx2-x64.zip` をダウンロード
- `llama.dll` を抽出

**Option B: 安定版（推奨）**
```
https://github.com/ggerganov/llama.cpp/releases/tag/b3600
```
- b3600以降の安定版を使用

#### 配置場所

```
nezumi-ai/
└── desktop/
    └── libs/
        └── windows/
            └── llama.dll  ← ここに配置
```

または、システムパスに配置：
- `C:\Windows\System32\llama.dll`
- または `PATH` 環境変数に追加

---

### 方法4: llama.cppを自分でビルド

最も確実な方法です。

#### 必要なツール
- CMake 3.14+
- Visual Studio 2019+ (C++ ビルドツール)
- Git

#### ビルド手順

```bash
# 1. llama.cppをクローン
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp

# 2. ビルド（CPU版）
cmake -B build -DBUILD_SHARED_LIBS=ON
cmake --build build --config Release

# 3. llama.dllをコピー
copy build\bin\Release\llama.dll C:\Users\mouse\AndroidStudioProjects\nezumiai\desktop\libs\windows\
```

#### GPU対応ビルド（NVIDIA）

```bash
cmake -B build -DBUILD_SHARED_LIBS=ON -DGGML_CUDA=ON
cmake --build build --config Release
```

**必要:** CUDA Toolkit 11.8+

---

## その他のエラー

### "llama.cpp library not found"

**原因:** llama.dllが見つからない

**解決:**
1. `desktop/libs/windows/llama.dll` が存在するか確認
2. または Settings → "Download llama.cpp" を実行

---

### "Model file not found"

**原因:** モデルファイルのパスが間違っている

**解決:**
1. Settings → Model Path を確認
2. GGUFファイルが実際に存在するか確認
3. パスに日本語や特殊文字が含まれていないか確認

---

### "Failed to load model"

**原因:** モデルファイルが破損しているか、形式が間違っている

**解決:**
1. モデルを再ダウンロード
2. GGUF形式であることを確認（.gguf拡張子）
3. ファイルサイズが正しいか確認

---

### メモリ不足エラー

**原因:** モデルが大きすぎる

**解決:**
1. より小さいモデルを使用（Gemma 2B推奨）
2. 量子化レベルを下げる（Q4_K_M → Q3_K_M）
3. Context Size を減らす（Settings → Context Size）

---

## デバッグ情報の収集

### ログの確認

アプリ起動時のコンソール出力を確認：

```bash
./gradlew :desktop:run > debug.log 2>&1
```

### システム情報

```bash
# CPU情報
wmic cpu get name

# メモリ情報
wmic memorychip get capacity

# Visual C++ Redistributable確認
wmic product where "name like '%Visual C++%'" get name,version
```

---

## サポート

問題が解決しない場合：

1. **GitHub Issues**: https://github.com/mouse0329/nezumi-ai/issues
2. 以下の情報を含めてください：
   - OS バージョン
   - CPU モデル
   - RAM容量
   - エラーメッセージ全文
   - `debug.log` の内容

---

## よくある質問

### Q: AVX2非対応CPUでも動作しますか？

A: 基本的にAVX2が必要です。古いCPUの場合は、AVX2なしでllama.cppをビルドする必要があります：

```bash
cmake -B build -DBUILD_SHARED_LIBS=ON -DGGML_AVX2=OFF
```

### Q: GPUを使いたい

A: 現在はCPU推論のみサポートしています。GPU対応は今後のアップデートで追加予定です。

### Q: Macでも動作しますか？

A: はい。`setup-llama.sh` を実行してください。Metal（GPU）対応も可能です。

### Q: Linuxでも動作しますか？

A: はい。`setup-llama.sh` を実行してください。

---

## 関連ドキュメント

- [LLAMA_SETUP.md](LLAMA_SETUP.md) - 詳細なセットアップガイド
- [README.md](README.md) - 基本的な使い方
- [llama.cpp公式](https://github.com/ggerganov/llama.cpp) - 最新情報
