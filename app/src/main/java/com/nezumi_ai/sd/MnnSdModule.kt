package com.nezumi_ai.sd

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.sd.safety.ImageSafetyChecker
import com.nezumi_ai.sd.safety.PromptFilter
import com.nezumi_ai.sd.safety.SafetyResult
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MNN 自前 SD エンジン。LocalDreamModule の JNI 置き換え。
 */
class MnnSdModule(private val context: Context) {

    companion object {
        private const val TAG = "MnnSdModule"
        // SD1.5 基準の OpenCL 安全上限。SDXL は別途 resolveMnnBackend() で 0 に切り下げる。
        const val OPENCL_SAFE_MAX_SIDE = 448
        // SDXL の最大安全上限。mnn-sd-engine 側 sdxl-support.patch で caps.max_side_px=1536 にしているのと合わせる。
        const val SDXL_MAX_SIDE = 1024

        internal fun resolveMnnBackend(
            backend: String,
            maxSidePx: Int = 0,
            isSdxl: Boolean = false
        ): Int {
            val normalized = backend.trim().lowercase()
            // NPU (QNN) は廃止。旧識別子 "qnn"/"npu" が渡されても実体は
            // MNN OpenCL 経路にリダイレクトする (UI 側の SharedPreferences で
            // 過去に保存された値との後方互換のため)。
            val wantsOpenCl = normalized == "opencl" ||
                              normalized == "gpu" ||
                              normalized == "qnn" ||
                              normalized == "npu"
            if (!wantsOpenCl) return MnnSdNative.BACKEND_CPU
            // SDXL は UNet 重みが大きすぎて mobile GPU の OpenCL tuning が安定しないので強制 CPU。
            if (isSdxl) return MnnSdNative.BACKEND_CPU
            return if (maxSidePx > OPENCL_SAFE_MAX_SIDE) {
                MnnSdNative.BACKEND_CPU
            } else {
                MnnSdNative.BACKEND_OPENCL
            }
        }
    }

    private var handle: Long = 0L
    private var currentModelPath: String? = null
    private var currentBackend: String? = null
    var isServerReady = false
        private set

    /**
     * 現在ロードしているモデルが SDXL かどうか。UI 側はこれを参照して
     * サイズスライダーの上限を 512 → 1024 に拡張する。
     */
    var isCurrentModelSdxl = false
        private set

    /**
     * vae_encoder_fp16.mnn (もしくは vae_encoder.mnn) が同梱されていて
     * img2img が使えるかどうか。native の caps.supports_img2img と一致。
     */
    var supportsImg2img = false
        private set

    private var _safetyChecker: ImageSafetyChecker? = null
    private var _lastSafetyVerdict: SafetyResult.Verdict? = null

    fun getLastSafetyVerdict(): SafetyResult.Verdict? = _lastSafetyVerdict
    fun clearLastSafetyVerdict() { _lastSafetyVerdict = null }

    private fun safetyChecker(): ImageSafetyChecker {
        return _safetyChecker ?: ImageSafetyChecker(context).also { _safetyChecker = it }
    }

    fun isNativeAvailable(): Boolean = MnnSdNative.isAvailable()

    suspend fun loadModel(modelPath: String, backend: String = "mnn"): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadModel: Starting (modelPath=$modelPath, backend=$backend)")

        if (!MnnSdNative.isAvailable()) {
            Log.e(TAG, "loadModel: mnn_sd_jni not loaded — run scripts/build_mnn_android.ps1")
            return@withContext false
        }

        val rawModelDir = File(modelPath)
        if (!rawModelDir.exists() || !rawModelDir.isDirectory) {
            Log.e(TAG, "loadModel: Model directory not found: $modelPath")
            return@withContext false
        }

        val layout = SdModelLayout.resolve(rawModelDir)
        if (layout == null) {
            Log.e(TAG, "loadModel: No MNN model files under $modelPath")
            return@withContext false
        }

        // NPU (QNN) は廃止。旧識別子も OpenCL 経路にマップして後方互換を保つ。
        val normalizedBackend = when (backend.lowercase()) {
            "opencl", "gpu", "qnn", "npu" -> "opencl"
            else -> "mnn"
        }

        if (currentModelPath == modelPath && isServerReady && currentBackend == normalizedBackend &&
            handle != 0L && MnnSdNative.isLoaded(handle)
        ) {
            Log.d(TAG, "loadModel: Reusing loaded model $modelPath (backend=$currentBackend)")
            return@withContext true
        }

        unloadInternal()

        if (handle == 0L) {
            handle = MnnSdNative.create()
        }

