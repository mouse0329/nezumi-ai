package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.SettingsDao
import com.nezumi_ai.data.database.entity.SettingsEntity
import com.nezumi_ai.data.inference.InferenceConfig
import java.io.File
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val dao: SettingsDao) {
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
    
    suspend fun initializeSettingsIfNeeded() {
        if (dao.getSettings() == null) {
            dao.insert(SettingsEntity())
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
        return InferenceConfig(
            contextWindow = current.contextWindow,
            contextCompressionEnabled = current.contextCompressionEnabled,
            contextCompressionThresholdPercent = current.contextCompressionThresholdPercent,
            temperature = current.temperature,
            maxTopK = current.maxTopK,
            maxTokens = current.maxTokens,
            backendType = backendForSelected
        ).normalized()
    }

    suspend fun getInferenceConfigForModel(model: String): InferenceConfig {
        val base = getInferenceConfig()
        val backend = getBackendForModel(model)
        return base.copy(backendType = backend).normalized()
    }

    suspend fun getBackendForModel(model: String): String {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        val parsed = parseBackendMap(current.backendType)
        return parsed[modelToBackendKey(model)] ?: BACKEND_CPU
    }

    suspend fun updateInferenceConfig(
        contextCompressionEnabled: Boolean,
        contextCompressionThresholdPercent: Int,
        temperature: Float,
        maxTopK: Int,
        maxTokens: Int,
        backendType: String,
        backendTargetModel: String = MODEL_E2B
    ) {
        val current = dao.getSettings() ?: SettingsEntity()
        val backendMap = parseBackendMap(current.backendType)
        val normalizedBackend = normalizeBackend(backendType)
        val target = modelToBackendKey(backendTargetModel)
        if (target == MODEL_ALL) {
            backendMap[MODEL_E2B] = normalizedBackend
            backendMap[MODEL_E4B] = normalizedBackend
            backendMap[MODEL_IMPORTED] = normalizedBackend
        } else {
            backendMap[target] = normalizedBackend
        }
        val config = InferenceConfig(
            contextWindow = 4096,
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

    private fun encodeBackendMap(map: Map<String, String>): String {
        val e2b = normalizeBackend(map[MODEL_E2B] ?: BACKEND_CPU)
        val e4b = normalizeBackend(map[MODEL_E4B] ?: BACKEND_CPU)
        val imported = normalizeBackend(map[MODEL_IMPORTED] ?: BACKEND_CPU)
        return "$MODEL_E2B=$e2b;$MODEL_E4B=$e4b;$MODEL_IMPORTED=$imported"
    }
}
