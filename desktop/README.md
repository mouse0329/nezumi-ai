# nezumi-ai Desktop

**Kotlin/JVM + Compose Desktop + llama.cpp + MCP連携**

Android版のnezumi-aiをデスクトップ環境に移植したバージョンです。

---

## 特徴

- **Compose Desktop**: Android版のUIコードを90%以上再利用
- **llama.cpp統合**: GGUF形式のモデルをローカル推論
- **MCP Server**: Claude Desktop等のMCPクライアントと連携可能
- **クロスプラットフォーム**: Windows / Linux / macOS対応

---

## 必要環境

| 項目 | 要件 |
|------|------|
| **JDK** | 21以上 |
| **OS** | Windows 10+, macOS 11+, Ubuntu 20.04+ |
| **RAM** | 8GB以上推奨 |
| **GPU** | CUDA (NVIDIA) / Metal (macOS) / CPU |

---

## クイックスタート

### 超簡単セットアップ（推奨）

1. **アプリを起動**
```bash
./gradlew :desktop:run
```

2. **Settings タブを開く**

3. **「Download llama.cpp」ボタンをクリック**
   - 自動的にllama.cppライブラリがダウンロードされます

4. **推奨モデルから選んでダウンロード**
   - Gemma 2B (1.7 GB) - 軽量・高速
   - Gemma 7B (4.9 GB) - 高性能
   - Qwen 2.5 3B (2.0 GB) - バランス型

5. **「Load Model」をクリック**

6. **Chat タブで会話開始！**

---

### 手動セットアップ（上級者向け）

#### 1. llama.cppライブラリの準備

詳細は [LLAMA_SETUP.md](LLAMA_SETUP.md) を参照してください。

**クイック手順**:
```bash
# llama.cppをクローン&ビルド
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp
cmake -B build -DBUILD_SHARED_LIBS=ON
cmake --build build --config Release

# ライブラリをコピー
# Windows: build/bin/Release/llama.dll → desktop/libs/windows/
# Linux: build/libllama.so → desktop/libs/linux/
# macOS: build/libllama.dylib → desktop/libs/macos/
```

#### 2. モデルファイルのダウンロード

```bash
# Hugging Face CLIでダウンロード
huggingface-cli download google/gemma-2b-it-GGUF gemma-2b-it-q4_k_m.gguf
```

または手動でダウンロード:
- [Gemma 2B GGUF](https://huggingface.co/google/gemma-2b-it-GGUF)
- [Gemma 7B GGUF](https://huggingface.co/google/gemma-7b-it-GGUF)
- [Qwen 2.5 3B GGUF](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF)

#### 3. アプリケーションの起動

```bash
./gradlew :desktop:run
```

---

## トラブルシューティング

**"Invalid memory access"** や **"Failed to load llama.cpp"** エラーが出る場合：

→ [TROUBLESHOOTING.md](TROUBLESHOOTING.md) を参照してください

**クイック解決法:**
1. Settings → "Download llama.cpp" ボタンをクリック
2. Visual C++ Redistributable をインストール: https://aka.ms/vs/17/release/vc_redist.x64.exe

---

## 使用方法

### 基本的なチャット

1. アプリを起動
2. Settings → Model Path にGGUFモデルのパスを設定
3. Chat画面でメッセージを送信

### MCP連携

MCP Serverは起動時に自動的に `http://localhost:3000` で起動します。

#### Claude Desktopとの連携例

`~/.config/claude/config.json` に以下を追加：

```json
{
  "mcpServers": {
    "nezumi-ai": {
      "url": "http://localhost:3000",
      "tools": ["generate_text", "get_context"]
    }
  }
}
```

---

## プロジェクト構造

```
desktop/
├── src/main/kotlin/com/nezumi_ai/desktop/
│   ├── Main.kt                    # エントリーポイント
│   ├── ui/                        # Compose Desktop UI
│   ├── viewmodel/                 # 状態管理
│   ├── inference/                 # llama.cpp統合
│   ├── mcp/                       # MCP Server実装
│   └── data/                      # データ層
├── src/main/resources/            # リソースファイル
├── libs/                          # ネイティブライブラリ
│   ├── windows/llama.dll
│   ├── linux/libllama.so
│   └── macos/libllama.dylib
├── build.gradle.kts               # ビルド設定
├── README.md                      # このファイル
├── LLAMA_SETUP.md                 # llama.cpp詳細ガイド
├── setup-llama.bat                # Windows自動セットアップ
└── setup-llama.sh                 # Linux/macOS自動セットアップ
```

---

## 次のステップ

### Phase 1: llama.cpp統合 ✅

- [x] JNAでllama.cppのネイティブライブラリをロード
- [x] 基本的な推論APIの実装
- [x] 自動ダウンロード機能
- [ ] ストリーミング推論の完全実装
- [ ] GPU (CUDA/Metal) サポート
- [ ] バッチ処理の最適化

**セットアップ手順**: [LLAMA_SETUP.md](LLAMA_SETUP.md) を参照

### Phase 2: UI機能 🚧

- [x] Markdown表示 (richtext-commonmark)
- [x] 画像表示 (Coil 3.x)
- [ ] ダークモード切り替え
- [ ] コードハイライト

### Phase 3: データベース統合

- [ ] Exposedでチャット履歴の永続化
- [ ] セッション管理機能
- [ ] エクスポート/インポート機能

### Phase 4: MCP機能拡張

- [x] 基本的なMCP Server実装
- [ ] ツール追加 (画像生成、Web検索等)
- [ ] WebSocket対応
- [ ] 認証機能

---

## Android版との違い

| 機能 | Android | Desktop |
|------|---------|---------|
| **UI Framework** | Jetpack Compose | Compose Desktop |
| **推論エンジン** | LiteRT-LM / llama.cpp | llama.cpp |
| **データベース** | Room | Exposed (SQLite) |
| **画像生成** | MNN/QNN | (未実装) |
| **MCP連携** | なし | **あり** |

---

## ライセンス

Android版と同じく **LGPL v3 / 商用別ライセンス** のデュアルライセンスです。

詳細は [../LICENSE.md](../LICENSE.md) を参照してください。

---

## 開発者向け

### デバッグ実行

```bash
./gradlew :desktop:run --args="--debug"
```

### ログ出力

```kotlin
println("Debug: $message")  // 標準出力
```

### MCP Server テスト

```bash
curl http://localhost:3000/health
curl http://localhost:3000/mcp/tools
```

---

## 貢献

Pull Requestを歓迎します！

1. Fork
2. Feature branchを作成
3. Commitしてpush
4. Pull Requestを作成
