package com.nezumi_ai.sd

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 0 helper for the in-house MNN SD engine.
 *
 * Phase 1+ will mirror [LocalDreamModule]'s generate API via JNI.
 * For now: model-dir resolution + `.mnn` shape probe only.
 */
class MnnSdModule {

    companion object {
        private const val TAG = "MnnSdModule"
        const val OPENCL_SAFE_MAX_SIDE = LocalDreamModule.OPENCL_SAFE_MAX_SIDE
    }

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

    private var handle: Long = 0L

    fun isNativeAvailable(): Boolean = MnnSdNative.isAvailable()

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

        val modelDir = resolveModelDir(File(modelPath))
            ?: return@withContext ProbeResult(
                modelPath,
                emptyMap(),
                listOf("unet.mnn not found under $modelPath (max depth 3)")
            )

        val targets = listOf("unet.mnn", "clip.mnn", "clip_v2.mnn", "vae_decoder.mnn")
        val logs = linkedMapOf<String, String>()
        val errors = mutableListOf<String>()

        for (name in targets) {
            val file = File(modelDir, name)
            if (!file.exists()) continue
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

        if (logs.isEmpty()) {
            errors.add("No .mnn files found in ${modelDir.absolutePath}")
        }

        ProbeResult(modelDir.absolutePath, logs, errors)
    }

    /** Same marker-file search as [LocalDreamModule] (MNN / CPU). */
    internal fun resolveModelDir(dir: File): File? {
        if (File(dir, "unet.mnn").exists()) return dir

        fun search(current: File, depth: Int): File? {
            if (depth > 3) return null
            current.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (File(sub, "unet.mnn").exists()) return sub
                search(sub, depth + 1)?.let { return it }
            }
            return null
        }
        return search(dir, 0)
    }

    fun close() {
        if (handle != 0L) {
            MnnSdNative.destroy(handle)
            handle = 0L
        }
    }
}
