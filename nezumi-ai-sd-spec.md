# nezumi-ai — stable-diffusion.cpp 組み込み仕様書

**バージョン**: 1.2  
**作成日**: 2026-05-04  
**更新日**: 2026-05-04（Codexレビュー×2 反映）  
**対象**: nezumi-ai Android アプリ (mouse0329/nezumi-ai)

---

## 1. 概要

nezumi-ai に stable-diffusion.cpp をJNIで組み込み、以下2つのルートで画像生成機能を提供する。

- **ルートA**: Gemma4 がツールとして自動的に `generateImage` を呼び出す（ユーザー承認あり）
- **ルートB**: ユーザーが専用ページで直接プロンプトを入力して生成する

---

## 2. 対象環境

| 項目 | 内容 |
|------|------|
| 実機 | Pixel 8a |
| 仮想 | Pixel 9 エミュレータ |
| Android | API 26以上 |
| NDK | 26.x |
| ABI | arm64-v8a |
| 推奨モデル | SD 1.5 q4_0 (.gguf) |

---

## 3. アーキテクチャ

```
Kotlin UI (Jetpack Compose)
    │
    ├── ChatScreen（既存）
    │       ├── ChatViewModel
    │       │       ├── ToolExecutor          ← generateImage ツール追加
    │       │       └── awaitUserConfirmation() ← 承認フロー
    │       └── AlertDialog（承認UI）
    │
    ├── ImageGenScreen（新規）
    │       └── ImageGenViewModel
    │
    └── EngineManager（新規 / シングルトン）
            ├── LlmEngine（既存）
            │       └── llama.cpp / LiteRT-LM JNI
            └── SdEngine（新規）
                    └── stable-diffusion.cpp JNI
                            └── libstable-diffusion.so
```

### 3.1 エンジン排他制御

LLM と SD は同時ロードしない。切り替え時は明示的にアンロードする。

```
状態遷移:
NONE ──→ LLM_ACTIVE ──→ NONE ──→ SD_ACTIVE ──→ NONE
              └──────────────────────────┘
                    (スワップ時は経由しない、直接切替)
```

同一エンジンの連続使用はリロードしない。

---

## 4. ggml シンボル衝突対策

llama.cpp と stable-diffusion.cpp は両方 ggml を内包するため、`.so` を両方ロードするとシンボル衝突が発生しクラッシュするリスクがある。

**対策方針**: フェーズ1はプロセス分離（`EngineManager` による排他制御で同時ロードを防ぐ）、フェーズ2で共有 ggml ビルドを検討する。

---

## 5. ネイティブ層

### 5.1 ディレクトリ構成

```
app/src/main/cpp/
    ├── CMakeLists.txt          ← 改修
    ├── stable-diffusion.cpp/   ← git submodule
    └── sd_jni.cpp              ← 新規作成
```

### 5.2 CMake 設定

```cmake
add_subdirectory(stable-diffusion.cpp)

add_library(nezumi-sd SHARED sd_jni.cpp)

target_compile_options(nezumi-sd PRIVATE
    -DGGML_OPENMP=OFF   # Android では必須
    -DGGML_NEON=ON      # ARM64 パフォーマンス（明示）
)

target_link_libraries(nezumi-sd
    stable-diffusion
    android
    log
)
```

### 5.3 JNI インターフェース (`sd_jni.cpp`)

| 関数 | 引数 | 戻り値 | 説明 |
|------|------|--------|------|
| `nativeInit` | modelPath: String, threads: Int | Long (ctx ptr) | コンテキスト初期化 |
| `nativeGenerate` | ctx: Long, prompt: String, negPrompt: String, w: Int, h: Int, steps: Int, cfg: Float, seed: Long | ByteArray? | **RGBA** バイト列を返す（4ch） |
| `nativeCancel` | ctx: Long | Unit | 生成中断フラグを立てる |
| `nativeFree` | ctx: Long | Unit | コンテキスト解放 |

seed = -1 のとき乱数シードを使用する。

`nativeGenerate` は RGB(3ch) ではなく **RGBA(4ch)** で返す。JNI 側で Alpha=0xFF を埋めることで、Kotlin 側で `Bitmap.copyPixelsFromBuffer` が直接使える。

`nativeCancel` は `sd_set_progress_callback` のキャンセルフラグ経由で実装する。

