# ネズミAI for Desktop

**ネズミソフト / INTERNAL PLANNING DOC / v0.1**

# ネズミAI  
*for Desktop*

Kotlin Multiplatform + Compose Desktop — 設計・開発計画書

## 01 — Overview

既存ネズミAI Androidコードベースを Kotlin Multiplatform (KMP) に移行し、 Compose Desktop (JVM) でWindows/macOS/Linux向けデスクトップアプリを提供する。 コード共有率最大化・推論バックエンド統一・UIロジック共有を原則とする。

LiteRT-LM の `litertlm-jvm` パッケージにより、 Android/Desktop間でオンデバイス推論コードをほぼ共有できる。

## 02 — Module Structure

```
// プロジェクト構成
nezumiai/
├── app/              // Android thin shell
├── desktop/          // Compose Desktop entry point
└── shared/
    ├── commonMain/   // ほぼ全ロジック + UI
    ├── androidMain/  // Android固有
    └── desktopMain/  // JVM固有
```

### commonMain
- ViewModel / Repository 全層
- Anthropic / Gemini / OpenAI APIクライアント
- SwitchBot HTTP API
- ツール定義（アラーム除く）
- チャット履歴 (SQLDelight)
- コンテキスト圧縮ロジック
- VOICEVOX HTTP クライアント
- InferenceBackend 抽象インターフェース
- Compose UI（大部分）

### androidMain
- litertlm-android
- アラーム / カレンダー / Gmail
- NotificationManager
- Porcupine ウェイクワード
- sd.cpp JNI (Android ABI)
- FLAG_SECURE

### desktopMain
- litertlm-jvm
- OS通知 (JVM / AWT)
- ファイルシステムアクセス
- sd.cpp JNI (x64/ARM64)
- ウィンドウ管理
- ホットキー / トレイアイコン

## 03 — Inference Backend

```kotlin
// commonMain
interface InferenceBackend {
    suspend fun generate(prompt: String): Flow<String>
    suspend fun load(modelPath: String)
    fun unload()
}

// androidMain + desktopMain — API同一なので抽象クラスで共有
class LiteRTBackend(config: EngineConfig) : InferenceBackend {
    // litertlm-android / litertlm-jvm どちらも同一Kotlin API
}

class GGUFBackend : InferenceBackend   // expect/actual でJNI呼び出し
class ApiBackend  : InferenceBackend   // commonMain — Anthropic/Gemini
```

**実装メモ（Compose Desktop 現行）**： `DesktopLlmServices.llamaEngine` を設定画面とチャットで共有する。`LlamaCppEngine` を ViewModel ごとに new すると、設定でロードした GGUF がチャット側に届かない。 モデルロード前はエラーハンドリングで適切なメッセージを表示し、ユーザーに設定画面からモデルをロードするよう促す。

| バックエンド | commonMain | androidMain | desktopMain | 備考 |
|-------------|------------|-------------|-------------|------|
| LiteRT-LM | 抽象 ◎ | litertlm-android | litertlm-jvm | API完全同一 |
| GGUF (llama.cpp) | 抽象 ◎ | JNI | JNI 別ビルド | expect/actual |
| Anthropic API | ◎ | — | — | HTTP共通 |
| Gemini API | ◎ | — | — | HTTP共通 |
| sd.cpp | — | JNI | JNI 別ビルド | 画像生成 |

## 04 — Performance

| 項目 | Android (ART) | Desktop (JVM) | 評価 |
|------|---------------|---------------|------|
| 推論メモリ (Gemma 4 2B int4) | ~1.5 GB | ~1.5 GB | 同等 |
| ランタイムオーバーヘッド | ~50 MB (ART) | ~300 MB (JVM) | 誤差レベル |
| 起動時間 | — | JVM+モデルロードで数秒 | 許容範囲 |
| 配布サイズ | APK ~50MB | jpackage ~80MB+ | jpackage最適化で削減可 |
| GPU加速 (LiteRT) | OpenCL / OpenGL | OpenCL / WebGPU | 両対応 |
| Electron比 | — | メモリ1/3以下 | 圧勝 |

## 05 — Phases

### Phase 1: FOUNDATION - KMP マルチモジュール化
- 既存Androidプロジェクトをshared/app/desktopに分割
- build.gradle.kts KMP設定
- SQLDelight → commonMain移行
- Kotlinx Coroutines / Serialization 共通化

### Phase 2: INFERENCE - 推論バックエンド抽象化
- InferenceBackend interface 定義
- LiteRTBackend commonMain実装
- ApiBackend (Anthropic/Gemini) 移行
- desktopMainにlitertlm-jvm接続

### Phase 3: UI - Compose UI 共通化
- チャット画面 → commonMain
- 設定画面 → commonMain
- Desktop用ウィンドウ/トレイ実装
- Android固有UI分離

### Phase 4: TOOLS - ツール・エージェント移行
- ツール定義全体をcommonMainへ
- アラーム/カレンダー → androidMain expect/actual
- Desktop向け代替ツール実装
- VOICEVOX HTTPクライアント共通化

### Phase 5: DISTRIBUTION - パッケージング・配布
- jpackage でインストーラ生成
- JRE最小化 (jlink)
- Windows (.msi) / macOS (.dmg) / Linux (.deb)
- GitHub Actions CI

## 06 — Risks

| Risk | Description | Level |
|------|-------------|-------|
| JNIビルド | sd.cpp / llama.cpp のWindows向けクロスコンパイル。CMake設定が別途必要。 | MID |
| Compose互換 | Android Compose APIとDesktop Compose APIの差異。一部コンポーネントがplatform固有。 | MID |
| アラームツール | Desktopでのアラーム実装はOS依存。Windows Task Scheduler or Kotlinタイマー。 | LOW |
| litertlm-jvm GPU | WindowsでのOpenCL対応はドライバ依存。フォールバックCPUは確実に動作。 | LOW |
| 配布サイズ | JRE同梱で肥大化。jlinkで使用モジュールのみに絞ることで緩和可能。 | LOW |

## 07 — Key Dependencies

| ライブラリ | 用途 | 対象 |
|-----------|------|------|
| litertlm-android | LiteRT-LM推論 | androidMain |
| litertlm-jvm | LiteRT-LM推論 (Windows/Linux/mac) | desktopMain |
| SQLDelight | チャット履歴DB | commonMain |
| Ktor Client | HTTP (API/VOICEVOX/SwitchBot) | commonMain |
| Compose Multiplatform | UI | commonMain |
| kotlinx.coroutines | 非同期処理 | commonMain |
| kotlinx.serialization | JSON | commonMain |
| io.github.ljcamargo:llamacpp-kotlin | GGUF推論 | android/desktop |

---

ネズミソフト — 内部ドキュメント  
ネズミAI for Desktop / KMP計画書 v0.1