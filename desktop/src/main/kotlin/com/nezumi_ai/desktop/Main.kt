package com.nezumi_ai.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nezumi_ai.desktop.ui.App
import com.nezumi_ai.desktop.data.database.DatabaseFactory
import java.io.File

fun main() = application {
    // JNA / llama.dll は ~/.nezumi-ai/libs に配置されるため、起動時点で検索パスに含める
    val nezumiLibs = File(System.getProperty("user.home"), ".nezumi-ai/libs").absolutePath
    val jnaPath = System.getProperty("jna.library.path").orEmpty()
    val javaPath = System.getProperty("java.library.path").orEmpty()
    
    // Windows: PATHにも追加（依存DLLの解決のため）
    if (System.getProperty("os.name").lowercase().contains("win")) {
        val currentPath = System.getenv("PATH") ?: ""
        System.setProperty("jna.library.path", 
            listOf(nezumiLibs, jnaPath, javaPath).filter { it.isNotBlank() }.distinct()
                .joinToString(File.pathSeparator)
        )
        // 依存DLL解決のためPATHにも追加
        try {
            val pathField = System.getenv().javaClass.getDeclaredField("m")
            pathField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = pathField.get(System.getenv()) as MutableMap<String, String>
            map["PATH"] = "$nezumiLibs${File.pathSeparator}$currentPath"
        } catch (e: Exception) {
            println("Warning: Could not modify PATH environment variable")
        }
    } else {
        System.setProperty(
            "jna.library.path",
            listOf(nezumiLibs, jnaPath, javaPath).filter { it.isNotBlank() }.distinct()
                .joinToString(File.pathSeparator),
        )
    }

    // データベース初期化
    DatabaseFactory.init()
    
    println("")
    println("=== nezumi-ai Desktop ===")
    println("Library search paths:")
    println("  - $nezumiLibs")
    println("  - ${File("desktop/libs").absolutePath}")
    println("")
    println("If you see 'Invalid memory access' error:")
    println("  1. Open Settings tab")
    println("  2. Click 'Download llama.cpp' button")
    println("  3. Or see: desktop/TROUBLESHOOTING.md")
    println("=========================")
    println("")
    
    // MCP Server起動 (バックグラウンド) - 一旦無効化
    // val mcpServer = McpServer()
    // mcpServer.start()
    
    val windowState = rememberWindowState(
        width = 1200.dp,
        height = 800.dp
    )
    
    Window(
        onCloseRequest = {
            // runBlocking {
            //     mcpServer.stop()
            // }
            exitApplication()
        },
        title = "ネズミAI",
        state = windowState
    ) {
        App()
    }
}
