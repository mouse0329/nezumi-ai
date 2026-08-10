import java.util.Properties
import java.io.FileInputStream
import java.io.File

plugins {
    id("com.android.application") version "9.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.9"
    id("androidx.navigation.safeargs.kotlin") version "2.9.8"
    // Chaquopy 17.0.0: AGP 9.0-9.2 に対応した最初のバージョン (#1096)。
    // 16.1.0 は AGP 8.13 までしか対応しておらず、AGP 9.1 との組み合わせで
    // 「Unable to load class 'org.gradle.util.VersionNumber'」エラー
    // (Gradle 9.0 で当該クラスが削除されたことによる非互換) が発生するため必須の更新。
    id("com.chaquo.python") version "17.0.0"
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

fun envOrLocal(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = envOrLocal("STORE_FILE")
val releaseStorePassword = envOrLocal("STORE_PASSWORD")
val releaseKeyAlias = envOrLocal("KEY_ALIAS")
val releaseKeyPassword = envOrLocal("KEY_PASSWORD")

val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    File(releaseStoreFilePath).exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.nezumi_ai"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.nezumi_ai"
        minSdk = 30
        targetSdk = 37
        versionCode = 17
        versionName = "2.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["appAuthRedirectScheme"] = "nezumiai"

        // -----------------------------------------------------------------------
        // Safety Layer フラグ
        // 全層のセーフティガードを無効化する場合は下記の値を false に変更する。
        // Google Play 審査やデバッグ目的以外は必ず true のままにすること。
        //
        // SAFETY_PROMPT_FILTER_ENABLED  : 前段テキストガード (PromptFilter)
        // SAFETY_IMAGE_GUARD_ENABLED    : 後段画像ガード (ImageSafetyChecker / ONNX)
        // -----------------------------------------------------------------------


        val safetyPromptEnabled = true
        val safetyImageEnabled  = true


        buildConfigField(
            "boolean",
            "SAFETY_PROMPT_FILTER_ENABLED",
            "$safetyPromptEnabled"
        )

        buildConfigField(
            "boolean",
            "SAFETY_IMAGE_GUARD_ENABLED",
            "$safetyImageEnabled"
        )

        // appNameはビルド状態に依存させず固定（UIと安全機構を分離）
        if (safetyPromptEnabled && safetyImageEnabled) {
            manifestPlaceholders["appName"] = "ネズミAI"
        } else {
            manifestPlaceholders["appName"] = "ネズミAI Open"
        }

        // openビルド判定はビルド識別として扱う（安全フラグとは分離）
        if (!safetyPromptEnabled || !safetyImageEnabled) {
            applicationIdSuffix = ".open"
        }

        buildConfigField("String", "LITERTLM_VERSION", "\"0.15.0\"")
        buildConfigField("String", "LLAMACPP_VERSION", "\"llama.rn 0.12.8\"")
        buildConfigField("boolean", "CONTEXT_COMPRESSION_ENABLED", "false")

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments.add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }

        // ─────────────────────────────────────────────
        // Chaquopy (Python-on-Android) 用の ABI 設定は、
        // 上の ndk { abiFilters.add("arm64-v8a") } がそのまま適用される
        // （Chaquopy は独自の abiFilters を持たず、AGP の ndk.abiFilters を参照する）。
        // Python 本体の設定 (buildPython / pip) はトップレベルの
        // chaquopy { defaultConfig { ... } } ブロックで行う
        // （Kotlin DSL では android.defaultConfig.python{} という旧 Groovy 専用 DSL は
        // 使用できないため、必ずファイル末尾の独立した chaquopy{} ブロックを使うこと）。
        // ─────────────────────────────────────────────
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                println("Release signing config not found. Building unsigned release APK.")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            ndk {
                debugSymbolLevel = "NONE"
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

// ─────────────────────────────────────────────
// Chaquopy (Python-on-Android) 設定
// PDF/Word/Excel → Markdown 変換 (MarkItDown) を実行するために使用。
//
// 重要: Kotlin DSL (build.gradle.kts) では、android.defaultConfig.python{} という
// 旧 Groovy 専用 DSL は使えない。必ずこのファイル末尾に独立して置く
// トップレベルの chaquopy { defaultConfig { ... } } ブロック（新DSL）を使うこと。
// ABI の絞り込みは android.defaultConfig.ndk.abiFilters 側（上の android ブロック）で
// 行われており、Chaquopy はそれをそのまま参照するため、ここでの指定は不要。
// ─────────────────────────────────────────────
chaquopy {
    defaultConfig {
        // ランタイム Python バージョンを明示的に 3.13 に指定する。
        //
        // 理由:
        //   1. buildPython の自動検出は「アプリのランタイム Python と同じ
        //      メジャー.マイナーバージョン」のインタプリタしかビルドマシン上で
        //      受け付けない。デフォルトのランタイムは 3.10 だが、多くの開発機
        //      （特に最近セットアップした Windows 環境）には 3.10 系が無く、
        //      py ランチャーには最新の 3.13 系のみが入っていることが多い。
        //      version="3.13" にすることで、この不一致を解消する。
        //   2. Android 15 の 16KB ページサイズ要件に対応する上でも、
        //      Chaquopy 17.0.0 + Python 3.13 以降の組み合わせが推奨されている
        //      （3.12 以前のビルド済み .so は 16KB ページ非対応のものが多いため）。
        //
        // ビルドマシン側に Python 3.13 系が無い場合は、この値を実際にインストール
        // 済みのバージョン（例: "3.11", "3.12"）に合わせて変更するか、
        // buildPython() で絶対パスを指定すること。
        version = "3.13"

        pip {
            // MarkItDown 本体（PDF/Word/Excel/PowerPoint/HTML等 → Markdown 変換）。
            // markitdown[pdf,docx,xlsx,pptx] は依存として pdfminer.six / python-docx /
            // openpyxl / python-pptx 等を自動的に引き込む。初回ビルドは pip 解決に
            // 数分かかることがある。
            // Chaquopy 17.0 は pip --only-binary を使うため、Android 向け wheel が
            // 存在しない純粋 Python 以外のパッケージは失敗し得る点に注意。
            install("markitdown[pdf,docx,xlsx,pptx]")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    implementation("androidx.activity:activity-compose:1.13.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text-android")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation(libs.compose.richtext.commonmark)
    implementation(libs.compose.richtext.ui.material3)
    implementation("ru.noties:jlatexmath-android:0.2.0")

    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.6.0")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.15.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("net.openid:appauth:0.11.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // クラウド推論エンジン (Claude / Gemini / OpenAI / LM Studio) の
    // SSE ストリームを行単位で解析するために okhttp-sse を追加。
    // Ollama は NDJSON ストリームなので通常の Response.body().source() で処理する。
    implementation("com.squareup.okhttp3:okhttp-sse:5.4.0")

    // クラウドプロバイダ用 API キー / Base URL を暗号化して保存するために使用。
    // Android Keystore の AES-256 マスターキーで EncryptedSharedPreferences を
    // 生成する。HfAuthManager が使う平文 SharedPreferences とは別ファイルに分離する。
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ─────────────────────────────────────────────
    // ページ取得ツール (URL → HTML 取得 + Markdown 変換)
    // jsoup: HTML の取得・解析 / flexmark-html2md-converter: HTML → Markdown 変換
    // web_search (Brave Search API) で見つけたページ本文を読むために使用。
    // 注意: flexmark-java 0.64.8 の HTML → Markdown 変換の実体は
    // flexmark-html2md-converter (com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter)。
    // "flexmark-html2md" というアーティファクトには jar が存在しないため、
    // 変換器モジュールを直接指定する。
    // ─────────────────────────────────────────────
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.vladsch.flexmark:flexmark-html2md-converter:0.64.8")

    // VOICEVOX integration
    implementation(files("libs/voicevoxcore-android-0.16.4.aar"))

    // ─────────────────────────────────────────────
    // Markdown → Word(.docx) / Excel(.xlsx) 生成用 (Apache POI)
    // Android では素の POI に加え poi-ooxml が必要。XML処理は Android 標準実装と
    // 衝突しやすいため、Xerces/Xalan 系の重複クラスを excludeし、
    // Android の内蔵 XML パーサ実装との競合を避ける。
    //
    // 注意: curvesapi は明示バージョン指定しない（POI 5.4.0 の POM が要求する
    // 推移的バージョンにそのまま従わせる）。Maven Central 上の curvesapi は
    // "1.06", "1.07", "1.08" のようにゼロ埋め表記のみが公開されており、
    // "1.8" のような表記のバージョンは存在しないため、明示指定すると
    // 解決エラーになる。
    // ─────────────────────────────────────────────
    implementation("org.apache.poi:poi:5.4.0")
    implementation("org.apache.poi:poi-ooxml:5.4.0") {
        exclude(group = "org.apache.xmlbeans", module = "xmlbeans")
    }
    implementation("org.apache.xmlbeans:xmlbeans:5.2.0")
    // POI の一部クラスが依存する javax.xml.stream (StAX) 実装。Android には
    // 標準搭載されていないため明示的に追加する。
    implementation("org.codehaus.woodstox:stax2-api:4.2.2")
    implementation("com.fasterxml.woodstox:woodstox-core:6.7.0")

    // ─────────────────────────────────────────────
    // Markdown → PDF 生成用 (Android移植版 PDFBox)
    // ─────────────────────────────────────────────
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}