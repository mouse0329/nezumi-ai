# nezumi-ai

**ローカルAIチャットアプリ** — Android上で完全にオフラインで動作するAIチャットアプリケーション  
オンデバイス推論に特化しています。

---

## 概要

nezumi-aiは、インターネット接続なしで動作するプライベート性の高いAIアシスタントです。以下の特徴があります：

- **完全オフライン動作**: ローカル推論で、サーバーへのデータ送信なし
- **マルチモデル対応**: Gemma 3n E2B (軽量) / E4B (高性能) の選択可能
- **GPU/CPU自動切り替え**: 端末のハードウェア最適化による高速化
- **画像入力対応**: カメラ・ギャラリーから画像を取り込んでAIに解析させられる
- **画像生成機能**: LocalDreamModule（MNN/QNN）による高速画像生成
- **チャット履歴管理**: Room DBで会話履歴を永続化

---

## 必要環境

| 項目 | 最小値 | 推奨値 |
|------|--------|--------|
| **Android Version** | 12 (API 30) | 14+ (API 34+) |
| **RAM** | 6GB | 8GB以上 |
| **ストレージ** | 4GB | 8GB以上 |
| **GPU** | 任意 | Mali / Adreno推奨 |

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
- **Gemma 3n E2B**: 軽量・高速（低スペック端末推奨）
- **Gemma 3n E4B**: 高精度・高性能（ハイエンド端末推奨）

### 3. 画像生成機能
- **LocalDreamModule**: MNN/QNNバックエンドによる高速画像生成
- **NPU対応**: Snapdragon端末でのNPUアクセラレーション
- **自動バックエンド選択**: QNN（NPU）→ MNN（CPU/OpenCL）の自動フォールバック
- **AI自動生成**: Gemmaがツールとして画像生成を呼び出し（ユーザー承認制）

### 4. GPU/CPU バックエンド切り替え（LLM）
- **GPU**: 高速推論（互換性は端末依存）
- **CPU**: 互換性重視（速度は低い）
- 自動フォールバック: GPU失敗時にCPUに自動切り替え

### 5. チャット履歴
- 会話履歴の永続化（Room DB）
- セッション削除・編集機能

---

## アーキテクチャ

```
[UI Layer (Compose)]
       ↓
[ViewModel / StateFlow]
       ↓
[Repository]
       ↓
[UseCase / Inference Layer]
       ↓
[Engine Layer]
├── LlmEngine (llama.cpp / LiteRT-LM)
└── SdEngine (MNN/QNN Stable Diffusion)
```

---

## 開発状況

- **現在のバージョン**: v0.x (開発中)
- **ターゲットリリース**: v1.0.0
- **主要コンポーネント**:
  - UI/UX: Jetpack Compose
  - データベース: Room
  - 推論エンジン: llama.cpp, MNN/QNN
  - モデル: Gemma 3n, Stable Diffusion 1.5

詳細な開発計画については [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) を参照してください。
       ↓
[MediaPipe / TensorFlow Lite]
       ↓
[Native Bridge (JNI) → llama.cpp]
       ↓
[Room DB]
```

### 主要レイヤー

| レイヤー | 責務 |
|---------|------|
| **Presentation** | Jetpack Compose UI, ViewModel |
| **Domain** | ビジネスロジック, UseCase |
| **Data** | Repository, Room DB, 設定保存 |
| **Inference** | モデルロード, 推論実行, バックエンド選択 |
| **Native** | JNI Bridge, llama.cpp バインディング |

---

## 依存ライブラリ

### Core Frameworks
- **Jetpack Compose**: Modern UI toolkit
- **AndroidX Navigation**: アプリ内ナビゲーション
- **Room**: ローカルデータベース

### AI/ML
- **MediaPipe Tasks**: オンデバイスML実行
- **TensorFlow Lite**: 軽量推論エンジン
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
2. モデルを選択（E2B / E4B）
3. バックエンド選択（GPU / CPU）
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
| 初回推論時間（E2B） | < 5秒 |
| 初回推論時間（E4B） | < 10秒 |
| ピークメモリ（E2B） | < 3GB |
| ピークメモリ（E4B） | < 5GB |

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
| TensorFlow Lite | Apache 2.0 |
| Halilibo Compose Richtext | Apache 2.0 |
| Coil (Image Loading) | Apache 2.0 |
| AppAuth for Android | Apache 2.0 |

### 使用モデル

- **Google Gemma 3n** (E2B / E4B) - [Gemma Terms](https://ai.google.dev/gemma/terms)
- **Hugging Face Hub** - モデル配布ページ参照
