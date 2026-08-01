# nezumi-ai

**ローカルAIチャットアプリ** — Android上で完全にオフラインで動作するAIチャットアプリケーション  
オンデバイス推論に特化しています。

---

## 概要

nezumi-aiは、インターネット接続なしで動作するプライベート性の高いAIアシスタントです。以下の特徴があります：

- **完全オフライン動作**: ローカル推論で、サーバーへのデータ送信なし
- **マルチモデル対応**: Gemma 4 2B (軽量) / 4B (高性能) + Gemma 3n の選択可能
- **GPU/CPU/NPU(対応機種のみ)切り替え**: 端末のハードウェア最適化による高速化
- **画像入力対応**: カメラ・ギャラリーから画像を取り込んでAIに解析させられる
- **画像生成機能**: MNN 画像生成エンジンによる高速画像生成

- **チャット履歴管理**: Room DBで会話履歴を永続化
- **高度なツールコール**: AIが画像生成、アラーム設定、Web検索などのツールを自律的に呼び出し
- **MCP クライアント対応**: Streamable HTTP / SSE の Model Context Protocol サーバーを登録し、プリセットごとに紐付けて外部ツールを呼び出せる

- **VOICEVOX 音声読み上げ**: 端末内で日本語音声を合成。標準話者は「ずんだもん / ノーマル」。音声モデルはモデルダウンロード機構で進捗表示付きに取得できます（[利用規約とクレジット表記](docs/VOICEVOX_TERMS.md)）

---

## 必要環境

| 項目 | 最小値 | 推奨値 |
|------|--------|--------|
| **Android Version** | 12 (API 30) | 14+ (API 34+) |
| **RAM** | 6GB | 8GB以上 |
| **ストレージ** | 4GB | 8GB以上 |
| **GPU/NPU** | 任意 | Snapdragon / Mali / Adreno推奨 |


---

## インストール

### ビルド手順

```bash
# 1. リポジトリをクローン
git clone https://github.com/mouse0329/nezumi-ai.git
cd nezumi-ai

# 2. サブモジュールを初期化
git submodule update --init --recursive

# 3. local.properties を設定
# (SDK, NDK, 署名情報を設定 - 詳細は下記参照)

# 4. Gradleでビルド
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK (署名設定必須)
```

### local.properties の設定

```properties
sdk.dir=/path/to/Android/sdk
ndk.dir=/path/to/Android/ndk

# リリース署名設定（オプション）
STORE_FILE=/path/to/keystore.jks
STORE_PASSWORD=your_keystore_password
KEY_ALIAS=key_alias
KEY_PASSWORD=your_key_password
```

---

## 主な機能

### 1. チャット機能
- リアルタイムストリーミング表示
- テキストと画像の複合入力
- セッション分岐対応

### 2. モデル設定
- **Gemma 4 2B**: 軽量・高速（低スペック端末推奨）
- **Gemma 4 4B**: 高精度・高性能（ハイエンド端末推奨）
- **Gemma 3n E2B / E4B**: レガシーモデル（互換性維持）※廃止

### 3. 画像生成機能
- **MNN 画像生成エンジン**: MNNバックエンドによる高速画像生成
- **自動バックエンド選択**: GPU→CPU の自動フォールバック（MNN OpenCL / CPU を利用）

- **AI自動生成**: Gemmaがツールとして画像生成を呼び出し（ユーザー承認制）

### 4. 推論バックエンド切り替え（LLM）
- **NPU**: Snapdragon QNNによる超高速・低消費電力推論（LiteRT-LM）
- **GPU**: 高速推論（互換性は端末依存）
- **CPU**: 互換性重視（速度は低い）
- 自動フォールバック: NPU/GPU失敗時にCPUに自動切り替え

### 5. チャット履歴
- 会話履歴の永続化（Room DB）
- セッション削除・編集機能

---

## システム構成と技術詳細

`nezumi-ai`は、高度なローカル推論を実現するために最適化された多層アーキテクチャを採用しています。(拡張し続けたら勝手にできた)

### アーキテクチャ概要

<img width="3120" height="880" alt="image" src="https://github.com/user-attachments/assets/15b70f56-f39c-4310-acab-c1c183be97cb" />


