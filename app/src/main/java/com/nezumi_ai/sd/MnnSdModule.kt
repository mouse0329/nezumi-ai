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

        val layout = SdModelLayout.resolve(File(modelPath))
            ?: return@withContext ProbeResult(
                modelPath,
                emptyMap(),
                listOf("UNet marker not found under $modelPath (unet.mnn / unet_asym_block32.mnn, max depth 3)")
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

        if (logs.isEmpty()) {
            errors.add("No probe targets in ${layout.modelDir.absolutePath}")
        }

        ProbeResult(layout.modelDir.absolutePath, logs, errors)
    }

    /** @see SdModelLayout.findModelDir */
    internal fun resolveModelDir(dir: File): File? = SdModelLayout.findModelDir(dir)

    fun close() {
        if (handle != 0L) {
            MnnSdNative.destroy(handle)
            handle = 0L
        }
    }
}
