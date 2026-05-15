package com.nezumi_ai.desktop.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * llama.cpp ビルド済みバイナリの自動ダウンローダー。
 * リリースは [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)（旧 ggerganov から移転）。
 */
object LlamaDownloader {

    private const val REPO = "ggml-org/llama.cpp"
    /** API 失敗時やアセット欠落時のフォールバック（定期的に更新推奨） */
    private const val FALLBACK_TAG = "b9134"

    private val OS = System.getProperty("os.name").lowercase()
    private val ARCH = System.getProperty("os.arch").lowercase()

    private fun getLibraryDir(): File {
        val userHome = System.getProperty("user.home")
        val libDir = File(userHome, ".nezumi-ai/libs")
        if (!libDir.exists()) {
            libDir.mkdirs()
        }
        return libDir
    }

    private fun getLibraryName(): String {
        return when {
            OS.contains("win") -> "llama.dll"
            OS.contains("mac") -> "libllama.dylib"
            else -> "libllama.so"
        }
    }

    fun isLibraryAvailable(): Boolean {
        val libDir = getLibraryDir()
        val libFile = File(libDir, getLibraryName())
        return libFile.exists()
    }

    fun getLibraryPath(): String {
        return File(getLibraryDir(), getLibraryName()).absolutePath
    }