### 主要な推論エンジン

本アプリは、モデルの特性に合わせて以下のエンジンを使い分けるハイブリッド構成となっています。

1.  **GGUFエンジン (llama.cpp)**
    - **用途**: 汎用的なGGUF形式モデル（Gemma 4 2B/4Bなど）の実行。
    - **特徴**: JNI経由で`llama.cpp`を直接制御。適応的GPU層数、サンプラーキャッシングなどの高度なネイティブ最適化が施されています。
2.  **LiteRT-LMエンジン (Google LiteRT)**
    - **用途**: Gemma 3nなどのTFLite形式モデルの実行。
    - **特徴**: Googleの`litertlm`ライブラリを活用。特にSnapdragon搭載端末において、QualcommのAIスタックを通じた**NPUアクセラレーション**をサポートし、低消費電力かつ高速なレスポンスを実現します。
3.  **MNN 画像生成エンジン**
    - **用途**: 画像生成（Stable Diffusion 1.5）。


### セーフティ & メディア管理

プライバシーと安全性を両立させるための専用レイヤーを備えています。

-   **コンテンツフィルタリング**: `PromptFilter`による不適切なプロンプトの遮断。
-   **画像セーフティチェック**: `ImageSafetyChecker`による生成画像のNSFW判定。不適切な場合は自動的にブロックまたはぼかし処理（Blur）を適用します。
-   **セキュアなメディア保存**: `MessageMediaStore`と`FileProvider`を組み合わせ、生成されたメディアをアプリ専用領域に安全に永続化します。

### 設定の永続化と管理

`PreferencesHelper`を通じて、モデルパラメータ（Steps, CFG, Backend等）やUI設定を`SharedPreferences`に即座に反映・永続化します。これにより、ユーザーごとに最適化された推論環境を維持します。

---

## 開発ストーリー：技術的こだわり

`nezumi-ai`は、単なるチャットアプリではなく、Androidデバイスの限界に挑むプロジェクトとして開発されています。

-   **ネイティブライブラリの最適化**: Android 15 以降を見据え、すべてのネイティブライブラリを最新の実行環境に合わせて最適化。VOICEVOX ランタイムを含め、全モジュールが最新の Android 端末で動作します。
-   **NPUの真価を引き出す**: モバイルGPUだけでなく、NPU（Neural Processing Unit）を積極的に活用することで、スマートフォンの発熱を抑えつつ、デスクトップ級のAI体験を提供することを目指しています。
-   **プライバシー・ファースト**: すべての推論、フィルタリング、保存プロセスをローカルで完結させることで、ユーザーのデータがデバイスの外に出ることは一切ありません。

---

## チームメッセージ

「**プライバシーを守りながら、あなたの創造性を解き放つ。nezumi-aiは、手のひらで未来を動かすAIアシスタントです！**」

---

## 開発状況

- **現在のバージョン**: v1.0.0
- **主要コンポーネント**:
  - UI/UX: Jetpack Compose
  - データベース: Room
    - 推論エンジン: llama.cpp (GGUF), LiteRT-LM (TFLite), MNN (画像生成)
  - LLMモデル: Gemma 4 (2B/4B), Gemma 3n (E2B/E4B)
    - 画像生成: Stable Diffusion 1.5 (MNN)


### VOICEVOX音声読み上げについて

VOICEVOX 音声読み上げは **有効** です。ランタイムの互換性問題は解決済みのため、アプリ内に注意書きは表示していません。

- **標準話者**: ずんだもん / ノーマル（`0.vvm` / styleId 3）
- **音声モデルの取得**: LLM モデルと同じ `ModelDownloadWorker` 経由で取得します。
  進捗バー・通知・バックグラウンド継続・中止に対応しています。
- **OpenJTalk 辞書**: 音声モデルの取得と同じフローで、未取得のときだけ続けてダウンロードします。

**クレジット表記**: 生成した音声を公開・配布する場合、VOICEVOX を利用した旨と話者ごとのクレジット表記が必要です。
アプリの「設定 › モデル › 音声読み上げ」および「設定 › ライセンス」から確認できます。
本アプリのカタログには、クレジット表記のみで商用・非商用ともに利用可能な音声ライブラリのみを収録しています。
詳細は [docs/VOICEVOX_TERMS.md](docs/VOICEVOX_TERMS.md) を参照してください。

