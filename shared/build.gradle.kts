// Kotlin Multiplatform 共有モジュール (nezumi-ai iOS 化の土台)。
//
// 方針: Android / iOS で共有可能なドメインモデル、UseCase、Repository、
// パーサ、プロトコルを commonMain に集約し、OS依存処理を platform source set に分ける。
//
// 参照: docs/ios/nezumi-ai-kmp-report.md, docs/ios/nezumi-ai-kmp-progress-summary.md

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    androidLibrary {
        namespace = "com.nezumi_ai.shared"
        compileSdk = 37
        minSdk = 30

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    // iOS ターゲット。実機 Apple Silicon と Simulator (Apple Silicon / Intel) をカバーする
    // 最小構成。最終的な Xcode 組み込み用フレームワークは各ターゲットの binaries.framework
    // で生成する。
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
        }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform UI。Androidのandroidx.composeではなく、
            // Android/iOS共通のComposeランタイムを利用する。
            implementation("org.jetbrains.compose.runtime:runtime:1.7.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.7.1")
            implementation("org.jetbrains.compose.material3:material3:1.7.1")
            implementation("org.jetbrains.compose.ui:ui:1.7.1")
            // Room (KMP)。エンティティ・DAO を共有する。DB 本体のビルダーはプラットフォーム別に実装する。
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            // クラウド推論エンジン層の KMP 化 (フェーズ1) で使う共通依存。
            // OkHttp 直叩きから Ktor Client へ置き換えるためのコア。
            implementation(libs.ktor.client.core)
            // SSE / NDJSON ストリームやリクエスト JSON の組み立てに使用。
            // バージョンは app 側 (app/build.gradle.kts) と揃える。
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Android の HTTP 実装に使う Ktor CIO エンジン。
            implementation(libs.ktor.client.cio)
            // PlatformSecureStore の Android 実装 (EncryptedSharedPreferences) 用。
            // バージョンは app 側 (app/build.gradle.kts) と揃える。
            implementation(libs.androidx.security.crypto)
        }
        iosMain.dependencies {
            // iOS 側の Ktor エンジン。実装は後日 (フェーズ1 ではインターフェースのみ)。
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
    }
}

// KMP の各ターゲットの configuration は kotlin {} でターゲットを宣言した後に存在するため、必ず後ろに置く
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}
