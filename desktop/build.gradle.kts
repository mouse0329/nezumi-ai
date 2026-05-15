import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

group = "com.nezumi_ai"
version = "1.0.0"

dependencies {
    // Shared module
    implementation(project(":shared"))
    
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Database (Exposed - Room風API)
    implementation("org.jetbrains.exposed:exposed-core:0.48.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.48.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.48.0")
    implementation("org.xerial:sqlite-jdbc:3.45.0.0")
    
    // HTTP Server (MCP連携用)
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-io:2.3.7")
    
    // HTTP Client (モデルダウンロード用)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")

    // Ktor / Exposed が参照する SLF4J の実装（コンソール警告解消）
    implementation("org.slf4j:slf4j-simple:2.0.16")
    
    // Markdown rendering
    implementation("com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha02")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:1.0.0-alpha02")
    
    // Image loading (Coil 3.x for Desktop)
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
    
    // JNA (llama.cpp連携用)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

compose.desktop {
    application {
        mainClass = "com.nezumi_ai.desktop.MainKt"
        
        jvmArgs += listOf(
            "-Djava.library.path=${project.projectDir}/libs"
        )
        
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "nezumi-ai-desktop"
            packageVersion = "1.0.0"
            
            description = "nezumi-ai Desktop - Local AI Chat with MCP Support"
            vendor = "nezumi-ai"
            
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}
