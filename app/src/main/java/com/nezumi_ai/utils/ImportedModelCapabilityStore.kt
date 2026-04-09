package com.nezumi_ai.utils

import android.content.Context
import java.io.File

data class ImportedModelCapabilities(
    val imageEnabled: Boolean = false,
    val audioEnabled: Boolean = false
)

object ImportedModelCapabilityStore {
    private const val PREF_NAME = "imported_model_capabilities"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun normalizeKey(modelPath: String): String {
        return try {
            File(modelPath).canonicalPath
        } catch (_: Exception) {
            modelPath
        }
    }

    private fun imageKey(path: String) = "${normalizeKey(path)}#image"
    private fun audioKey(path: String) = "${normalizeKey(path)}#audio"

    fun get(context: Context, modelPath: String): ImportedModelCapabilities {
        val p = prefs(context)
        return ImportedModelCapabilities(
            imageEnabled = p.getBoolean(imageKey(modelPath), false),
            audioEnabled = p.getBoolean(audioKey(modelPath), false)
        )
    }

    fun set(context: Context, modelPath: String, capabilities: ImportedModelCapabilities) {
        prefs(context).edit()
            .putBoolean(imageKey(modelPath), capabilities.imageEnabled)
            .putBoolean(audioKey(modelPath), capabilities.audioEnabled)
            .apply()
    }

    fun clear(context: Context, modelPath: String) {
        prefs(context).edit()
            .remove(imageKey(modelPath))
            .remove(audioKey(modelPath))
            .apply()
    }

    fun resolveForModel(context: Context, modelKey: String): ImportedModelCapabilities {
        val lowered = modelKey.lowercase()
        val isImported =
            (lowered.endsWith(".task") || lowered.endsWith(".litertlm")) &&
                File(modelKey).isAbsolute
        if (!isImported) {
            // Built-in Gemma models are fully multimodal.
            return ImportedModelCapabilities(imageEnabled = true, audioEnabled = true)
        }
        return get(context, modelKey)
    }
}
