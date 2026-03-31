package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.SettingsDao
import com.nezumi_ai.data.database.entity.SettingsEntity
import com.nezumi_ai.data.inference.InferenceConfig
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val dao: SettingsDao) {
    
    fun getSettings(): Flow<SettingsEntity?> = dao.getSettingsFlow()
    
    suspend fun updateModel(model: String) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(current.copy(selectedModel = model))
    }
    
    suspend fun updateBackend(backend: String) {
        val current = dao.getSettings() ?: SettingsEntity()
        dao.update(current.copy(backendType = backend))
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

    suspend fun getInferenceConfig(): InferenceConfig {
        val current = dao.getSettings() ?: SettingsEntity().also { dao.insert(it) }
        return InferenceConfig(
            contextWindow = current.contextWindow,
            contextCompressionEnabled = current.contextCompressionEnabled,
            contextCompressionThresholdPercent = current.contextCompressionThresholdPercent,
            temperature = current.temperature,
            maxTopK = current.maxTopK,
            maxTokens = current.maxTokens,
            backendType = current.backendType
        ).normalized()
    }

    suspend fun updateInferenceConfig(
        contextCompressionEnabled: Boolean,
        contextCompressionThresholdPercent: Int,
        temperature: Float,
        maxTopK: Int,
        maxTokens: Int,
        backendType: String
    ) {
        val current = dao.getSettings() ?: SettingsEntity()
        val config = InferenceConfig(
            contextWindow = 4096,
            contextCompressionEnabled = contextCompressionEnabled,
            contextCompressionThresholdPercent = contextCompressionThresholdPercent,
            temperature = temperature,
            maxTopK = maxTopK,
            maxTokens = maxTokens,
            backendType = backendType
        ).normalized()
        dao.update(
            current.copy(
                contextWindow = config.contextWindow,
                contextCompressionEnabled = config.contextCompressionEnabled,
                contextCompressionThresholdPercent = config.contextCompressionThresholdPercent,
                temperature = config.temperature,
                maxTopK = config.maxTopK,
                maxTokens = config.maxTokens,
                backendType = config.backendType,
                lastModified = System.currentTimeMillis()
            )
        )
    }
}
