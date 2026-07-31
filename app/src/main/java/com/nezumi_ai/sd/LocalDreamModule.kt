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
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
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
        return _safetyChecker ?: ImageSafetyChecker(context).also { _safetyChecker = it }
    }

    companion object {
        private const val TAG = "LocalDreamModule"
        private const val SERVER_PORT = 18081
        private const val RUNTIME_DIR = "runtime_libs"
        private const val DISABLE_NATIVE_SERVER_PROPERTY = "nezumi.disable_native_sd_server"

        internal fun shouldDisableNativeServerForTests(
            isDebugBuild: Boolean,
            systemProperty: String?
        ): Boolean {
            val enabled = systemProperty?.trim()?.lowercase()
            return when (enabled) {
                "true" -> isDebugBuild
                "false" -> false
                null -> false
                else -> false
            }
        }

        // xororz/local-dream では MNN の MnnSessionOptions で Precision_Low +
        // MNN_GPU_MEMORY_BUFFER + MNN_GPU_TUNING_FAST を付けることで OpenCL を安定化しているが、
        // ネズミ AI は外部バイナリに任せているためこちら側での制御ができない。
        // そこで、大きめの解像度 (>=512) では OpenCL をオフに倒すことで安全側に逃げる。
        internal const val OPENCL_SAFE_MAX_SIDE = 448
        // SDXL の最大許容辺長 (mnn-sd-engine 側 caps.max_side_px=1536 と合わせて 1024 に丸める)
        internal const val SDXL_MAX_SIDE = 1024

        internal fun resolveEffectiveUseOpenCL(
            userWantsOpenCL: Boolean,
            currentBackend: String?,
            maxSidePx: Int = 0,
            isSdxl: Boolean = false
        ): Boolean {
            // NPU (QNN) サポートは廃止済み。currentBackend は "mnn" (CPU) か
            // "qnn" (旧識別子だが実体は GPU/OpenCL) の 2 択で、どちらの経路も
            // MNN 側の OpenCL カーネルに合流するため、ここでは backend 名に
            // 依存せず「解像度と userWantsOpenCL」だけで判断する。
            if (!userWantsOpenCL) return false
            // SDXL は UNet の latent が 128x128 になるため、mobile GPU の OpenCL では
            // カーネル tuning が破綻する。強制的に CPU に落とす。
            if (isSdxl) return false
            // MNN CPU モードでも 512 クラスは OpenCL を避ける。
            //   背景: 512x512 の UNET latent (64x64 * feature) を mobile GPU で tuning させると
            //   カーネル JIT + 重み転送に数 GB の VRAM を使おうとしてドライバが abort する。
            //   ボーダーは OPENCL_SAFE_MAX_SIDE (448) で、これを超えたら CPU (MNN) に逃す。
            if (maxSidePx > OPENCL_SAFE_MAX_SIDE) return false
            return true
        }
    }

    private var serverProcess: Process? = null
    private var currentModelPath: String? = null
    private var currentBackend: String? = null
    var isServerReady = false
        private set
    private var monitorJob: Job? = null
    private var mnnModule: MnnSdModule? = null
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

    // NOTE: QNN/NPU バックエンドは廃止済み。以前は assets/qnnlibs/ 配下の
    //   QNN 実行ライブラリ (libQnnHtp.so 等) を毎起動時に filesDir にコピーする
    //   ロジックがここにあったが、いまはロードされないため機能上は no-op。
    //   assets 側にファイルが残っていても失敗するだけで生成には影響しない。
    //   将来的に assets/qnnlibs/ を完全削除する際にこの prepareRuntimeDir と
    //   関連ロジックも取り除くこと。
    @Volatile private var runtimeDirReady: File? = null

    private suspend fun prepareRuntimeDir(): File = withContext(Dispatchers.IO) {
        runtimeDirReady?.let { return@withContext it }

        val runtimeDir = File(context.filesDir, RUNTIME_DIR).apply {
            if (!exists()) mkdirs()
        }

        try {
            val qnnLibs = context.assets.list("qnnlibs")
            if (qnnLibs == null || qnnLibs.isEmpty()) {
                Log.w(TAG, "prepareRuntimeDir: assets/qnnlibs is empty or missing")
            } else {
                var copiedCount = 0
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
                        copiedCount++
                    }

                    targetLib.setReadable(true, true)
                    targetLib.setExecutable(true, true)
                }
                if (copiedCount > 0) {
                    Log.i(TAG, "prepareRuntimeDir: copied $copiedCount QNN libs into ${runtimeDir.absolutePath}")
                } else {
                    Log.d(TAG, "prepareRuntimeDir: all QNN libs already up-to-date")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "No QNN libraries found in assets: ${e.message}")
        }

        runtimeDir.setReadable(true, true)
        runtimeDir.setExecutable(true, true)
        runtimeDirReady = runtimeDir
        runtimeDir
    }


    private fun resolveModelDir(dir: File, isCpu: Boolean): File? {
        // Bug fix:
        //   旧実装は CPU(MNN) 側の判定を "unet.mnn / clip.mnn / vae_decoder.mnn"
        //   の固定ファイル名で見ていたため、現在主流の
        //   unet_asym_block32.mnn / clip_v2.mnn / vae_decoder_fp16.mnn を含む
        //   正常な変換済みモデルでも cpuModelDir=null になっていた。
        //   さらに tokenizer/ のような不完全フォルダも上位のスキャンで候補に
        //   混ざっており、UI がそれを選ぶと loadModel 全体が失敗していた。
        //   MNN 側は SdModelLayout の実際の解決ロジックに一本化する。
        if (isCpu) {
            val resolved = SdModelLayout.findUsableModelDir(dir)
            if (resolved != null) {
                Log.d(TAG, "resolveModelDir: resolved usable MNN dir ${resolved.absolutePath}")
                return resolved
            }
            Log.w(TAG, "resolveModelDir: no usable MNN dir under ${dir.absolutePath}")
            return null
        }

        val markerFile = "unet.bin"
        if (File(dir, markerFile).exists()) return dir

        fun searchDir(current: File, depth: Int): File? {
            if (depth > 3) return null
            current.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                if (File(subDir, markerFile).exists()) {
                    Log.d(TAG, "Found $markerFile in: ${subDir.absolutePath}")
                    val hasRequiredFiles =
                        File(subDir, "unet.bin").exists() &&
                        (File(subDir, "clip.bin").exists() || File(subDir, "clip_v2.mnn").exists()) &&
                        File(subDir, "vae_decoder.bin").exists()

                    if (hasRequiredFiles) {
                        return subDir
                    } else {
                        Log.w(TAG, "resolveModelDir: Found $markerFile but missing other required files in ${subDir.absolutePath}")
                    }
                }
                val deeper = searchDir(subDir, depth + 1)
                if (deeper != null) return deeper
            }
            return null
        }

        return searchDir(dir, 0)
    }

    // Legacy subprocess execution (libstable_diffusion_core.so) removed.
    // LocalDreamModule now prefers the JNI `MnnSdModule` path provided by mnn-sd-engine.

    suspend fun loadModel(modelPath: String, backend: String = "auto"): Boolean = withContext(Dispatchers.IO) {
        val disableNativeServer = shouldDisableNativeServerForTests(
            isDebugBuild = com.nezumi_ai.BuildConfig.DEBUG,
            systemProperty = System.getProperty(DISABLE_NATIVE_SERVER_PROPERTY)
        )
        // JNI attempt is performed after selecting the backend below.
        if (disableNativeServer) {
            Log.w(TAG, "loadModel: Native SD server disabled by system property; skipping server startup")
            currentModelPath = modelPath
            currentBackend = backend
            isServerReady = false
            return@withContext false
        }

        Log.d(TAG, "loadModel: Starting (modelPath=$modelPath, backend=$backend)")

        try {
            val rawModelDir = File(modelPath)
            if (!rawModelDir.exists() || !rawModelDir.isDirectory) {
                Log.e(TAG, "loadModel: Model directory not found: $modelPath")
                return@withContext false
            }

            Log.d(TAG, "loadModel: Model directory exists and is readable")

            // NPU (QNN) サポートは廃止。バックエンドは:
            //   - "mnn" / "cpu" : MNN CPU 経路
            //   - "qnn" / "gpu" / "npu" (後方互換) : MNN OpenCL 経路
            // どちらも MNN エンジン (mnn-sd-engine) の JNI に流れる。
            val normalizedBackend = when (backend.lowercase()) {
                "mnn", "cpu" -> "mnn"
                "gpu", "opencl", "qnn", "npu" -> "gpu"
                else -> "auto"
            }

            val cpuModelDir = resolveModelDir(rawModelDir, true)
            // 旧 QNN 形式 (.bin) のディレクトリも一応検出する: そこにしか .mnn が
            // 見つからないなら「対応形式でない」旨をログに残して失敗させる。
            val legacyQnnModelDir = resolveModelDir(rawModelDir, false)

            Log.d(TAG, "loadModel: cpuModelDir=$cpuModelDir, legacyQnnModelDir=$legacyQnnModelDir")

            val (selectedBackend, modelDir) = when (normalizedBackend) {
                "mnn" -> when {
                    cpuModelDir != null -> "mnn" to cpuModelDir
                    else -> null
                }
                "gpu" -> when {
                    // GPU (OpenCL) でも実体の重みは .mnn (unet.mnn / clip.mnn / vae_decoder.mnn)。
                    cpuModelDir != null -> "gpu" to cpuModelDir
                    else -> null
                }
                else -> when {
                    cpuModelDir != null -> "mnn" to cpuModelDir
                    else -> null
                }
            } ?: run {
                if (legacyQnnModelDir != null) {
                    Log.e(TAG, "loadModel: Only legacy QNN (.bin) model files were found under $modelPath. " +
                        "The QNN/NPU backend is discontinued. Please re-import an MNN (.mnn) formatted model.")
                } else {
                    Log.e(TAG, "loadModel: Could not find usable model files in $modelPath (backend=$backend)")
                }
                return@withContext false
            }

            Log.d(TAG, "loadModel: Selected backend=$selectedBackend, modelDir=$modelDir")

            // Try JNI-based MNN engine first (mnn-sd-engine)
            if (MnnSdNative.isAvailable()) {
                Log.i(TAG, "loadModel: MnnSdNative available — attempting JNI MNN engine load")
                stopServer()
                mnnModule?.cleanup()
                mnnModule = MnnSdModule(context)
                // "gpu" / "opencl" は MnnSdNative の BACKEND_OPENCL に、
                // "mnn" / "cpu" は BACKEND_CPU にマップする。
                val backendForMnn = if (selectedBackend == "mnn") "mnn" else "opencl"
                val loadedMnn = try {
                    mnnModule!!.loadModel(modelPath, backendForMnn)
                } catch (e: Exception) {
                    Log.e(TAG, "loadModel: JNI MNN module load failed", e)
                    false
                }
                if (loadedMnn) {
                    currentModelPath = modelPath
                    currentBackend = selectedBackend
                    isServerReady = mnnModule?.isServerReady == true
 Log.i(TAG, "loadModel: JNI MNN engine loaded, ready=${isServerReady}")
                    return@withContext true
                } else {
                    Log.w(TAG, "loadModel: JNI MNN engine failed to load; will attempt other backends")
                    mnnModule?.cleanup()
                    mnnModule = null
                }
            }

            if (currentModelPath == modelPath && isServerReady && currentBackend == selectedBackend) {
                if (serverProcess?.isAlive != true) {
                    Log.w(TAG, "loadModel: Previous server process is not alive but HTTP service is still marked ready. Reusing existing server for $modelPath.")
                } else {
                    Log.d(TAG, "loadModel: Model already loaded and server ready: $modelPath (backend=$currentBackend)")
                }
                return@withContext true
            }

            if (currentModelPath != modelPath || !isServerReady || currentBackend != selectedBackend) {
                Log.d(TAG, "loadModel: Stopping existing server before loading new model (backendChange: $currentBackend -> $selectedBackend)")
                stopServer()
            }

            Log.d(TAG, "loadModel: Starting server with selectedBackend=$selectedBackend")

            val result = tryStartServer(modelPath, modelDir, selectedBackend, selectedBackend == "mnn")

            // NPU/QNN 廃止に伴い旧 QNN → MNN フォールバック分岐は削除。
            // GPU (OpenCL) が失敗しても実体は同一の mnn-sd-engine JNI 経由なので
            // CPU への切り替えはユーザーが UI 上で選択し直す (ImageGenFragment)。
            result
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
        Log.i(TAG, "tryStartServer: legacy subprocess path removed — using JNI MNN engine only")
        // If a JNI-backed module is already initialized, report its readiness.
        if (mnnModule != null) {
            isServerReady = mnnModule?.isServerReady == true
            currentModelPath = modelPath
            currentBackend = backend
            Log.i(TAG, "tryStartServer: JNI module present, ready=${isServerReady}")
            return isServerReady
        }

        Log.w(TAG, "tryStartServer: JNI module not initialized or failed to load; not attempting deprecated subprocess startup")
        return false
    }

    private suspend fun waitForServer(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
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
                // 軽量なSocketでポート開放を先に出口調査してフリーズを防ぐ
                val isPortOpen = Socket().use { socket ->
                    try {
                        socket.connect(InetSocketAddress("127.0.0.1", SERVER_PORT), 150)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                if (isPortOpen) {
                    val url = URL("http://127.0.0.1:$SERVER_PORT/")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 300
                    conn.readTimeout = 300
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    portCheckCount++
                    // Perf fix: 200/404 で早期リターン。Debug ログはループ毎に出さず、
                    //           readiness が確定した瞬間だけ 1 行に絞る。
                    if (code == 200 || code == 404) {
                        Log.i(TAG, "waitForServer: Server is ready! (response=$code, checks=$portCheckCount, elapsed=${System.currentTimeMillis()-startTime}ms)")
                        return true
                    }
                } else {
                    portCheckCount++
                }
            } catch (e: Exception) {
                portCheckCount++
                if (!alive && e is IOException) {
                    // If the process died, log once for debugging.
                    Log.w(TAG, "waitForServer: serverProcess died during health check")
                }
            }

            // Perf fix: 生成直前の待機は 500ms ではなく段階的に間隔を広げ、
            //           CPU モデルの起動 (~1.5s) に対して不要な wake を減らす。
            val elapsed = System.currentTimeMillis() - startTime
            val delayMs = when {
                elapsed < 1000 -> 100L   // ホット期: すばやく検出
                elapsed < 5000 -> 250L
                else -> 500L             // コールドロード期は間隔を広げる
            }
            delay(delayMs)
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
                        // Perf fix: サーバーはトークン化された prompt をそのままダンプするので、
                        //           1KB 超のトークン列は logcat に刷き尽くすと UI スレッドを
                        //           ブロックする。長い行は先頭 300 文字にクリップして末尾に
                        //           切り捨てを明示。
                        val raw = line ?: continue
                        if (raw.length > 300) {
                            Log.i(TAG, "[server] ${raw.substring(0, 300)}... [+${raw.length - 300}ch truncated]")
                        } else {
                            Log.i(TAG, "[server] $raw")
                        }
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

    /**
     * MnnSdModule の capability を ViewModel/UI に直达させるブリッジ。
     * HTTP フォールバック経路ではどちらも false / ネイティブ未ロード相当になる。
     */
    val isCurrentModelSdxl: Boolean
        get() = mnnModule?.isCurrentModelSdxl == true

    val supportsImg2img: Boolean
        get() = mnnModule?.supportsImg2img == true

    suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        scheduler: SdScheduler = SdScheduler.DEFAULT,
        initImageRgb: ByteArray? = null,
        denoiseStrength: Float = 0f,
        onProgress: (Int, Int, Float) -> Unit
    ): Bitmap? = withContext(Dispatchers.IO) {
        // ── 前段：テキストガード ──────────────────────────────────
        if (com.nezumi_ai.BuildConfig.SAFETY_PROMPT_FILTER_ENABLED &&
            PromptFilter.check(prompt) == PromptFilter.Result.BLOCK) {
            Log.w(TAG, "Prompt blocked by PromptFilter — skipping UNET inference")
            return@withContext null
        }
        // 後段ガード有効時はセーフティモデル未準備なら UNET 推論前にダウンロードを試みる
        if (com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED &&
            !com.nezumi_ai.data.inference.ModelDownloadWorker.isSafetyModelReady(context)) {
            Log.i(TAG, "Safety model not yet downloaded, attempting to download...")
            val downloaded = com.nezumi_ai.data.inference.ModelDownloadWorker.downloadSafetyModelBlocking(context)
            if (!downloaded) {
                Log.w(TAG, "Safety model download failed — aborting generation")
                _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
                return@withContext null
            }
        }
        // セーフティモデルがロード可能か再チェック（ダウンロード後の検証）
        if (com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED &&
            !com.nezumi_ai.data.inference.ModelDownloadWorker.isSafetyModelUsable(context)) {
            Log.w(TAG, "Safety model not usable after download — aborting generation")
            _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
            return@withContext null
        }
        // ─────────────────────────────────────────────────────────
        val disableNativeServer = shouldDisableNativeServerForTests(
            isDebugBuild = com.nezumi_ai.BuildConfig.DEBUG,
            systemProperty = System.getProperty(DISABLE_NATIVE_SERVER_PROPERTY)
        )
        if (disableNativeServer) {
            Log.w(TAG, "generateImage: Native SD server disabled by system property; skipping generation")
            return@withContext null
        }
        if (!isServerReady) {
            Log.e(TAG, "Server is not ready")
            return@withContext null
        }
        if (mnnModule != null) {
            Log.d(TAG, "generateImage: Using JNI MNN module for generation (img2img=${initImageRgb != null})")
            return@withContext mnnModule!!.generateImage(
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfg = cfg,
                seed = seed,
                scheduler = scheduler,
                initImageRgb = initImageRgb,
                denoiseStrength = denoiseStrength,
                onProgress = onProgress
            )
        }
        if (initImageRgb != null) {
            Log.w(TAG, "generateImage: HTTP fallback path does not support img2img — ignoring init_image")
        }
        if (serverProcess?.isAlive != true) {
            Log.w(TAG, "Server process is not alive but service is marked ready; continuing with HTTP generation")
        }

        try {
            val userWantsOpenCL = PreferencesHelper.isSdUseOpenCL(context)
            val maxSide = kotlin.math.max(width, height)
            // SDXL モデルが読み込まれているかは mnnModule 経由で判定 (HTTP フォールバック時は false)。
            val isSdxl = mnnModule?.isCurrentModelSdxl == true
            val effectiveUseOpenCL = resolveEffectiveUseOpenCL(userWantsOpenCL, currentBackend, maxSide, isSdxl)
            if (userWantsOpenCL != effectiveUseOpenCL) {
                Log.w(TAG, "generateImage: use_opencl の解決結果が期待値と異なりました (requested=$userWantsOpenCL, effective=$effectiveUseOpenCL, side=$maxSide, backend=$currentBackend)")
            }
            // 512 クラスの生成はネイティブ側で latent (64×64 * feature) と VAE デコーダの
            // 中間テンソルを同時に抱えるため、バックグラウンド側の Bitmap キャッシュを
            // 一度回収してやると OOM 確率が下がる (local-dream の Memory_Low 相当のヒント)。
            if (maxSide >= 512) {
                System.gc()
            }

            val body = JSONObject().apply {
                put("prompt", prompt)
                put("negative_prompt", negativePrompt)
                put("width", width)
                put("height", height)
                put("steps", steps)
                put("cfg", cfg)
                put("seed", if (seed < 0) (Math.random() * Int.MAX_VALUE).toInt() else seed)
                put("scheduler", scheduler.httpValue)
                put("use_opencl", effectiveUseOpenCL)
                put("show_diffusion_process", false)
            }

            Log.d(TAG, "Starting generation: ${body.toString().take(200)}...")
            Log.d(TAG, "[LocalDream] request size=${width}x${height}")

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

            var lastProgressTime = 0L
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
                            "progress", "preview" -> {
                                val (step, totalSteps) = normalizeServerProgress(
                                    serverStep = data.optInt("step", data.optInt("step", 0)),
                                    serverTotalSteps = data.optInt("total_steps", steps),
                                    requestedSteps = steps
                                )
                                val now = System.currentTimeMillis()
                                // Perf fix: 進捗コールバックは Compose の recomposition を引き起こし、
                                // 512 クラスでは 400ms スロットルだと main thread を不必要に食う。
                                // 800ms に後退し、末尾ステップのみ確実に通知する。
                                if (now - lastProgressTime > 800 || step == totalSteps) {
                                    onProgress(step, totalSteps, 0f)
                                    lastProgressTime = now
                                }
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

                Log.d(TAG, "[LocalDream] response size=${w}x${h}")
                val raw = decodeRgbToBitmap(imageBase64, w, h) ?: return@let null
                return@withContext applySafetyFilter(raw)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Generation cancelled via coroutine")
            activeGenerationConn.getAndSet(null)?.disconnect()
            throw e // ViewModel 側でハンドリングさせる
        } catch (e: java.net.SocketException) {
            Log.i(TAG, "Socket closed during generation (likely due to cancellation)")
            activeGenerationConn.getAndSet(null)?.disconnect()
            throw e // ViewModel 側でハンドリングさせる
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            activeGenerationConn.set(null)
            null
        }
    }

    fun cancelGeneration() {
        activeGenerationConn.getAndSet(null)?.disconnect()
    }

    /**
     * Perf fix:
     * - 旧実装は Kotlin の for-in で 262,144 回 (512x512) 個別に Int を組み立て、
     *   都度オートボクシングと bounds check が入りメイン UNET とは別スレッドの CPU を
     *   数百 ms 焼き付けて GC を圧迫していた (Davey! 800ms ログの原因の 1 つ)。
     * - 現在は一度だけ IntArray を確保し、byte 配列を直接インデックスして
     *   バイトから ARGB int を組み立てる。ループ本体を単純化し JIT に任せる。
     *   さらに 512x512 で 3MB 弱の中間 pixels[] だけで済ませ、setPixels に一発で渡す。
     */
    private fun decodeRgbToBitmap(base64Rgb: String, width: Int, height: Int): Bitmap? {
        return try {
            val rgbBytes = Base64.decode(base64Rgb, Base64.DEFAULT)
            val total = width * height
            val expectedSize = total * 3

            if (rgbBytes.size != expectedSize) {
                Log.e(TAG, "RGB data size ${rgbBytes.size} doesn't match expected $expectedSize")
                return null
            }

            val pixels = IntArray(total)
            var src = 0
            var i = 0
            val alpha = 0xFF shl 24
            while (i < total) {
                val r = rgbBytes[src].toInt() and 0xFF
                val g = rgbBytes[src + 1].toInt() and 0xFF
                val b = rgbBytes[src + 2].toInt() and 0xFF
                pixels[i] = alpha or (r shl 16) or (g shl 8) or b
                src += 3
                i++
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM while decoding RGB to bitmap (w=$width h=$height)", e)
            null
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
        val nsfwScore = safetyChecker().check(bitmap)
        if (nsfwScore == null) {
            Log.w(TAG, "Safety: check failed or model unavailable — BLOCK (fail-safe)")
            _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
            bitmap.recycle()
            return@withContext null
        }

        // Yahoo Open NSFW labels: [0: Safe, 1: NSFW]
        val safeProb = nsfwScore.getOrNull(0) ?: 1f
        val nsfwProb = nsfwScore.getOrNull(1) ?: 0f

        // 判定基準: NSFWの確率が0.8以上の場合にBLOCK
        val isUnsafe = nsfwProb >= 0.8f

        if (isUnsafe) {
            Log.w(TAG, "Safety: BLOCK (safe=$safeProb, nsfw=$nsfwProb)")
            _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
            bitmap.recycle()
            null
        } else {
            Log.d(TAG, "Safety: ALLOW (safe=$safeProb, nsfw=$nsfwProb)")
            _lastSafetyVerdict = SafetyResult.Verdict.ALLOW
            bitmap
        }
    }

    fun cleanup() {
        coroutineScope.cancel()
        // close呼び出しを除去

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
        scheduler: SdScheduler = SdScheduler.DEFAULT,
        initImageRgb: ByteArray? = null,
        denoiseStrength: Float = 0f,
        onProgress: (Int, Int, Float) -> Unit
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
            scheduler = scheduler,
            initImageRgb = initImageRgb,
            denoiseStrength = denoiseStrength,
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
                scheduler = scheduler.id,
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

        // バックエンド情報を追加。NPU (QNN) は廃止済みなので旧形式は "-Legacy" 表記。
        val backend = when {
            File(dir, "unet.mnn").exists() -> "-MNN"
            File(dir, "unet.bin").exists() -> "-Legacy"
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
