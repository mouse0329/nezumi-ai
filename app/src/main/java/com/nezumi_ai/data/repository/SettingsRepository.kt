package com.nezumi_ai.data.repository

import android.content.Context
import android.util.Log
import com.nezumi_ai.data.database.dao.ChatSessionDao
import com.nezumi_ai.data.database.dao.SettingsDao
import com.nezumi_ai.data.database.entity.SettingsEntity
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.ModelFileManager
import java.io.File
import kotlinx.coroutines.flow.Flow

class SettingsRepository(
    private val dao: SettingsDao,
    private val chatSessionDao: ChatSessionDao
) {
    companion object {
        private const val BACKEND_CPU = "CPU"
        private const val BACKEND_GPU = "GPU"
        private const val BACKEND_NPU = "NPU"
        private const val MODEL_E2B = "E2B"
        private const val MODEL_E4B = "E4B"
        private const val MODEL_IMPORTED = "IMPORTED"
        private const val MODEL_ALL = "ALL"
    }
    
    fun getSettings(): Flow<SettingsEntity?> = dao.getSettingsFlow()
    
    suspend fun updateModel(model: String) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(current.copy(selectedModel = model))
    }
    
    suspend fun updateBackend(backend: String) {
        val current = dao.getSettings() ?: SettingsEntity()
        val normalizedBackend = normalizeBackend(backend)
        dao.update(
            current.copy(
                backendType = encodeBackendMap(
                    linkedMapOf(
                        MODEL_E2B to normalizedBackend,
                        MODEL_E4B to normalizedBackend,
                        MODEL_IMPORTED to normalizedBackend
                    )
                )
            )
        )
    }
    
    suspend fun updateAutoFallback(enabled: Boolean) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(current.copy(autoFallback = enabled))
    }
    
    suspend fun initializeSettingsIfNeeded(context: Context) {
        if (dao.getSettings() == null) {
            dao.insert(SettingsEntity())
        }
        migrateImportedLiteRtLmStoredPaths(context)
    }

    private suspend fun migrateImportedLiteRtLmStoredPaths(context: Context) {
        val renames = ModelFileManager.migrateImportedLegacyLiteRtLmFiles(context)
        if (renames.isEmpty()) return
        val settings = dao.getSettings() ?: return
        var newSelected = settings.selectedModel
        for ((oldPath, newPath) in renames) {
            if (newSelected == oldPath) {
                newSelected = newPath
            }
            chatSessionDao.updateSelectedModelPath(oldPath, newPath)
        }
        if (newSelected != settings.selectedModel) {
            dao.update(settings.copy(selectedModel = newSelected))
        }
    }

    suspend fun getSelectedModel(): String {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        return current.selectedModel
    }

    suspend fun isResourceMonitorEnabled(): Boolean {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        return current.resourceMonitorEnabled
    }

    suspend fun updateResourceMonitorEnabled(enabled: Boolean) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(current.copy(resourceMonitorEnabled = enabled))
    }

    suspend fun getInferenceConfig(): InferenceConfig {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        val backendForSelected = parseBackendMap(current.backendType)[modelToBackendKey(current.selectedModel)]
            ?: BACKEND_CPU
        val contextWindowForSelected = parseContextWindowMap(current.contextWindowMap)[modelToBackendKey(current.selectedModel)]
            ?: 4096
        val enableThinking =
            current.gemmaThinkingEnabled && isBuiltinGemma4Model(current.selectedModel)
        return InferenceConfig(
            contextWindow = contextWindowForSelected,
            contextCompressionEnabled = current.contextCompressionEnabled,
            contextCompressionThresholdPercent = current.contextCompressionThresholdPercent,
            temperature = current.temperature,
            maxTopK = current.maxTopK,
            maxTokens = current.maxTokens,
            enableThinking = enableThinking,
            backendType = backendForSelected
        ).normalized()
    }

    suspend fun getInferenceConfigForModel(model: String): InferenceConfig {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        val backend = getBackendForModel(model)
        val contextWindow = getContextWindowForModel(model)
        val isGemma4 = isBuiltinGemma4Model(model)
        val enableThinking = current.gemmaThinkingEnabled && isGemma4
        val base = getInferenceConfig()
        Log.d("SettingsRepository", "getInferenceConfigForModel: model=$model, isGemma4=$isGemma4, gemmaThinkingEnabled=${current.gemmaThinkingEnabled}, enableThinking=$enableThinking")
        return base.copy(
            backendType = backend,
            contextWindow = contextWindow,
            enableThinking = enableThinking
        ).normalized()
    }

    suspend fun getBackendForModel(model: String): String {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        val parsed = parseBackendMap(current.backendType)
        return parsed[modelToBackendKey(model)] ?: BACKEND_CPU
    }

    suspend fun getContextWindowForModel(model: String): Int {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        val parsed = parseContextWindowMap(current.contextWindowMap)
        val stored = parsed[modelToBackendKey(model)] ?: 4096
        
        // モデル別の最大値を制約
        val maxWindow = when {
            model.equals("Gemma4-2B", ignoreCase = true) || model.equals("Gemma4-4B", ignoreCase = true) -> 8192
            else -> 4096  // Gemma3n (E2B, E4B) や他のモデル
        }
        
        return stored.coerceIn(512, maxWindow)
    }

    suspend fun updateInferenceConfig(
        contextCompressionEnabled: Boolean,
        contextCompressionThresholdPercent: Int,
        temperature: Float,
        maxTopK: Int,
        maxTokens: Int,
        contextWindow: Int = 4096,
        backendType: String,
        backendTargetModel: String = MODEL_E2B
    ) {
        val current = dao.getSettings() ?: SettingsEntity()
        val backendMap = parseBackendMap(current.backendType)
        val contextWindowMap = parseContextWindowMap(current.contextWindowMap).toMutableMap()
        val normalizedBackend = normalizeBackend(backendType)
        val target = modelToBackendKey(backendTargetModel)
        
        // モデル別の最大コンテキスト窓を適用
        fun getMaxContextWindow(key: String): Int = when {
            key.contains("Gemma4", ignoreCase = true) -> 8192
            else -> 4096  // Gemma3n (E2B, E4B) や IMPORTED
        }
        
        val constrainedWindow = contextWindow.coerceIn(512, getMaxContextWindow(backendTargetModel))
        
        if (target == MODEL_ALL) {
            backendMap[MODEL_E2B] = normalizedBackend
            backendMap[MODEL_E4B] = normalizedBackend
            backendMap[MODEL_IMPORTED] = normalizedBackend
            contextWindowMap[MODEL_E2B] = contextWindow.coerceIn(512, getMaxContextWindow(MODEL_E2B))
            contextWindowMap[MODEL_E4B] = contextWindow.coerceIn(512, getMaxContextWindow(MODEL_E4B))
            contextWindowMap[MODEL_IMPORTED] = contextWindow.coerceIn(512, getMaxContextWindow(MODEL_IMPORTED))
        } else {
            backendMap[target] = normalizedBackend
            contextWindowMap[target] = constrainedWindow
        }
        val config = InferenceConfig(
            contextWindow = constrainedWindow,
            contextCompressionEnabled = contextCompressionEnabled,
            contextCompressionThresholdPercent = contextCompressionThresholdPercent,
            temperature = temperature,
            maxTopK = maxTopK,
            maxTokens = maxTokens,
            backendType = normalizedBackend
        ).normalized()
        dao.update(
            current.copy(
                contextWindow = config.contextWindow,
                contextWindowMap = encodeContextWindowMap(contextWindowMap),
                contextCompressionEnabled = config.contextCompressionEnabled,
                contextCompressionThresholdPercent = config.contextCompressionThresholdPercent,
                temperature = config.temperature,
                maxTopK = config.maxTopK,
                maxTokens = config.maxTokens,
                backendType = encodeBackendMap(backendMap),
                lastModified = System.currentTimeMillis()
            )
        )
    }


    private fun normalizeBackend(value: String): String {
        return when {
            value.equals(BACKEND_GPU, ignoreCase = true) -> BACKEND_GPU
            value.equals(BACKEND_NPU, ignoreCase = true) -> BACKEND_NPU
            else -> BACKEND_CPU
        }
    }

    private fun modelToBackendKey(model: String): String {
        val trimmed = model.trim()
        val lowered = trimmed.lowercase()
        val isImported =
            (lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && File(trimmed).isAbsolute
        return when {
            trimmed.equals(MODEL_ALL, ignoreCase = true) -> MODEL_ALL
            trimmed.equals(MODEL_E4B, ignoreCase = true) -> MODEL_E4B
            trimmed.equals(MODEL_IMPORTED, ignoreCase = true) -> MODEL_IMPORTED
            isImported -> MODEL_IMPORTED
            else -> MODEL_E2B
        }
    }

    private fun parseBackendMap(raw: String): LinkedHashMap<String, String> {
        val normalizedRaw = raw.trim()
        val allValue = when {
            normalizedRaw.equals(BACKEND_GPU, ignoreCase = true) -> BACKEND_GPU
            normalizedRaw.equals(BACKEND_NPU, ignoreCase = true) -> BACKEND_NPU
            normalizedRaw.equals(BACKEND_CPU, ignoreCase = true) -> BACKEND_CPU
            else -> null
        }
        if (allValue != null) {
            return linkedMapOf(
                MODEL_E2B to allValue,
                MODEL_E4B to allValue,
                MODEL_IMPORTED to allValue
            )
        }

        val map = linkedMapOf(
            MODEL_E2B to BACKEND_CPU,
            MODEL_E4B to BACKEND_CPU,
            MODEL_IMPORTED to BACKEND_CPU
        )
        normalizedRaw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val pair = entry.split('=', limit = 2)
                if (pair.size != 2) return@forEach
                val key = pair[0].trim().uppercase()
                val value = normalizeBackend(pair[1].trim())
                when (key) {
                    MODEL_E2B, MODEL_E4B, MODEL_IMPORTED -> map[key] = value
                }
            }
        return map
    }

    suspend fun getSystemPrompt(): String {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        return current.systemPrompt
    }

    suspend fun isGemmaThinkingEnabled(): Boolean {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        return current.gemmaThinkingEnabled
    }

    suspend fun updateGemmaThinkingEnabled(enabled: Boolean) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(
            current.copy(
                gemmaThinkingEnabled = enabled,
                lastModified = System.currentTimeMillis()
            )
        )
    }

    /**
     * LiteRT-LM では [InferenceConfig.enableThinking] 経由で `enable_thinking` を付与するため、
     * プロンプト先頭への `<|think|>` 注入は行わない（二重指定を避ける）。
     */
    suspend fun shouldInjectGemmaThinkTrigger(): Boolean = false

    private fun isBuiltinGemma4Model(model: String): Boolean {
        val t = model.trim()
        return t.equals("Gemma4-2B", ignoreCase = true) ||
            t.equals("Gemma4-4B", ignoreCase = true)
    }

    /** チャット画面の「このチャットでシンキングOFF」トグルを出すか（Gemma 4 のみ。Gemma 3n は非対応） */
    fun modelSupportsGemmaThinking(model: String): Boolean = isBuiltinGemma4Model(model)

    suspend fun updateSystemPrompt(prompt: String) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(current.copy(systemPrompt = prompt, lastModified = System.currentTimeMillis()))
    }

    suspend fun getUserName(): String {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        return current.userName
    }

    suspend fun updateUserName(name: String) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(current.copy(userName = name, lastModified = System.currentTimeMillis()))
    }

    private fun encodeBackendMap(map: Map<String, String>): String {
        val e2b = normalizeBackend(map[MODEL_E2B] ?: BACKEND_CPU)
        val e4b = normalizeBackend(map[MODEL_E4B] ?: BACKEND_CPU)
        val imported = normalizeBackend(map[MODEL_IMPORTED] ?: BACKEND_CPU)
        return "$MODEL_E2B=$e2b;$MODEL_E4B=$e4b;$MODEL_IMPORTED=$imported"
    }

    private fun parseContextWindowMap(raw: String): LinkedHashMap<String, Int> {
        val map = linkedMapOf(
            MODEL_E2B to 4096,
            MODEL_E4B to 4096,
            MODEL_IMPORTED to 4096
        )
        if (raw.trim().isEmpty()) {
            return map
        }
        raw.trim().split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val pair = entry.split('=', limit = 2)
                if (pair.size != 2) return@forEach
                val key = pair[0].trim().uppercase()
                val value = try {
                    pair[1].trim().toInt().coerceIn(
                        InferenceConfig.MIN_CONTEXT_WINDOW,
                        InferenceConfig.MAX_CONTEXT_WINDOW
                    )
                } catch (e: Exception) {
                    4096
                }
                when (key) {
                    MODEL_E2B, MODEL_E4B, MODEL_IMPORTED -> map[key] = value
                }
            }
        return map
    }

    private fun encodeContextWindowMap(map: Map<String, Int>): String {
        val e2b = map[MODEL_E2B]?.coerceIn(
            InferenceConfig.MIN_CONTEXT_WINDOW,
            InferenceConfig.MAX_CONTEXT_WINDOW
        ) ?: 4096
        val e4b = map[MODEL_E4B]?.coerceIn(
            InferenceConfig.MIN_CONTEXT_WINDOW,
            InferenceConfig.MAX_CONTEXT_WINDOW
        ) ?: 4096
        val imported = map[MODEL_IMPORTED]?.coerceIn(
            InferenceConfig.MIN_CONTEXT_WINDOW,
            InferenceConfig.MAX_CONTEXT_WINDOW
        ) ?: 4096
        return "$MODEL_E2B=$e2b;$MODEL_E4B=$e4b;$MODEL_IMPORTED=$imported"
    }

    suspend fun updateContextWindowForModel(model: String, contextWindow: Int) {
        val current = dao.getSettings() ?: SettingsEntity()
        val map = parseContextWindowMap(current.contextWindowMap).toMutableMap()
        val key = modelToBackendKey(model)
        map[key] = contextWindow.coerceIn(
            InferenceConfig.MIN_CONTEXT_WINDOW,
            InferenceConfig.MAX_CONTEXT_WINDOW
        )
        dao.update(
            current.copy(
                contextWindowMap = encodeContextWindowMap(map),
                lastModified = System.currentTimeMillis()
            )
        )
    }
}