詳細な開発計画については [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) を参照してください。

---

## 依存ライブラリ

### Core Frameworks
- **Jetpack Compose**: Modern UI toolkit
- **AndroidX Navigation**: アプリ内ナビゲーション
- **Room**: ローカルデータベース

### AI/ML
- **MediaPipe Tasks**: オンデバイスML実行
- **TensorFlow Lite / LiteRT**: 軽量推論エンジン
- **llama.cpp** (via JNI): LLM推論コア
-- **MNN 画像生成エンジン**: 画像生成モジュール


### UI Components
- **Halilibo Compose Richtext**: Markdown表示
- **Coil**: 非同期画像ロード
- **Material3**: Compose Material Design

### 認証・その他
- **AppAuth**: OAuth 2.0 フロー
- **Kotlin Coroutines**: 非同期処理

詳細ライセンス情報は [NOTICE](NOTICE) を参照してください。NOTICE は現行の Android アプリ配布構成に合わせて整理しています。

---

## 使用方法

### 初回起動
1. アプリを起動
2. モデルを選択（Gemma 4 2B / 4B 推奨）
3. バックエンド選択（NPU / GPU / CPU）
4. モデルダウンロード開始

### チャット開始
1. テキストを入力 → 送信
2. （オプション）画像を添付 → 送信
3. AIの返答をストリーミング表示で確認

### 設定変更
- **Settings** → **Model Config**
- バックエンド、モデルサイズを変更
- 自動フォールバック設定の有効化/無効化

---

## パフォーマンス目標

| 指標 | 目標値 |
|-----|--------|
| 起動時間 | < 3秒 |
| 初回推論時間（2B） | < 3秒 |
| 初回推論時間（4B） | < 8秒 |
| ピークメモリ（2B） | < 3GB |
| ピークメモリ（4B） | < 5GB |

---

## ライセンス

このプロジェクトは **デュアルライセンス** で公開されています。以下のいずれかを選択して使用できます：

- **GNU Lesser General Public License v3.0 (LGPL v3)** - https://www.gnu.org/licenses/lgpl-3.0.html
- **別途商用ライセンス** - mouse0329 に連絡して取得

詳細は以下を参照してください：
- [LICENSE.md](LICENSE.md) - プロジェクトライセンス（LGPL v3 / 商用別ライセンス）
- [LGPL_LICENSE](LGPL_LICENSE) - LGPL v3 の全文
- [NOTICE](NOTICE) - 依存ライブラリ、バンドル済みランタイム、モデル関連のライセンス情報

### 主要な依存ライブラリ

| ライブラリ | ライセンス |
|---|---|
| AndroidX / Jetpack Compose | Apache 2.0 |
| Kotlin / Coroutines | Apache 2.0 |
| MediaPipe Tasks (GenAI) | Apache 2.0 |
| TensorFlow Lite / LiteRT | Apache 2.0 |
| Halilibo Compose Richtext | Apache 2.0 |
| Coil (Image Loading) | Apache 2.0 |
| AppAuth for Android | Apache 2.0 |

---

## 関連ドキュメント

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - アーキテクチャ詳細
- [`docs/BUILD_ERROR_FIX.md`](docs/BUILD_ERROR_FIX.md) - ビルドエラー修正記録
- [`docs/GGUF_ENGINE_STATUS.md`](docs/GGUF_ENGINE_STATUS.md) - GGUF エンジン状況
- [`docs/LLAMA_OPTIMIZATION.md`](docs/LLAMA_OPTIMIZATION.md) - Llama 最適化ドキュメント
- [`docs/OPTIMIZATION_COMPLETION_REPORT.md`](docs/OPTIMIZATION_COMPLETION_REPORT.md) - 最適化完了報告
- [`docs/VOICEVOX_RESTORE.md`](docs/VOICEVOX_RESTORE.md) - VOICEVOX 復旧手順

---

## 貢献について

バグ報告、機能リクエスト、プルリクエストを歓迎します。  
コントリビュートの際は、コードの品質とパフォーマンスを維持してください。
