# nezumi-ai

**ローカルAIチャットアプリ** — Android上で完全にオフラインで動作するAIチャットアプリケーション  
オンデバイス推論に特化しています。

---

## 概要

nezumi-aiは、インターネット接続なしで動作するプライベート性の高いAIアシスタントです。以下の特徴があります：

- **完全オフライン動作**: ローカル推論で、サーバーへのデータ送信なし
- **マルチモデル対応**: Gemma 4 2B (軽量) / 4B (高性能) + Gemma 3n の選択可能
- **GPU/CPU/NPU自動切り替え**: 端末のハードウェア最適化による高速化
- **画像入力対応**: カメラ・ギャラリーから画像を取り込んでAIに解析させられる
- **画像生成機能**: LocalDreamModule（MNN/QNN）による高速画像生成
- **チャット履歴管理**: Room DBで会話履歴を永続化
- **高度なツールコール**: AIが画像生成、アラーム設定、Web検索などのツールを自律的に呼び出し

> **注意**: VOICEVOX音声読み上げ機能は、Android 15以降の16KBページサイズ端末との互換性問題のため、現在無効化されています。詳細は [VOICEVOX_RESTORE.md](docs/VOICEVOX_RESTORE.md) を参照してください。

---

## 必要環境

| 項目 | 最小値 | 推奨値 |
|------|--------|--------|
| **Android Version** | 12 (API 30) | 14+ (API 34+) |
| **RAM** | 6GB | 8GB以上 |
| **ストレージ** | 4GB | 8GB以上 |
| **GPU/NPU** | 任意 | Snapdragon (QNN対応) / Mali / Adreno推奨 |

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
- **Gemma 3n E2B / E4B**: レガシーモデル（互換性維持）

### 3. 画像生成機能
- **LocalDreamModule**: MNN/QNNバックエンドによる高速画像生成
- **NPU対応**: Snapdragon端末でのNPUアクセラレーション
- **自動バックエンド選択**: QNN（NPU）→ MNN（CPU/OpenCL）の自動フォールバック
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
    - **特徴**: JNI経由で`llama.cpp`を直接制御。16KBページサイズ対応、適応的GPU層数、サンプラーキャッシングなどの高度なネイティブ最適化が施されています。
2.  **LiteRT-LMエンジン (Google LiteRT)**
    - **用途**: Gemma 3nなどのTFLite形式モデルの実行。
    - **特徴**: Googleの`litertlm`ライブラリを活用。特にSnapdragon搭載端末において、QNN（Qualcomm AI Stack）を通じた**NPUアクセラレーション**を強力にサポートし、低消費電力かつ高速なレスポンスを実現します。
3.  **LocalDreamエンジン (MNN/QNN)**
    - **用途**: 画像生成（Stable Diffusion 1.5）。
    - **特徴**: `stable-diffusion.cpp`から移行し、MNN OpenCLおよびQNN NPUバックエンドを統合。Snapdragon 8 Gen 2クラスの端末では、数秒での画像生成が可能です。

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

-   **16KBページサイズへの挑戦**: Android 15以降を見据え、すべてのネイティブライブラリを16KBアライメントで最適化。VOICEVOX機能の一時停止も、この互換性を最優先した苦渋の決断であり、現在は復元に向けた調整が進んでいます。
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
  - 推論エンジン: llama.cpp (GGUF), LiteRT-LM (TFLite), MNN/QNN (画像生成)
  - LLMモデル: Gemma 4 (2B/4B), Gemma 3n (E2B/E4B)
  - 画像生成: Stable Diffusion 1.5 (MNN/QNN)

### VOICEVOX音声読み上げについて

現在、VOICEVOX機能は **一時的に無効化** されています。理由は以下の通りです：

- VOICEVOX CORE 0.16.4 に同梱の ONNX Runtime 1.17.3 は 4KB ページアライメントでビルドされている
- Android 15 以降の一部端末（Pixel 6 以降など）は 16KB ページサイズを使用
- 4KB アライメントのネイティブライブラリは 16KB 端末で `dlopen` 失敗してクラッシュ
- Google Play Console は 16KB 非対応 APK の新規アップロードを拒否

**復元方法**: ONNX Runtime 1.18.0 以降（16KB 対応版）へのアップグレード後、[VOICEVOX_RESTORE.md](docs/VOICEVOX_RESTORE.md) の手順に従ってください。

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
- **LocalDreamModule**: MNN/QNNベースの画像生成エンジン

### UI Components
- **Halilibo Compose Richtext**: Markdown表示
- **Coil**: 非同期画像ロード
- **Material3**: Compose Material Design

### 認証・その他
- **AppAuth**: OAuth 2.0 フロー
- **Kotlin Coroutines**: 非同期処理

詳細ライセンス情報は [NOTICE](NOTICE) を参照してください。

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