    private fun fetchLatestReleaseTag(): String {
        val url = URL("https://api.github.com/repos/$REPO/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "nezumi-ai-desktop")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                println("GitHub API releases/latest returned HTTP $code, using fallback tag $FALLBACK_TAG")
                return FALLBACK_TAG
            }
            val key = "\"tag_name\":"
            val i = body.indexOf(key)
            if (i < 0) return FALLBACK_TAG
            val start = body.indexOf('"', i + key.length) + 1
            val end = body.indexOf('"', start)
            if (start <= 0 || end <= start) return FALLBACK_TAG
            val tag = body.substring(start, end).trim()
            if (tag.isEmpty() || !tag.matches(Regex("b\\d+"))) FALLBACK_TAG else tag
        } catch (e: Exception) {
            println("Could not fetch latest llama.cpp tag: ${e.message}, using $FALLBACK_TAG")
            FALLBACK_TAG
        } finally {
            conn.disconnect()
        }
    }

    private fun osKey(): String = when {
        OS.contains("win") -> "windows"
        OS.contains("mac") -> "macos"
        else -> "linux"
    }

    /**
     * 現在 OS 向けのリリースアセット名（[releases](https://github.com/ggml-org/llama.cpp/releases) に準拠）
     */
    private fun assetFileName(tag: String): String = when (osKey()) {
        "windows" -> {
            val winArm = ARCH.contains("aarch64") || ARCH.contains("arm64")
            if (winArm) "llama-$tag-bin-win-cpu-arm64.zip"
            else "llama-$tag-bin-win-cpu-x64.zip"
        }
        "linux" -> "llama-$tag-bin-ubuntu-x64.tar.gz"
        "macos" -> {
            val appleSilicon = ARCH.contains("aarch64") || ARCH.contains("arm64")
            if (appleSilicon) "llama-$tag-bin-macos-arm64.tar.gz"
            else "llama-$tag-bin-macos-x64.tar.gz"
        }
        else -> error("Unsupported OS: $OS")
    }

    private fun releaseDownloadUrl(tag: String): String {
        val asset = assetFileName(tag)
        return "https://github.com/$REPO/releases/download/$tag/$asset"
    }

    private fun openAssetStream(downloadUrl: String): InputStream {
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "nezumi-ai-desktop")
        conn.connectTimeout = 30_000
        conn.readTimeout = 600_000
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw java.io.FileNotFoundException("HTTP $code for $downloadUrl")
        }
        return conn.inputStream
    }

    private fun isNativeLibraryFileName(name: String): Boolean {
        val n = File(name).name.lowercase()
        return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib")
    }

    private fun extractZip(zipFile: File, libDir: File) {
        var extractedCount = 0
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val fileName = entry.name
                if (!entry.isDirectory && isNativeLibraryFileName(fileName)) {
                    val outFile = File(libDir, File(fileName).name)
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                    if (!OS.contains("win")) {
                        outFile.setExecutable(true, false)
                    }
                    val sizeMB = outFile.length() / 1024.0 / 1024.0
                    println("Extracted: ${outFile.name} (${String.format("%.2f", sizeMB)}MB)")
                    extractedCount++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        println("Total files extracted: $extractedCount")
    }

    private fun extractTarGz(tarGz: File, libDir: File) {
        val tempExtract = File(tarGz.parentFile, "llama-extract-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pb = ProcessBuilder(
                "tar",
                "-xzf",
                tarGz.absolutePath,
                "-C",
                tempExtract.absolutePath,
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line -> println(line) }
            }
            val code = proc.waitFor()
            if (code != 0) {
                throw RuntimeException("tar exited with code $code")
            }
            tempExtract.walkTopDown()
                .filter { it.isFile && isNativeLibraryFileName(it.name) }
                .forEach { f ->
                    val outFile = File(libDir, f.name)
                    f.copyTo(outFile, overwrite = true)
                    if (!OS.contains("win")) {
                        outFile.setExecutable(true, false)
                    }
                    println("Extracted: ${outFile.absolutePath}")
                }
        } finally {
            tempExtract.deleteRecursively()
        }
    }

    suspend fun downloadLibrary(onProgress: (Int) -> Unit = {}): Result<String> = withContext(Dispatchers.IO) {
        try {
            val osKey = osKey()
            if (osKey !in setOf("windows", "linux", "macos")) {
                return@withContext Result.failure(Exception("Unsupported OS: $OS"))
            }

            var tag = fetchLatestReleaseTag()
            var downloadUrl = releaseDownloadUrl(tag)
            println("Using llama.cpp release tag: $tag")
            println("Downloading from: $downloadUrl")
            onProgress(5)

            val libDir = getLibraryDir()
            val ext = when {
                downloadUrl.endsWith(".tar.gz") -> ".tar.gz"
                else -> ".zip"
            }
            val tempArchive = File.createTempFile("llama-cpp", ext, libDir)

            fun downloadToFile(url: String) {
                openAssetStream(url).use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            if (totalBytes % (1024 * 1024) == 0L) {
                                println("Downloaded: ${totalBytes / 1024 / 1024}MB")
                            }
                        }
                        println("Total downloaded: ${totalBytes / 1024 / 1024}MB")
                        println("Archive saved to: ${tempArchive.absolutePath}")
                        println("Archive size: ${tempArchive.length() / 1024 / 1024}MB")
                    }
                }
            }

            try {
                downloadToFile(downloadUrl)
            } catch (e: java.io.FileNotFoundException) {
                if (tag != FALLBACK_TAG) {
                    println("Primary URL failed (${e.message}), retrying with fallback tag $FALLBACK_TAG")
                    tag = FALLBACK_TAG
                    downloadUrl = releaseDownloadUrl(tag)
                    println("Downloading from: $downloadUrl")
                    downloadToFile(downloadUrl)
                } else {
                    throw e
                }
            }

            onProgress(50)
            println("Download complete. Extracting...")

            when {
                tempArchive.name.endsWith(".tar.gz") -> extractTarGz(tempArchive, libDir)
                else -> extractZip(tempArchive, libDir)
            }

            onProgress(90)
            tempArchive.delete()

            val mainLib = File(libDir, getLibraryName())
            if (!mainLib.exists()) {
                return@withContext Result.failure(
                    Exception("Extraction finished but ${getLibraryName()} was not found in ${libDir.absolutePath}"),
                )
            }
            
            // Validate file size (llama.dll should be around 2-3MB, but we need all dependencies)
            val fileSizeMB = mainLib.length() / 1024 / 1024
            println("Main library (${mainLib.name}): ${fileSizeMB}MB")
            
            // Check for required dependencies on Windows
            if (OS.contains("win")) {
                val requiredDeps = listOf("llama-common.dll", "ggml-base.dll")
                val missingDeps = requiredDeps.filter { !File(libDir, it).exists() }
                if (missingDeps.isNotEmpty()) {
                    return@withContext Result.failure(
                        Exception("Missing required dependencies: ${missingDeps.joinToString(", ")}"),
                    )
                }
                println("All required dependencies found")
            }

            onProgress(100)
            println("llama.cpp library installed successfully")
            Result.success(getLibraryPath())
        } catch (e: Exception) {
            println("Failed to download llama.cpp: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun buildFromSource(onProgress: (String) -> Unit = {}): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress("Cloning llama.cpp repository...")

            val tempDir = File.createTempFile("llama", "build").apply {
                delete()
                mkdirs()
            }

            val cloneProcess = ProcessBuilder(
                "git",
                "clone",
                "--depth",
                "1",
                "https://github.com/$REPO",
                tempDir.absolutePath,
            ).redirectErrorStream(true).start()

            cloneProcess.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    println(line)
                    onProgress(line)
                }
            }

            if (cloneProcess.waitFor() != 0) {
                return@withContext Result.failure(Exception("Git clone failed"))
            }

            onProgress("Building llama.cpp...")

            val buildDir = File(tempDir, "build").apply { mkdirs() }

            val cmakeProcess = ProcessBuilder(
                "cmake",
                "..",
                "-DBUILD_SHARED_LIBS=ON",
                "-DCMAKE_BUILD_TYPE=Release",
            ).directory(buildDir).redirectErrorStream(true).start()

            cmakeProcess.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    println(line)
                    onProgress(line)
                }
            }

            if (cmakeProcess.waitFor() != 0) {
                return@withContext Result.failure(Exception("CMake configure failed"))
            }

            val buildProcess = ProcessBuilder(
                "cmake",
                "--build",
                ".",
                "--config",
                "Release",
            ).directory(buildDir).redirectErrorStream(true).start()

            buildProcess.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    println(line)
                    onProgress(line)
                }
            }

            if (buildProcess.waitFor() != 0) {
                return@withContext Result.failure(Exception("Build failed"))
            }

            onProgress("Copying library...")

            val libDir = getLibraryDir()
            val builtLib = when {
                OS.contains("win") -> File(buildDir, "Release/llama.dll")
                else -> File(buildDir, getLibraryName())
            }

            if (!builtLib.exists()) {
                return@withContext Result.failure(Exception("Built library not found: ${builtLib.absolutePath}"))
            }

            val targetLib = File(libDir, getLibraryName())
            builtLib.copyTo(targetLib, overwrite = true)

            tempDir.deleteRecursively()

            onProgress("Build complete!")
            Result.success(targetLib.absolutePath)
        } catch (e: Exception) {
            println("Failed to build llama.cpp: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
