package com.nezumi_ai.utils

import android.content.Context
import java.io.File

data class ImportedModelCapabilities(
    val imageEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
    val mmprojPath: String? = null,
    val thinkingEnabled: Boolean = false,
    /** UI 表示用の名前（ファイル名とは別） */
    val displayName: String? = null,
    /** ツール呼び出しを有効化（LiteRT-LM / GGUF） */
    val toolCallingEnabled: Boolean = false
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
    private fun displayNameKey(path: String) = "${normalizeKey(path)}#displayName"
    private fun toolCallingKey(path: String) = "${normalizeKey(path)}#toolCalling"

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
            thinkingEnabled = p.getBoolean(thinkingKey(modelPath), false),
            displayName = p.getString(displayNameKey(modelPath), null),
            toolCallingEnabled = p.getBoolean(toolCallingKey(modelPath), false)
        )
    }

    fun set(context: Context, modelPath: String, capabilities: ImportedModelCapabilities) {
        prefs(context).edit()
            .putBoolean(imageKey(modelPath), capabilities.imageEnabled)
            .putBoolean(audioKey(modelPath), capabilities.audioEnabled)
            .putBoolean(thinkingKey(modelPath), capabilities.thinkingEnabled)
            .putBoolean(toolCallingKey(modelPath), capabilities.toolCallingEnabled)
            .apply {
                if (capabilities.mmprojPath != null) putString(mmprojKey(modelPath), capabilities.mmprojPath)
                else remove(mmprojKey(modelPath))
                val name = capabilities.displayName?.trim()
                if (!name.isNullOrEmpty()) putString(displayNameKey(modelPath), name)
                else remove(displayNameKey(modelPath))
            }
            .commit()
    }

    fun clear(context: Context, modelPath: String) {
        prefs(context).edit()
            .remove(imageKey(modelPath))
            .remove(audioKey(modelPath))
            .remove(mmprojKey(modelPath))
            .remove(thinkingKey(modelPath))
            .remove(displayNameKey(modelPath))
            .remove(toolCallingKey(modelPath))
            .commit()
    }

    fun resolveDisplayName(context: Context, modelPath: String, fallback: String): String {
        val stored = get(context, modelPath).displayName?.trim()
        return if (!stored.isNullOrEmpty()) stored else fallback
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
            val storedCaps = get(context, modelKey)
            if (!hasStoredCapabilities(context, modelKey) && !hasCompanionMmproj(File(modelKey))) {
                // Standalone GGUF multimodalモデルは mmproj がないと画像/音声入力が使えないため、
                // 初回未設定時は無効にしておく。
                return ImportedModelCapabilities(imageEnabled = false, audioEnabled = false)
            }
            return storedCaps
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

    private fun hasStoredCapabilities(context: Context, modelPath: String): Boolean {
        val prefs = prefs(context)
        return prefs.contains(imageKey(modelPath)) ||
            prefs.contains(audioKey(modelPath)) ||
            prefs.contains(mmprojKey(modelPath)) ||
            prefs.contains(thinkingKey(modelPath))
    }

    private fun hasCompanionMmproj(modelFile: File): Boolean {
        if (!modelFile.exists() || !modelFile.isFile) return false
        val parentDir = modelFile.parentFile ?: return false
        val baseName = modelFile.nameWithoutExtension
        val fileName = modelFile.name
        val candidateNames = listOf(
            // suffix/infix style (existing)
            "${baseName}.mmproj.gguf",
            "${baseName}.mmproj",
            "${baseName}_mmproj.gguf",
            "${baseName}_mmproj",
            "${fileName}.mmproj",
            "${fileName}.mmproj.gguf",
            "${fileName}_mmproj",
            "${fileName}_mmproj.gguf",
            // #18 fix: prefix-style names (common in HuggingFace/llama.cpp distributions)
            "mmproj-${baseName}.gguf",
            "mmproj-${baseName}-f16.gguf",
            "mmproj-${baseName}-f32.gguf",
            "mmproj_${baseName}.gguf",
            "mmproj-${baseName}"
        )
        return candidateNames.any { candidateName ->
            File(parentDir, candidateName).exists()
        }
    }
}

