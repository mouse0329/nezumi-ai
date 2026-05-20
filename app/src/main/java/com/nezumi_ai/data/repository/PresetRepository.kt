package com.nezumi_ai.data.repository

import android.content.Context
import com.nezumi_ai.data.database.dao.PresetDao
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.inference.ToolPreferences
import com.nezumi_ai.data.preset.PresetConstants
import com.nezumi_ai.data.preset.PresetModelCatalog
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PresetRepository(
    private val dao: PresetDao,
    private val context: Context
) {
    fun observePresets(): Flow<List<PresetEntity>> =
        dao.observeAll().map { presets -> presets.filter(::shouldShowPreset) }

    suspend fun getPresets(): List<PresetEntity> = dao.getAll().filter(::shouldShowPreset)

    suspend fun getPreset(id: String): PresetEntity? = dao.getById(id)

    suspend fun createPreset(preset: PresetEntity) {
        dao.insert(preset.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePreset(preset: PresetEntity): Boolean {
        val current = dao.getById(preset.id) ?: return false
        if (current.isLocked) return false
        dao.update(preset.copy(isLocked = current.isLocked, updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun deletePreset(id: String): Boolean {
        val preset = dao.getById(id) ?: return false
        if (preset.isDefault || preset.isLocked) return false
        dao.delete(preset)
        if (PreferencesHelper.getCurrentPresetId(context) == id) {
            val fallback = dao.getDefault() ?: dao.getAll().firstOrNull(::shouldShowPreset)
            PreferencesHelper.setCurrentPresetId(context, fallback?.id.orEmpty())
            if (fallback != null) {
                ToolPreferences(context).setActivePresetToolIds(fallback.enabledTools)
            }
        }
        return true
    }

    suspend fun selectPreset(id: String): PresetEntity? {
        val preset = dao.getById(id) ?: return null
        PreferencesHelper.setCurrentPresetId(context, preset.id)
        ToolPreferences(context).setActivePresetToolIds(preset.enabledTools)
        return preset
    }

    suspend fun getCurrentPreset(): PresetEntity? {
        val storedId = PreferencesHelper.getCurrentPresetId(context)
        return storedId.takeIf { it.isNotBlank() }?.let { dao.getById(it) }?.takeIf(::shouldShowPreset)
            ?: dao.getDefault()
            ?: dao.getAll().firstOrNull(::shouldShowPreset)
    }

    suspend fun initializeDefaultsIfNeeded() {
        if (dao.count() > 0) {
            ensureNezumiAiDefaultExists()
            ensurePlainPresetsForDownloadedModels()
            deleteLegacyGeneratedDefaults()
            ensureCurrentPresetSelected()
            return
        }

        val defaults = listOf(createNezumiAiDefault())
        dao.insertIgnore(defaults)
        ensurePlainPresetsForDownloadedModels()
        PreferencesHelper.setCurrentPresetId(context, DEFAULT_NEZUMI_AI_ID)
        ToolPreferences(context).setActivePresetToolIds(defaults.first().enabledTools)
    }

    suspend fun ensurePlainPresetsForDownloadedModels() {
        PresetModelCatalog.downloadedModels(context).forEach { model ->
            ensurePlainPreset(model.id, model.label)
        }
    }

    private suspend fun ensurePlainPreset(modelId: String, displayName: String) {
        val id = plainPresetId(modelId)
        if (dao.getById(id) != null) return
        val now = System.currentTimeMillis()
        dao.insert(
            PresetEntity(
                id = id,
                name = displayName,
                icon = "🔒",
                modelId = modelId,
                enabledTools = "[]",
                createdAt = now,
                updatedAt = now,
                memoryEnabled = false,
                description = PLAIN_PRESET_DESCRIPTION,
                isLocked = true
            )
        )
    }

    private suspend fun ensureCurrentPresetSelected() {
        val stored = PreferencesHelper.getCurrentPresetId(context)
        if (stored.isNotBlank()) {
            val storedPreset = dao.getById(stored)
            if (storedPreset != null) {
                ToolPreferences(context).setActivePresetToolIds(storedPreset.enabledTools)
                return
            }
        }
        val preset = dao.getDefault() ?: dao.getAll().firstOrNull(::shouldShowPreset)
        PreferencesHelper.setCurrentPresetId(context, preset?.id.orEmpty())
        if (preset != null) {
            ToolPreferences(context).setActivePresetToolIds(preset.enabledTools)
        }
    }

    private suspend fun ensureNezumiAiDefaultExists() {
        if (dao.getById(DEFAULT_NEZUMI_AI_ID) != null) return
        dao.insert(createNezumiAiDefault())
    }

    private suspend fun deleteLegacyGeneratedDefaults() {
        dao.deleteByIds(LEGACY_GENERATED_DEFAULT_IDS)
    }

    private fun createNezumiAiDefault(): PresetEntity {
        val now = System.currentTimeMillis()
        return PresetEntity(
            id = DEFAULT_NEZUMI_AI_ID,
            name = "ネズミAI",
            icon = "🐭",
            systemPrompt = "あなたはネズミAIです。親しみやすく、簡潔で、ユーザーの意図に寄り添って日本語で応答してください。",
            modelId = "Gemma4-2B",
            enabledTools = encodeToolIds(PresetConstants.allToolIds),
            createdAt = now,
            updatedAt = now,
            isDefault = true,
            memoryEnabled = true,
            description = "自由に使えるデフォルトプリセット"
        )
    }

    private fun shouldShowPreset(preset: PresetEntity): Boolean {
        val isPlain = preset.id.startsWith(PLAIN_PRESET_ID_PREFIX)
        return !isPlain || PresetModelCatalog.isDownloaded(context, preset.modelId)
    }

    companion object {
        const val DEFAULT_NEZUMI_AI_ID = "default_nezumi_ai"
        const val PLAIN_PRESET_DESCRIPTION = "システムプロンプトなし・ツールなしの素の状態"
        private const val PLAIN_PRESET_ID_PREFIX = "plain_"
        private val LEGACY_GENERATED_DEFAULT_IDS = listOf(
            "default_nezumimaru",
            "default_offline",
            "default_high_performance"
        )

        fun plainPresetId(modelId: String): String = "$PLAIN_PRESET_ID_PREFIX$modelId"

        fun encodeToolIds(toolIds: List<String>): String =
            toolIds.joinToString(prefix = "[", postfix = "]") { "\"${it.replace("\"", "")}\"" }
    }
}