### 5.4 JNI 関数シグネチャ例

```cpp
// パッケージ名に合わせて調整すること
extern "C" {

JNIEXPORT jlong JNICALL
Java_org_nezumi_ai_sd_SdEngine_nativeInit(
    JNIEnv *env, jobject thiz,
    jstring model_path, jint n_threads);

// 戻り値: RGBA 4ch ByteArray (width * height * 4 bytes)
// Android の Bitmap.Config.ARGB_8888 はメモリ上 R,G,B,A 順なので
// JNI 側でも同順で詰めること（注意: BGRA ではない）
JNIEXPORT jbyteArray JNICALL
Java_org_nezumi_ai_sd_SdEngine_nativeGenerate(
    JNIEnv *env, jobject thiz,
    jlong ctx_ptr, jstring prompt, jstring neg_prompt,
    jint width, jint height, jint steps, jfloat cfg, jlong seed);

JNIEXPORT void JNICALL
Java_org_nezumi_ai_sd_SdEngine_nativeCancel(
    JNIEnv *env, jobject thiz, jlong ctx_ptr);

JNIEXPORT void JNICALL
Java_org_nezumi_ai_sd_SdEngine_nativeFree(
    JNIEnv *env, jobject thiz, jlong ctx_ptr);

} // extern "C"
```

---

## 6. Kotlin 層

### 6.1 SdEngine

```
SdEngine(modelPath: String)
    ├── load()                          // nativeInit 呼び出し
    ├── generate(params): Bitmap?       // Dispatchers.IO で実行
    ├── cancel()                        // nativeCancel 呼び出し（生成中断）
    └── release()                       // nativeFree 呼び出し
```

`generate` は RGBA ByteArray を受け取り `Bitmap.copyPixelsFromBuffer` で高速変換する。`cancel()` は専用ページの「キャンセル」ボタンから呼び出す。

### 6.3 生成キャンセル時の状態遷移

```
生成中
    └→ cancel() 呼び出し
         └→ nativeCancel() でフラグ立て
              └→ nativeGenerate() が null を返す
                   └→ SdEngine.generate() が null を返す
                        ├→ [専用ページ] 「キャンセルしました」スナックバー表示
                        │               生成ボタンに戻る
                        └→ [チャット]   ToolResult.Text("キャンセルしました") を Gemma4 に返す
```

キャンセル後も SD コンテキストは保持したまま（再生成可能）。`release()` は呼ばない。

生成パラメータ:

| パラメータ | デフォルト | 範囲 |
|-----------|-----------|------|
| width | 512 | 256 / 512 / 768 |
| height | 512 | 256 / 512 / 768 |
| steps | 20 | 1〜50 |
| cfg | 7.0f | 1.0〜20.0 |
| seed | -1 | -1（ランダム）or 任意値 |
| negativePrompt | "" | 任意文字列 |

### 6.2 EngineManager

```kotlin
object EngineManager {
    enum class ActiveEngine { NONE, LLM, SD }

    suspend fun acquireLlm(factory: () -> LlmEngine): LlmEngine
    suspend fun acquireSd(factory: () -> SdEngine): SdEngine
    suspend fun releaseAll()
}
```

- `Mutex` による排他制御
- 既にアクティブな同エンジンはリロードしない
- 別エンジンへの切替時は旧エンジンを `release()` してから新エンジンをロード

---

## 7. ツールコール (ルートA)

### 7.1 ツール定義

```kotlin
Tool(
    name = "generateImage",
    description = "Generate an image from a text prompt using Stable Diffusion",
    parameters = mapOf(
        "prompt"          to "English image generation prompt, detailed and descriptive",
        "negative_prompt" to "Things to avoid in the image (optional)",
        "width"           to "256, 512, or 768 (default 512)",
        "height"          to "256, 512, or 768 (default 512)"
    )
)
```

### 7.2 承認フロー

```
Gemma4 が generateImage ツールコールを生成
    │
    └→ ToolExecutor が検知
         │
         └→ awaitUserConfirmation(prompt) で suspend
              │
              └→ AlertDialog 表示
                   ├─ 「はい」→ Channel.send(true)  → 生成実行
                   └─ 「いいえ」→ Channel.send(false) → ToolResult.Text("キャンセルしました") を返す
```

