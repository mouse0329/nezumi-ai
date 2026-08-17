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
            // 移設してきた 4 ファイルは純粋 Kotlin のみで依存ゼロ。
            // 今後の KMP 化で必要になる依存はここに追加していく。
        }
    }
}
