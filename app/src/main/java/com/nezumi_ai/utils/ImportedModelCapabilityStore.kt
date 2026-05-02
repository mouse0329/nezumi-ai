package com.nezumi_ai.utils

import android.content.Context
import java.io.File

data class ImportedModelCapabilities(
    val imageEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
    val mmprojPath: String? = null,
    val thinkingEnabled: Boolean = false
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
    private fun mmprojKey(path: String) = "${normalizeKey(path)}#mmproj"
    private fun thinkingKey(path: String) = "${normalizeKey(path)}#thinking"

    fun get(context: Context, modelPath: String): ImportedModelCapabilities {
        val p = prefs(context)
        val lowered = modelPath.lowercase()
        val isImportedGguf =
            File(modelPath).isAbsolute && lowered.endsWith(".gguf")
        // 単一 GGUF にビジョン／音声が統合されているモデル（例: unsloth/gemma-4-E2B-it-GGUF）では mmproj 不要。
        // キー未設定時はマルチモーダル ON を既定にし、モデル設定で明示的にオフにできるようにする。
        val defaultImage = if (isImportedGguf) true else false
        val defaultAudio = if (isImportedGguf) true else false
        return ImportedModelCapabilities(
            imageEnabled = p.getBoolean(imageKey(modelPath), defaultImage),
            audioEnabled = p.getBoolean(audioKey(modelPath), defaultAudio),
            mmprojPath = p.getString(mmprojKey(modelPath), null),
            thinkingEnabled = p.getBoolean(thinkingKey(modelPath), false)
        )
    }

    fun set(context: Context, modelPath: String, capabilities: ImportedModelCapabilities) {
        prefs(context).edit()
            .putBoolean(imageKey(modelPath), capabilities.imageEnabled)
            .putBoolean(audioKey(modelPath), capabilities.audioEnabled)
            .putBoolean(thinkingKey(modelPath), capabilities.thinkingEnabled)
            .apply {
                if (capabilities.mmprojPath != null) putString(mmprojKey(modelPath), capabilities.mmprojPath)
                else remove(mmprojKey(modelPath))
            }
            .commit()
    }

    fun clear(context: Context, modelPath: String) {
        prefs(context).edit()
            .remove(imageKey(modelPath))
            .remove(audioKey(modelPath))
            .remove(mmprojKey(modelPath))
            .remove(thinkingKey(modelPath))
            .commit()
    }

    fun resolveForModel(context: Context, modelKey: String): ImportedModelCapabilities {
        val lowered = modelKey.lowercase()
        val isAbsolutePath = File(modelKey).isAbsolute
        if (isAbsolutePath && lowered.endsWith(".gguf")) {
            // GGUF は get() 側でキー未設定時に画像・音声を既定 ON（統合型 GGUF を想定）。
            return get(context, modelKey)
        }
        val isImported =
            isAbsolutePath &&
                (lowered.endsWith(".task") || lowered.endsWith(".litertlm"))
        if (!isImported) {
            // Built-in Gemma models are fully multimodal.
            return ImportedModelCapabilities(imageEnabled = true, audioEnabled = true)
        }
        return get(context, modelKey)
    }
}
