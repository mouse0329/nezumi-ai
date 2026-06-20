package com.nezumi_ai.sd

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.nezumi_ai.sd.safety.ImageSafetyChecker
import com.nezumi_ai.sd.safety.PromptFilter
import com.nezumi_ai.sd.safety.SafetyResult
import com.nezumi_ai.sd.safety.toBlurred
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ProgressData(
    val step: Int,
    val totalSteps: Int,
    val time: Float,
    val progress: Float = step.toFloat() / totalSteps.coerceAtLeast(1)
)

class LocalDreamModule(private val context: Context) {

    // lazy ではなく毎回生成時に取得 — ファイル差し替え後も確実に反映される
    private var _safetyChecker: ImageSafetyChecker? = null
    private var _lastSafetyVerdict: SafetyResult.Verdict? = null
    
    fun getLastSafetyVerdict(): SafetyResult.Verdict? = _lastSafetyVerdict
    fun clearLastSafetyVerdict() { _lastSafetyVerdict = null }
    
    private fun safetyChecker(): ImageSafetyChecker {
        val existing = _safetyChecker
        // モデルファイルが新たに存在するのに session が null なら再生成
        if (existing != null && (existing.isAvailable || !com.nezumi_ai.data.inference.ModelDownloadWorker.isSafetyModelReady(context))) {
            return existing
        }
        existing?.close()
        return ImageSafetyChecker(context).also { _safetyChecker = it }
    }
    
    companion object {
        private const val TAG = "LocalDreamModule"
        private const val SERVER_PORT = 18081
        private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
        private const val RUNTIME_DIR = "runtime_libs"
    }
    
