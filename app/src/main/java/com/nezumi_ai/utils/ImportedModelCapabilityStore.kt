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
        // 追加直後は画像・音声ともオフが安全（mmproj 未設定や非マルチモーダル GGUF でノイズ出力を防ぐ）。
        // 統合型などで使う場合はモデル設定から有効化する。
        val defaultImage = false
        val defaultAudio = false
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

    /** インポート GGUF のファイルリネーム後に設定キーを移す */
    fun migrateModelPath(context: Context, oldPath: String, newPath: String) {
        val caps = get(context, oldPath)
        clear(context, oldPath)
        set(context, newPath, caps)
    }

    fun resolveForModel(context: Context, modelKey: String): ImportedModelCapabilities {
        val lowered = modelKey.lowercase()
        val isAbsolutePath = File(modelKey).isAbsolute
        if (isAbsolutePath && lowered.endsWith(".gguf")) {
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