Gemma4 は Channel が解決するまで待機状態となり、UIをブロックしない。

### 7.3 AlertDialog 仕様

```
タイトル : 「画像を生成しますか？」
本文     : Gemma4 が生成したプロンプトを編集可能な TextField で表示
           （bodySmall / onSurfaceVariant、複数行対応）
ボタン   : 「はい」（confirmButton） / 「いいえ」（dismissButton）
```

ユーザーはダイアログ上でプロンプトを微調整してから「はい」を押せる。確定時は編集後のプロンプトを生成に使用する。

### 7.4 ChatViewModel 追加実装

```kotlin
val confirmationRequest: StateFlow<String?>   // null = 非表示、non-null = プロンプト初期値
fun onConfirmGenerate(editedPrompt: String)   // 編集後プロンプトを受け取る
fun onCancelGenerate()
```

### 7.5 Message への画像埋め込み

```kotlin
data class Message(
    val role: Role,
    val text: String,
    val imageBitmap: Bitmap? = null   // 追加
)
```

チャット画面では `imageBitmap != null` のとき `Image(bitmap)` を表示する。

---

## 8. 画像生成専用ページ (ルートB)

### 8.1 画面構成

```
ImageGenScreen
    ├── プロンプト入力欄（TextField、複数行）
    ├── ネガティブプロンプト入力欄（折りたたみ可）
    ├── Steps スライダー（1〜50、デフォルト20）
    ├── CFG スライダー（1.0〜20.0、デフォルト7.0）
    ├── サイズ選択（256x256 / 512x512 / 768x768）
    ├── 生成ボタン / キャンセルボタン（生成中は切替表示）
    ├── 生成結果画像表示エリア
    └── 保存 / 共有ボタン（生成後に表示）
```

承認ダイアログは**不要**（ユーザー自身が操作するため）。

### 8.2 画像保存仕様

- 生成画像はまずアプリ内部ストレージに自動保存する
- 「💾 保存」ボタン押下時に `MediaStore` 経由でギャラリーに書き出す
  - Android 13 (API 33) 以降は `WRITE_EXTERNAL_STORAGE` 不要（`MediaStore.Images` への書き込みは権限なしで可能）
  - API 29〜32 は `MediaStore` + `IS_PENDING` フラグを使う
- 「📤 共有」ボタンは `Intent.ACTION_SEND` で外部アプリに渡す

### 8.3 ナビゲーション

BottomNavigation に「🎨 画像生成」タブを追加する。

---

## 9. モデル管理

- モデルファイルは外部ストレージ or アプリ専用ディレクトリに配置
- パスは設定画面から指定
- 起動時にファイル存在確認を行い、未設定なら生成ボタンを disabled にする

---

## 10. 実装フェーズ

| フェーズ | 内容 | 優先度 |
|---------|------|--------|
| 1 | `sd_jni.cpp` + CMake ビルド確認（ロード→即解放でクラッシュ確認） | 高 |
| 2 | `SdEngine` Kotlin ラッパー（RGBA変換・キャンセル対応） | 高 |
| 3 | `EngineManager` 排他制御 | 高 |
| 4 | `ToolExecutor` に `generateImage` 追加 | 高 |
| 5 | 承認ダイアログ（プロンプト編集可・ChatViewModel + UI） | 高 |
| 6 | チャット画面に画像表示 | 高 |
| 7 | `ImageGenScreen` 専用ページ（キャンセルボタン含む） | 中 |
| 8 | BottomNavigation 追加 | 中 |
| 9 | 画像保存（内部→MediaStore書き出し） | 中 |
| 10 | モデルパス設定UI | 低 |
| 11 | 共有 ggml ビルド最適化 | 低 |

---

## 11. リスク

| リスク | 対策 |
|--------|------|
| ggml シンボル衝突 | EngineManager で同時ロードを防止 |
| Pixel 8a OOM（SD 1.5 q4_0 ~1.5GB） | steps/size を下げる設定をデフォルトに |
| モデルロード時間（数秒） | ローディングインジケータ表示 |
| Vulkan バックエンド不安定（Adreno） | CPU バックエンド固定でスタート |

---

## 12. 対象外

- SDXL / FLUX / Wan 等の大型モデル（Pixel 8a では非現実的）
- iOS 対応
- クラウド推論
- LoRA / ControlNet（将来検討）