    private var serverProcess: Process? = null
    private var currentModelPath: String? = null
    private var currentBackend: String? = null
    private var isServerReady = false
    private var monitorJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())
    private val activeGenerationConn = AtomicReference<HttpURLConnection?>(null)

    private fun normalizeServerProgress(
        serverStep: Int,
        serverTotalSteps: Int,
        requestedSteps: Int
    ): Pair<Int, Int> {
        val total = requestedSteps.coerceAtLeast(1)
        if (serverTotalSteps <= total) {
            return serverStep.coerceIn(0, total) to total
        }

        val extraSteps = serverTotalSteps - total
        val normalizedStep = when {
            // Some LocalDream server builds include setup/finalization events in total_steps.
            extraSteps == 2 -> serverStep.coerceIn(0, total)
            else -> ((serverStep.toFloat() / serverTotalSteps.toFloat()) * total)
                .toInt()
                .coerceIn(0, total)
        }
        return normalizedStep to total
    }
    
    private fun isNpuSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.startsWith("SM")
        } else {
            false
        }
    }
    
    private fun prepareRuntimeDir(): File {
        val runtimeDir = File(context.filesDir, RUNTIME_DIR).apply {
            if (!exists()) mkdirs()
        }
        Log.d(TAG, "prepareRuntimeDir: runtimeDir=${runtimeDir.absolutePath}")

        try {
            val qnnLibs = context.assets.list("qnnlibs")
            if (qnnLibs == null || qnnLibs.isEmpty()) {
                Log.w(TAG, "prepareRuntimeDir: assets/qnnlibs is empty or missing")
            } else {
                qnnLibs.forEach { fileName ->
                    val targetLib = File(runtimeDir, fileName)

                    val needsCopy = !targetLib.exists() || run {
                        val assetInputStream = context.assets.open("qnnlibs/$fileName")
                        val assetSize = assetInputStream.use { it.available().toLong() }
                        targetLib.length() != assetSize
                    }

                    if (needsCopy) {
                        context.assets.open("qnnlibs/$fileName").use { input ->
                            targetLib.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "Copied $fileName to runtime directory")
                    }

                    targetLib.setReadable(true, true)
                    targetLib.setExecutable(true, true)
                    Log.d(TAG, "prepareRuntimeDir: targetLib=${targetLib.absolutePath} exists=${targetLib.exists()} canExecute=${targetLib.canExecute()} length=${targetLib.length()}")
                }
                Log.i(TAG, "QNN libraries prepared in: ${runtimeDir.absolutePath}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "No QNN libraries found in assets: ${e.message}")
        }

        runtimeDir.setReadable(true, true)
        runtimeDir.setExecutable(true, true)
        return runtimeDir
    }

    private fun resolveExecutable(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeDirFile = File(nativeDir, EXECUTABLE_NAME)

        if (!nativeDirFile.exists()) {
            Log.w(TAG, "resolveExecutable: executable not found in nativeLibraryDir=${nativeDirFile.absolutePath}")
            return null
        }

        if (!nativeDirFile.canExecute()) {
            nativeDirFile.setExecutable(true, true)
        }
        Log.d(TAG, "resolveExecutable: native executable=${nativeDirFile.absolutePath} canExecute=${nativeDirFile.canExecute()} length=${nativeDirFile.length()}")
        return nativeDirFile
    }
    
    private fun resolveModelDir(dir: File, isCpu: Boolean): File? {
        val markerFile = if (isCpu) "unet.mnn" else "unet.bin"
        
        if (File(dir, markerFile).exists()) return dir
        
        fun searchDir(current: File, depth: Int): File? {
            if (depth > 3) return null
            current.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                if (File(subDir, markerFile).exists()) {
                    Log.d(TAG, "Found $markerFile in: ${subDir.absolutePath}")
                    return subDir
                }
                val deeper = searchDir(subDir, depth + 1)
                if (deeper != null) return deeper
            }
            return null
        }
        
        return searchDir(dir, 0)
    }
    
    private fun buildCommand(
        executable: File,
        modelDir: File,
        runtimeDir: File,
        isCpu: Boolean
    ): List<String> {
        return if (isCpu) {
            mutableListOf(
                executable.absolutePath,
                "--clip", File(modelDir, "clip.mnn").absolutePath,
                "--unet", File(modelDir, "unet.mnn").absolutePath,
                "--vae_decoder", File(modelDir, "vae_decoder.mnn").absolutePath,
                "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                "--port", SERVER_PORT.toString(),
                "--text_embedding_size", "768",
                "--cpu"
            ).also { cmd ->
                val vaeEncoder = File(modelDir, "vae_encoder.mnn")
                if (vaeEncoder.exists()) {
                    cmd.addAll(listOf("--vae_encoder", vaeEncoder.absolutePath))
                }
            }
        } else {
            val clipFile = when {
                File(modelDir, "clip.mnn").exists() -> "clip.mnn"
                File(modelDir, "clip_v2.mnn").exists() -> "clip_v2.mnn"
                else -> "clip.bin"
            }
            val hasMnnClip = clipFile.endsWith(".mnn")
            
            mutableListOf(
                executable.absolutePath,
                "--clip", File(modelDir, clipFile).absolutePath,
                "--unet", File(modelDir, "unet.bin").absolutePath,
                "--vae_decoder", File(modelDir, "vae_decoder.bin").absolutePath,
                "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                "--backend", File(runtimeDir, "libQnnHtp.so").absolutePath,
                "--system_library", File(runtimeDir, "libQnnSystem.so").absolutePath,
                "--port", SERVER_PORT.toString(),
                "--text_embedding_size", "768"
            ).also { cmd ->
                if (hasMnnClip) {
                    cmd.add("--use_cpu_clip")
                }
                val vaeEncoder = File(modelDir, "vae_encoder.bin")
                if (vaeEncoder.exists()) {
                    cmd.addAll(listOf("--vae_encoder", vaeEncoder.absolutePath))
                }
            }
        }
    }
    
    private fun buildEnvironment(runtimeDir: File, nativeLibraryDir: String): Map<String, String> {
        val env = mutableMapOf<String, String>()

        val systemLibPaths = mutableListOf(
            runtimeDir.absolutePath,
            nativeLibraryDir,
            "/system/lib64",
            "/vendor/lib64",
            "/vendor/lib64/egl"
        )

        val inheritedLdLibraryPath = System.getenv("LD_LIBRARY_PATH")
        if (!inheritedLdLibraryPath.isNullOrBlank()) {
            systemLibPaths.add(inheritedLdLibraryPath)
        }

        env["LD_LIBRARY_PATH"] = systemLibPaths.joinToString(":")
        env["DSP_LIBRARY_PATH"] = runtimeDir.absolutePath
        env["ADSP_LIBRARY_PATH"] = runtimeDir.absolutePath
        env["MNN_OPENCL_TUNING"] = "WIDE"
        env["PATH"] = System.getenv("PATH") ?: ""

        return env
    }
    
    suspend fun loadModel(modelPath: String, backend: String = "auto"): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadModel: Starting (modelPath=$modelPath, backend=$backend)")
        
        try {
            val rawModelDir = File(modelPath)
            if (!rawModelDir.exists() || !rawModelDir.isDirectory) {
                Log.e(TAG, "loadModel: Model directory not found: $modelPath")
                return@withContext false
            }
            
            Log.d(TAG, "loadModel: Model directory exists and is readable")
            
            val normalizedBackend = when (backend.lowercase()) {
                "mnn", "cpu" -> "mnn"
                "qnn", "npu" -> "qnn"
                else -> "auto"
            }
            
            val cpuModelDir = resolveModelDir(rawModelDir, true)
            val qnnModelDir = resolveModelDir(rawModelDir, false)
            val npuSupported = isNpuSupported()
            
            Log.d(TAG, "loadModel: cpuModelDir=$cpuModelDir, qnnModelDir=$qnnModelDir, npuSupported=$npuSupported")
            
            val (selectedBackend, modelDir) = when (normalizedBackend) {
                "mnn" -> cpuModelDir?.let { "mnn" to it }
                "qnn" -> qnnModelDir?.let { "qnn" to it }
                else -> when {
                    qnnModelDir != null && npuSupported -> "qnn" to qnnModelDir
                    cpuModelDir != null -> "mnn" to cpuModelDir
                    qnnModelDir != null -> "qnn" to qnnModelDir
                    else -> null
                }
            } ?: run {
                Log.e(TAG, "loadModel: Could not find model files in $modelPath")
                return@withContext false
            }
            
            Log.d(TAG, "loadModel: Selected backend=$selectedBackend, modelDir=$modelDir")
            
            if (currentModelPath == modelPath && isServerReady) {
                if (serverProcess?.isAlive != true) {
                    Log.w(TAG, "loadModel: Previous server process is not alive but HTTP service is still marked ready. Reusing existing server for $modelPath.")
                } else {
                    Log.d(TAG, "loadModel: Model already loaded and server ready: $modelPath")
                }
                return@withContext true
            }
            
            if (currentModelPath != modelPath || !isServerReady) {
                Log.d(TAG, "loadModel: Stopping existing server before loading new model")
                stopServer()
            }
            
            Log.d(TAG, "loadModel: Starting server with selectedBackend=$selectedBackend")
            
            val result = tryStartServer(modelPath, modelDir, selectedBackend, selectedBackend == "mnn")
            
            if (!result && selectedBackend == "qnn" && cpuModelDir != null) {
                Log.w(TAG, "loadModel: QNN backend failed, falling back to MNN/CPU")
                stopServer()
                tryStartServer(modelPath, cpuModelDir, "mnn", true)
            } else {
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModel: Error loading model", e)
            stopServer()
            false
        }
    }
    
    private suspend fun tryStartServer(
        modelPath: String,
        modelDir: File,
        backend: String,
        isCpu: Boolean
    ): Boolean {
        Log.d(TAG, "tryStartServer: Starting (backend=$backend, isCpu=$isCpu)")
        
        val runtimeDir = prepareRuntimeDir()
        val executableFile = resolveExecutable() ?: return false
        
        val nativeDir = context.applicationInfo.nativeLibraryDir
        Log.d(TAG, "tryStartServer: executableFile=${executableFile.absolutePath} exists=${executableFile.exists()} canExecute=${executableFile.canExecute()} length=${executableFile.length()}")
        
        val command = buildCommand(executableFile, modelDir, runtimeDir, isCpu)
        val env = buildEnvironment(runtimeDir, nativeDir)
        
        Log.d(TAG, "COMMAND: ${command.joinToString(" ")}")
        Log.d(TAG, "LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")
        
        val processBuilder = ProcessBuilder(command).apply {
            directory(executableFile.parentFile)
            redirectErrorStream(true)
            environment().putAll(env)
        }
        
        Log.d(TAG, "tryStartServer: Spawning process...")
        serverProcess = try {
            processBuilder.start()
        } catch (e: IOException) {
            Log.w(TAG, "tryStartServer: direct exec failed, retrying with sh", e)
            val shellCommand = listOf("sh", "-c", command.joinToString(" ") { arg ->
                arg.replace("'", "'\\''").let { "'$it'" }
            })
            ProcessBuilder(shellCommand).apply {
                directory(executableFile.parentFile)
                redirectErrorStream(true)
                environment().putAll(env)
            }.start()
        }
        startMonitor()
        currentModelPath = modelPath
        currentBackend = backend
        isServerReady = false
        
        Log.d(TAG, "tryStartServer: serverProcess alive=${serverProcess?.isAlive}")
        val timeoutMs = if (isCpu) 180000L else 120000L
        val ready = waitForServer(timeoutMs)
        if (ready) {
            isServerReady = true
            Log.i(TAG, "tryStartServer: ✓ Server is ready on port $SERVER_PORT (backend: $backend)")
        } else {
            Log.e(TAG, "tryStartServer: ✗ Server failed to start within ${timeoutMs/1000}s")
        }
        
        return ready
    }
    
    private suspend fun waitForServer(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        var lastLogTime = 0L
        var processDied = false
        var portCheckCount = 0
        
        Log.d(TAG, "waitForServer: Starting health checks, timeout=${timeoutMs}ms")
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val alive = serverProcess?.isAlive == true
            if (!alive) {
                if (!processDied) {
                    Log.w(TAG, "waitForServer: Server process died while waiting (process=null or !isAlive)")
                    processDied = true
                }
                // If the binary daemonizes, continue checking the port.
            }

            try {
                val url = URL("http://127.0.0.1:$SERVER_PORT/")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                portCheckCount++
                Log.d(TAG, "waitForServer: Health check #$portCheckCount response: $code (alive=$alive, elapsed=${System.currentTimeMillis()-startTime}ms)")
                if (code == 200 || code == 404) {
                    Log.i(TAG, "waitForServer: Server is ready! (response=$code)")
                    return true
                }
            } catch (e: Exception) {
                portCheckCount++
                Log.d(TAG, "waitForServer: Health check #$portCheckCount failed (alive=$alive, elapsed=${System.currentTimeMillis()-startTime}ms): ${e.javaClass.simpleName}: ${e.message}")
                if (!alive && e is IOException) {
                    // If the process died, log process state repeatedly for debugging.
                    Log.w(TAG, "waitForServer: serverProcess state on failure: process=$serverProcess")
                }
            }

            delay(500)
        }
        
        Log.e(TAG, "waitForServer: Timeout! Failed to connect after ${System.currentTimeMillis()-startTime}ms, portCheckCount=$portCheckCount")
        return false
    }
    
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                serverProcess?.inputStream?.bufferedReader()?.use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.i(TAG, "[server] $line")
                    }
                }
                
                val exitCode = serverProcess?.waitFor() ?: -1
                Log.i(TAG, "Server process exited with code: $exitCode")
                isServerReady = false
            } catch (e: Exception) {
                Log.e(TAG, "Monitor error", e)
            }
        }
    }
    
    fun stopServer() {
        monitorJob?.cancel()
        monitorJob = null
        
        serverProcess?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
                Log.i(TAG, "Server process stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping server: ${e.message}")
            }
        }
        
        serverProcess = null
        currentModelPath = null
        currentBackend = null
        isServerReady = false
    }
    
    suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        onProgress: (Int, Int, Float, Bitmap?) -> Unit
    ): Bitmap? = withContext(Dispatchers.IO) {
        // ── 前段：テキストガード ──────────────────────────────────
        if (com.nezumi_ai.BuildConfig.SAFETY_PROMPT_FILTER_ENABLED &&
            PromptFilter.check(prompt) == PromptFilter.Result.BLOCK) {
            Log.w(TAG, "Prompt blocked by PromptFilter — skipping UNET inference")
            return@withContext null
        }
        // ─────────────────────────────────────────────────────────
        if (!isServerReady) {
            Log.e(TAG, "Server is not ready")
            return@withContext null
        }
        if (serverProcess?.isAlive != true) {
            Log.w(TAG, "Server process is not alive but service is marked ready; continuing with HTTP generation")
        }
        
        try {
            val body = JSONObject().apply {
                put("prompt", prompt)
                put("negative_prompt", negativePrompt)
                put("width", width)
                put("height", height)
                put("steps", steps)
                put("cfg", cfg)
                put("seed", if (seed < 0) (Math.random() * Int.MAX_VALUE).toInt() else seed)
                put("scheduler", "dpm")
                put("show_diffusion_process", true)
                put("show_diffusion_stride", 2)
            }
            
            Log.d(TAG, "Starting generation: ${body.toString().take(200)}...")
            
            val url = URL("http://127.0.0.1:$SERVER_PORT/generate")
            val conn = url.openConnection() as HttpURLConnection
            activeGenerationConn.set(conn)
            
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.connectTimeout = 10000
            conn.readTimeout = 600000
            
            OutputStreamWriter(conn.outputStream).use { 
                it.write(body.toString())
                it.flush()
            }
            
            val responseCode = conn.responseCode
            Log.d(TAG, "generateImage: POST /generate responseCode=$responseCode")
            if (responseCode != 200) {
                Log.e(TAG, "Server returned $responseCode")
                activeGenerationConn.set(null)
                return@withContext null
            }
            
            var completeData: JSONObject? = null
            var currentEventType = ""
            
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String? = null
                while (isActive && reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    
                    if (trimmed.startsWith("event: ")) {
                        currentEventType = trimmed.substring(7).trim()
                        continue
                    }
                    
                    if (!trimmed.startsWith("data: ")) continue
                    
                    try {
                        val data = JSONObject(trimmed.substring(6))
                        when (data.optString("type", currentEventType)) {
                            "progress" -> {
                                val (step, totalSteps) = normalizeServerProgress(
                                    serverStep = data.getInt("step"),
                                    serverTotalSteps = data.getInt("total_steps"),
                                    requestedSteps = steps
                                )
                                val previewBmp = data.optString("preview", "").takeIf { it.isNotEmpty() }?.let {
                                    runCatching { decodeRgbToBitmap(it, data.optInt("preview_width", width), data.optInt("preview_height", height)) }.getOrNull()
                                }
                                onProgress(step, totalSteps, 0f, previewBmp)
                            }
                            "preview" -> {
                                val (step, totalSteps) = normalizeServerProgress(
                                    serverStep = data.optInt("step", 0),
                                    serverTotalSteps = data.optInt("total_steps", steps),
                                    requestedSteps = steps
                                )
                                val previewBmp = data.optString("image", "").takeIf { it.isNotEmpty() }?.let {
                                    runCatching { decodeRgbToBitmap(it, data.optInt("width", width), data.optInt("height", height)) }.getOrNull()
                                }
                                onProgress(step, totalSteps, 0f, previewBmp)
                            }
                            "complete" -> {
                                completeData = data
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE data: ${e.message}")
                    }
                    
                    currentEventType = ""
                }
            }
            
            conn.disconnect()
            activeGenerationConn.set(null)
            
            if (!isActive) {
                Log.i(TAG, "Generation cancelled by coroutine")
                return@withContext null
            }
            
            completeData?.let { data ->
                val imageBase64 = data.getString("image")
                val w = data.getInt("width")
                val h = data.getInt("height")

                val raw = decodeRgbToBitmap(imageBase64, w, h) ?: return@let null
                return@withContext applySafetyFilter(raw)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Generation cancelled")
            activeGenerationConn.getAndSet(null)?.disconnect()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            activeGenerationConn.set(null)
            null
        }
    }
    
    fun cancelGeneration() {
        activeGenerationConn.getAndSet(null)?.disconnect()
    }
    
    private fun decodeRgbToBitmap(base64Rgb: String, width: Int, height: Int): Bitmap? {
        return try {
            val rgbBytes = Base64.decode(base64Rgb, Base64.DEFAULT)
            val expectedSize = width * height * 3
            
            if (rgbBytes.size != expectedSize) {
                Log.e(TAG, "RGB data size ${rgbBytes.size} doesn't match expected $expectedSize")
                return null
            }
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            
            for (i in 0 until width * height) {
                val idx = i * 3
                val r = rgbBytes[idx].toInt() and 0xFF
                val g = rgbBytes[idx + 1].toInt() and 0xFF
                val b = rgbBytes[idx + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode RGB to bitmap", e)
            null
        }
    }
    
    // ---- Safety Layer ----

    private suspend fun applySafetyFilter(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (!com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED) {
            Log.i(TAG, "Safety: image guard disabled by BuildConfig")
            return@withContext bitmap
        }
        val result = safetyChecker().check(bitmap)
        if (result == null) {
            Log.w(TAG, "Safety: check failed or model unavailable — BLOCK (fail-safe)")
            _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
            bitmap.recycle()
            return@withContext null
        }
        when (result.verdict) {
            SafetyResult.Verdict.BLOCK -> {
                Log.w(TAG, "Safety: BLOCK (nsfw=${result.nsfwScore})")
                _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
                bitmap.recycle()
                null
            }
            SafetyResult.Verdict.BLUR -> {
                Log.i(TAG, "Safety: BLUR (nsfw=${result.nsfwScore})")
                _lastSafetyVerdict = SafetyResult.Verdict.BLUR
                bitmap.toBlurred(radius = 25)
            }
            SafetyResult.Verdict.ALLOW -> {
                Log.d(TAG, "Safety: ALLOW (nsfw=${result.nsfwScore})")
                _lastSafetyVerdict = SafetyResult.Verdict.ALLOW
                bitmap
            }
        }
    }

    fun cleanup() {
        coroutineScope.cancel()
        _safetyChecker?.close()
        stopServer()
    }

    /**
     * メタデータ付きで画像を生成
     */
    suspend fun generateImageWithMetadata(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        onProgress: (Int, Int, Float, Bitmap?) -> Unit
    ): Pair<Bitmap?, ImageGenerationMetadata?>? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val resolvedSeed = if (seed < 0) {
            (Math.random() * Int.MAX_VALUE).toLong()
        } else {
            seed
        }
        
        val bitmap = generateImage(
            prompt = prompt,
            negativePrompt = negativePrompt,
            width = width,
            height = height,
            steps = steps,
            cfg = cfg,
            seed = resolvedSeed,
            onProgress = onProgress
        )
        
        if (bitmap != null) {
            val endTime = System.currentTimeMillis()
            val metadata = ImageGenerationMetadata(
                modelPath = currentModelPath ?: "",
                modelName = extractModelName(currentModelPath ?: ""),
                prompt = prompt,
                negativePrompt = negativePrompt,
                steps = steps,
                cfg = cfg,
                seed = resolvedSeed,
                width = width,
                height = height,
                backend = currentBackend ?: "unknown",
                timestamp = startTime,
                generationTimeMs = endTime - startTime
            )
            Pair(bitmap, metadata)
        } else {
            null
        }
    }

    private fun extractModelName(path: String): String {
        if (path.isEmpty()) return "Unknown"
        val dir = File(path)
        val dirName = dir.name
        
        // バックエンド情報を追加
        val backend = when {
            File(dir, "unet.bin").exists() -> "-QNN"
            File(dir, "unet.mnn").exists() -> "-MNN"
            else -> ""
        }
        
        return dirName + backend
    }

    /**
     * メタデータを画像に埋め込む（EXIF情報として）
     */
    fun embedMetadataInBitmap(bitmap: Bitmap, metadata: ImageGenerationMetadata): Bitmap {
        // NOTE: Androidでは、通常メタデータはファイル保存時にExifとして埋め込みます
        // ここではメタデータオブジェクトそのものを返し、保存時に別途埋め込みます
        return bitmap
    }
}
