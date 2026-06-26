import java.util.Properties
import java.io.FileInputStream
import java.io.File

plugins {
    id("com.android.application") version "8.9.1"
    kotlin("android") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("com.google.devtools.ksp") version "2.2.0-2.0.2"
    id("androidx.navigation.safeargs.kotlin") version "2.6.0"
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
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.nezumi_ai"
        minSdk = 30
        targetSdk = 36
        versionCode = 16
        versionName = "2.1.1"

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

        buildConfigField("String", "LITERTLM_VERSION", "\"0.12.0\"")
        buildConfigField("String", "LLAMACPP_VERSION", "\"llama.rn 0.12.5\"")

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments.add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
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

            // VOICEVOX 無効化期間中は 4KB アライン SO を除外する。
            // VoicevoxFeatureFlag.ENABLED = true に戻す際はこの excludes を削除すること。
            excludes += setOf(
                "lib/arm64-v8a/libvoicevox_onnxruntime.so",
                "lib/x86_64/libvoicevox_onnxruntime.so"
            )
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

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
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

    implementation("androidx.navigation:navigation-fragment-ktx:2.6.0")
    implementation("androidx.navigation:navigation-ui-ktx:2.6.0")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("net.openid:appauth:0.11.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}