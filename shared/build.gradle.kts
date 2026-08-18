// Kotlin Multiplatform 共有モジュール (nezumi-ai iOS 化の土台)。
//
// 方針: iOS 化の最初の一手として、Android / iOS で共有可能な「純粋 Kotlin ロジック」
// (プロンプト構築・パーサ系) を commonMain に移す受け皿を用意する。
//
// 参照: docs/ios/nezumi-ai-kmp-report.md, docs/ios/nezumi-ai-kmp-progress-summary.md

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
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

    sourceSets {
        commonMain.dependencies {
            // クラウド推論エンジン層の KMP 化 (フェーズ1) で使う共通依存。
            // OkHttp 直叩きから Ktor Client へ置き換えるためのコア。
            implementation(libs.ktor.client.core)
            // SSE / NDJSON ストリームやリクエスト JSON の組み立てに使用。
            // バージョンは app 側 (app/build.gradle.kts) と揃える。
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Android 側の Ktor エンジン。既存 OkHttp と同じ挙動 (タイムアウト等) を
            // 再現しやすい OkHttp エンジンを採用する。
            implementation(libs.ktor.client.okhttp)
            // PlatformSecureStore の Android 実装 (EncryptedSharedPreferences) 用。
            // バージョンは app 側 (app/build.gradle.kts) と揃える。
            implementation(libs.androidx.security.crypto)
        }
        iosMain.dependencies {
            // iOS 側の Ktor エンジン。実装は後日 (フェーズ1 ではインターフェースのみ)。
            implementation(libs.ktor.client.darwin)
        }
    }
}