        // model.json + ファイル配置から SDXL を事前判定し、backend 選択と caps に反映させる。
        val sdxl = SdModelLayout.isSdxlModelDir(layout.modelDir)
        val mnnBackend = resolveMnnBackend(normalizedBackend, isSdxl = sdxl)
        val safeMaxSide = if (sdxl) MnnSdNative.OPENCL_SAFE_MAX_SIDE_SDXL else OPENCL_SAFE_MAX_SIDE
        val loaded = MnnSdNative.load(
            handle,
            layout.modelDir.absolutePath,
            mnnBackend,
            safeMaxSide
        )

        if (!loaded) {
            Log.e(TAG, "loadModel: Native load failed: ${MnnSdNative.getLastError()}")
            return@withContext false
        }

        currentModelPath = modelPath
        currentBackend = normalizedBackend
        // ネイティブの caps を取れるなら優先する。未対応 .so は null を返すので layout 判定にフォールバック。
        runCatching {
            val capsJson = MnnSdNative.getCapabilities(handle)
            if (!capsJson.isNullOrBlank()) {
                val obj = org.json.JSONObject(capsJson)
                isCurrentModelSdxl = obj.optInt("is_sdxl", if (sdxl) 1 else 0) == 1
                supportsImg2img = obj.optInt("supports_img2img", 0) == 1
            } else {
                isCurrentModelSdxl = sdxl
                supportsImg2img = false
            }
        }.onFailure {
            isCurrentModelSdxl = sdxl
            supportsImg2img = false
        }
        isServerReady = true
        Log.i(
            TAG,
 "loadModel: Loaded ${layout.modelDir.absolutePath} "+
            "backend=$normalizedBackend sdxl=$isCurrentModelSdxl img2img=$supportsImg2img"
        )
        true
    }

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
        if (com.nezumi_ai.BuildConfig.SAFETY_PROMPT_FILTER_ENABLED &&
            PromptFilter.check(prompt) == PromptFilter.Result.BLOCK) {
            Log.w(TAG, "Prompt blocked by PromptFilter")
            return@withContext null
        }
        if (com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED &&
            !com.nezumi_ai.data.inference.ModelDownloadWorker.isSafetyModelReady(context)) {
            val downloaded = com.nezumi_ai.data.inference.ModelDownloadWorker.downloadSafetyModelBlocking(context)
            if (!downloaded) {
                _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
                return@withContext null
            }
        }
        if (com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED &&
            !com.nezumi_ai.data.inference.ModelDownloadWorker.isSafetyModelUsable(context)) {
            _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
            return@withContext null
        }

        if (!isServerReady || handle == 0L || !MnnSdNative.isLoaded(handle)) {
            Log.e(TAG, "generateImage: Engine not loaded")
            return@withContext null
        }

        // 大きめの解像度 (≥512) では latent + VAE デコーダのピークを削らすため GC をヒントする。
        // SDXL は 1024 まで行けるので境界を同じ 512 のまま使う。
        if (kotlin.math.max(width, height) >= 512) {
            System.gc()
        }

        onProgress(0, steps, 0f)
        Log.d(TAG, "[MnnSd] request size=${width}x${height}")

        val progressBridge = object : MnnSdNative.NativeProgressListener {
            override fun onNativeProgress(step: Int, totalSteps: Int, elapsedSec: Float) {
                try {
                    onProgress(step, totalSteps, elapsedSec)
                } catch (t: Throwable) {
                    Log.w(TAG, "onProgress threw", t)
                }
            }
        }

        // img2img ガード: capability=false なら native で MODEL_NOT_FOUND になるので早期に無効化。
        val effectiveInit: ByteArray? = if (initImageRgb != null && supportsImg2img) initImageRgb else null
        if (initImageRgb != null && !supportsImg2img) {
            Log.w(TAG, "generateImage: init_image supplied but model has no vae_encoder — ignoring")
        }
        val effInitW = if (effectiveInit != null) width else 0
        val effInitH = if (effectiveInit != null) height else 0
        val effDenoise = if (effectiveInit != null) denoiseStrength.coerceIn(0f, 1f) else 0f

        val packed = MnnSdNative.generate(
            handle = handle,
            prompt = prompt,
            negativePrompt = negativePrompt,
            width = width,
            height = height,
            steps = steps,
            cfg = cfg,
            seed = seed,
            scheduler = scheduler.nativeValue,
            initImageRgb = effectiveInit,
            initImageWidth = effInitW,
            initImageHeight = effInitH,
            denoiseStrength = effDenoise,
            progressListener = progressBridge
        )

        onProgress(steps, steps, 0f)

        if (packed == null) {
            Log.e(TAG, "generateImage: Native generate failed: ${MnnSdNative.getLastError()}")
            return@withContext null
        }

        val raw = decodePackedRgb(packed) ?: return@withContext null
        Log.d(TAG, "[MnnSd] response size=${raw.width}x${raw.height}")
        applySafetyFilter(raw.bitmap)
    }

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
                backend = currentBackend ?: "mnn",
                timestamp = startTime,
                generationTimeMs = System.currentTimeMillis() - startTime
            )
            Pair(bitmap, metadata)
        } else {
            null
        }
    }

    fun cancelGeneration() {
        if (handle != 0L) {
            MnnSdNative.cancel(handle)
        }
    }

    fun stopServer() {
        unloadInternal()
    }

    fun cleanup() {
        unloadInternal()
        if (handle != 0L) {
            MnnSdNative.destroy(handle)
            handle = 0L
        }
    }

    private fun unloadInternal() {
        if (handle != 0L) {
            MnnSdNative.unload(handle)
        }
        currentModelPath = null
        currentBackend = null
        isServerReady = false
    }

    private data class DecodedRgb(val width: Int, val height: Int, val bitmap: Bitmap)

    private fun decodePackedRgb(packed: ByteArray): DecodedRgb? {
        if (packed.size < 8) return null
        val header = ByteBuffer.wrap(packed, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        val rgbBytes = packed.copyOfRange(8, packed.size)
        val total = width * height
        val expectedSize = total * 3
        if (rgbBytes.size != expectedSize) {
            Log.e(TAG, "RGB size ${rgbBytes.size} != expected $expectedSize")
            return null
        }
        return try {
            val pixels = IntArray(total)
            var src = 0
            val alpha = 0xFF shl 24
            for (i in 0 until total) {
                val r = rgbBytes[src].toInt() and 0xFF
                val g = rgbBytes[src + 1].toInt() and 0xFF
                val b = rgbBytes[src + 2].toInt() and 0xFF
                pixels[i] = alpha or (r shl 16) or (g shl 8) or b
                src += 3
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            DecodedRgb(width, height, bitmap)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding RGB (w=$width h=$height)", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode RGB", e)
            null
        }
    }

    private suspend fun applySafetyFilter(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (!com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED) {
            return@withContext bitmap
        }
        val nsfwScore = safetyChecker().check(bitmap)
        if (nsfwScore == null) {
            _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
            bitmap.recycle()
            return@withContext null
        }
        val nsfwProb = nsfwScore.getOrNull(1) ?: 0f
        if (nsfwProb >= 0.8f) {
            _lastSafetyVerdict = SafetyResult.Verdict.BLOCK
            bitmap.recycle()
            null
        } else {
            _lastSafetyVerdict = SafetyResult.Verdict.ALLOW
            bitmap
        }
    }

    private fun extractModelName(path: String): String {
        if (path.isEmpty()) return "Unknown"
        return File(path).name + "-MNN"
    }

    // --- Phase 0 probe API (settings UI) ---

    data class ProbeResult(
        val modelDir: String,
        val logs: Map<String, String>,
        val errors: List<String>
    ) {
        val ok: Boolean get() = errors.isEmpty() && logs.isNotEmpty()

        fun summary(): String = buildString {
            appendLine("modelDir=$modelDir")
            logs.forEach { (name, log) ->
                appendLine("--- $name ---")
                appendLine(log.trim())
            }
            if (errors.isNotEmpty()) {
                appendLine("--- errors ---")
                errors.forEach { appendLine(it) }
            }
        }
    }

    suspend fun probeModelDirectory(
        modelPath: String,
        backend: Int = MnnSdNative.BACKEND_CPU
    ): ProbeResult = withContext(Dispatchers.IO) {
        if (!MnnSdNative.isAvailable()) {
            return@withContext ProbeResult(
                modelPath,
                emptyMap(),
                listOf("mnn_sd_jni not loaded — rebuild and deploy libmnn_sd_jni.so")
            )
        }

        val layout = SdModelLayout.resolve(File(modelPath))
            ?: return@withContext ProbeResult(
                modelPath,
                emptyMap(),
                listOf("UNet marker not found under $modelPath")
            )

        val logs = linkedMapOf<String, String>()
        val errors = mutableListOf<String>()

        for (name in layout.probeTargets()) {
            val file = File(layout.modelDir, name)
            val log = MnnSdNative.probeModel(file.absolutePath, backend)
            logs[name] = log
            val lastErr = MnnSdNative.getLastError()
            if (lastErr.isNotBlank()) {
                errors.add("$name: $lastErr")
            } else if (!log.contains("input ", ignoreCase = true) &&
                !log.contains("output ", ignoreCase = true)
            ) {
                errors.add("$name: probe returned no tensor info")
            }
            Log.i(TAG, "probe $name:\n$log")
        }

        ProbeResult(layout.modelDir.absolutePath, logs, errors)
    }

    internal fun resolveModelDir(dir: File): File? = SdModelLayout.findModelDir(dir)
}
