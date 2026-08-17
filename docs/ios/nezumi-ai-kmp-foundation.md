# nezumi-ai KMP土台構築メモ（sharedモジュール新設）

最終更新: 2026-08-17

iOS 化の最初の一手として、AGP 9 + Kotlin 2.3.20 の新構成に対応した Kotlin
Multiplatform 共有モジュール（`:shared`）を新設し、純粋 Kotlin ロジックを
commonMain に移設した。本メモは実装内容・確認手順・残課題を実装エージェントに
引き継ぐための記録。

---

## 1. 目的と背景

nezumi-ai は現状「AGP 単一 `app` モジュール構成」で KMP 未導入だった。
AGP 9.0 以降、従来の `org.jetbrains.kotlin.multiplatform` + `com.android.library`
の併用は非互換となり、KMP ライブラリには新プラグイン
`com.android.kotlin.multiplatform.library` を使う必要がある。

この制約を踏まえ、「app モジュール自体を KMP 化する」のではなく、
独立した `:shared` モジュールを新設して app から依存させる構成を採用した。
これは [公式の AGP 9 移行ガイド](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)
および [Android 公式の Android-KMP プラグインガイド](https://developer.android.com/kotlin/multiplatform/plugin)
の推奨構造に沿う。

## 2. 変更内容

### 2.1 Gradle 設定

| ファイル | 変更 |
|---|---|
| `settings.gradle.kts` | `include(":shared")` を追加 |
| `build.gradle.kts` | ルートに `kotlin.multiplatform` / `com.android.kotlin.multiplatform.library` の `apply false` 宣言を追加 |
| `gradle/libs.versions.toml` | `agp = "9.1.0"` に更新（app と同じ実効バージョンに揃えた）+ KMP 系プラグインのエイリアスを追加 |
| `shared/build.gradle.kts` | 新設。`androidLibrary` + `iosArm64` / `iosSimulatorArm64` を宣言 |
| `app/build.gradle.kts` | `implementation(project(":shared"))` を追加 |

### 2.2 共有モジュール構成

```
shared/
├── build.gradle.kts
└── src/
    └── commonMain/kotlin/com/nezumi_ai/data/inference/
        ├── Gemma4ThinkingParser.kt   (BuildConfig / android.util.Log 依存を除去して純粋化)
        ├── InferenceStreamProtocol.kt
        ├── StringExtensions.kt
        └── ThinkingLeakSalvage.kt
```

`shared/build.gradle.kts` の要点:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    androidLibrary {
        namespace = "com.nezumi_ai.shared"
        compileSdk = 37
        minSdk = 30
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "shared" }
    }
}
```

## 3. 移設したファイルと正規化

| ファイル | 変更の有無 | 備考 |
|---|---|---|
| `InferenceStreamProtocol` | 変更なし | import ゼロ・純粋 Kotlin |
| `ThinkingLeakSalvage` | 変更なし | import ゼロ・純粋 Kotlin |
| `StringExtensions` | 変更なし | import ゼロ。ただし `stripGemmaTokens` が `Gemma4ThinkingParser` を参照するためセット移設 |
| `Gemma4ThinkingParser` | **要正規化** | `com.nezumi_ai.BuildConfig` と `android.util.Log`（デバッグログ）を除去。ロジックは不変 |

`Gemma4ThinkingParser.sanitizeVisibleText()` の末尾にあった
`if (BuildConfig.DEBUG && ...) logDebug(...)` は、共有化のため削除した
（純粋な表示テキスト正規化ロジック自体は変化なし）。デバッグログが必要になったら、
`expect/actual` のロガーを別途導入する。

## 4. 移設を見送ったファイル

- `TextTokenEstimator`: `java.lang.Character.UnicodeBlock`（JVM 限定の Unicode 判定）
  を使用するため、そのままでは iOS (Kotlin/Native) でコンパイルできない。
  Unicode ブロック判定を expect/actual 化するか、自前のコードポイント範囲判定に
  置き換えてから移設する。

## 5. ビルド確認手順

### Android 側（app の回帰確認）

```bash
./gradlew assembleDebug
```

### shared モジュール単体のコンパイル

```bash
# Android target
./gradlew :shared:compileKotlinAndroid

# iOS 実機 (Apple Silicon)
./gradlew :shared:compileKotlinIosArm64

# iOS Simulator (Apple Silicon)
./gradlew :shared:compileKotlinIosSimulatorArm64
```

iOS ターゲットのコンパイルには Kotlin/Native のツールチェインと
（framework 生成時は）Xcode が必要。`compileKotlinIos*` は Kotlin ソースの
コンパイルのみなので macOS 環境で検証する。

## 6. 残課題・次へ

1. shared モジュールの実ビルド確認（macOS / Windows 環境で実施依頼）
2. `TextTokenEstimator` の expect/actual 化による残り純粋ロジックの移設
3. クラウド推論エンジン層の KMP 化（OkHttp → Ktor 移行）
4. 各推論エンジン（GGUF / MNN / LiteRT-LM）の iOS ブリッジ実装
