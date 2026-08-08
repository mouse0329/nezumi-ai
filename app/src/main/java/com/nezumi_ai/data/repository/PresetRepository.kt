package com.nezumi_ai.data.repository

import android.content.Context
import com.nezumi_ai.data.database.dao.PresetDao
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.inference.ToolPreferences
import com.nezumi_ai.data.mcp.McpPreferences
import com.nezumi_ai.data.mcp.McpToolRegistry
import com.nezumi_ai.data.preset.PresetConstants
import com.nezumi_ai.data.preset.PresetModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.nezumi_ai.utils.ImportedModelCapabilityStore
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

    /**
     * プリセット仕分け・並び替え用の追加 API。
     *
     * - [reorder]は UI 上でドラッグ&ドロップによって得た並び順を、
     *   sort_order を一括更新して永続化する。使われないプリセットはそのまま。
     * - [setTags] はジャンル・タグを保存するための API。
     */
    suspend fun reorder(orderedIds: List<String>) {
        val now = System.currentTimeMillis()
        // 連番で sort_order を振る（ステップ 1）。難しい計算は不要。
        orderedIds.forEachIndexed { index, id ->
            dao.updateSortOrder(id = id, sortOrder = (index + 1).toLong(), updatedAt = now)
        }
    }

    suspend fun setTags(id: String, tags: List<String>) {
        val csv = tags.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        dao.updateTagsCsv(id = id, tagsCsv = csv, updatedAt = System.currentTimeMillis())
    }

    /**
     * 名前検索・タグ検索を LIKE クエリで行う。 query が空のときは observePresets() と同じ振る舞い。
     */
    fun searchPresets(query: String): Flow<List<PresetEntity>> {
        val q = query.trim()
        return if (q.isEmpty()) {
            observePresets()
        } else {
            val pattern = "%${q.replace("%", "\\%").replace("_", "\\_")}%"
            dao.searchByNameOrTag(pattern).map { presets -> presets.filter(::shouldShowPreset) }
        }
    }

    suspend fun createPreset(preset: PresetEntity) {
        val maxOrder = dao.getAll().maxOfOrNull { it.sortOrder.takeIf { o -> o != Long.MAX_VALUE } ?: 0L } ?: 0L
        dao.insert(preset.copy(sortOrder = maxOrder + 1, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePreset(preset: PresetEntity): Boolean {
        val current = dao.getById(preset.id) ?: return false
        if (current.isLocked) return false
        // バグ修正: 編集保存時に UI 層が sortOrder を引き継がないケースでも
        // 既存の並び順を壊さないように、DB 側の値を優先して保持する。
        // (UI から明示的に sort_order を更新したい場合は reorder() を利用する)
        dao.update(
            preset.copy(
                isLocked = current.isLocked,
                sortOrder = current.sortOrder,
                updatedAt = System.currentTimeMillis()
            )
        )
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
                applyPresetTools(fallback)
            }
        }
        return true
    }

    suspend fun selectPreset(id: String): PresetEntity? {
        val preset = dao.getById(id) ?: return null
        PreferencesHelper.setCurrentPresetId(context, preset.id)
        applyPresetTools(preset)
        return preset
    }

    private fun applyPresetTools(preset: PresetEntity) {
        val toolCallingOn = isPresetToolCallingEnabled(preset)
        val tools = if (toolCallingOn) preset.enabledTools else "[]"
        val prefs = ToolPreferences(context)
        prefs.setActivePresetToolIds(tools)

        val mcpIds = if (toolCallingOn) McpPreferences.decodeServerIds(preset.mcpServerIds) else emptySet()
        prefs.setActiveMcpServerIds(mcpIds)
        // バックグラウンドで MCP ツール一覧をリフレッシュ（ネットワーク待ちになるため UI をブロックしない）
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            McpToolRegistry.get(context).refresh(mcpIds, force = true)
        }
    }

    /**
     * プリセット保存直後など、MCP サーバーの検索完了を待ってから次の推論を回したい
     * シーン向けの suspend 版。UI コルーチンから呼べば、即時にツール一覧を反映できる。
     */
    suspend fun applyActivePresetToolsSync(): Boolean {
        val currentId = PreferencesHelper.getCurrentPresetId(context)
        val preset = if (currentId.isNotBlank()) dao.getById(currentId) else null
        val toolCallingOn = preset?.let(::isPresetToolCallingEnabled) ?: false
        val prefs = ToolPreferences(context)
        prefs.setActivePresetToolIds(if (toolCallingOn) preset!!.enabledTools else "[]")
        val mcpIds = if (toolCallingOn) McpPreferences.decodeServerIds(preset!!.mcpServerIds) else emptySet()
        prefs.setActiveMcpServerIds(mcpIds)
        McpToolRegistry.get(context).refresh(mcpIds, force = true)
        return true
    }

    private fun isPresetToolCallingEnabled(preset: PresetEntity): Boolean {
        if (!preset.toolCallingEnabled) return false
        return isModelToolCallingEnabled(preset.modelId)
    }

    private fun isModelToolCallingEnabled(modelId: String): Boolean {
        val isImportedModel = modelId.contains('/') || modelId.contains('\\')
        if (!isImportedModel) return true
        return ImportedModelCapabilityStore.get(context, modelId).toolCallingEnabled
    }

    suspend fun countPresetsUsingModelWithToolCallingEnabled(modelId: String): Int {
        return dao.getAll().count { it.modelId == modelId && it.toolCallingEnabled }
    }

    suspend fun disableToolCallingForPresetsUsingModel(modelId: String): Int {
        val presets = dao.getAll().filter { it.modelId == modelId && it.toolCallingEnabled }
        if (presets.isEmpty()) return 0
        val now = System.currentTimeMillis()
        presets.forEach { preset ->
            dao.update(preset.copy(toolCallingEnabled = false, updatedAt = now))
        }
        val currentPresetId = PreferencesHelper.getCurrentPresetId(context)
        if (currentPresetId.isNotBlank() && presets.any { it.id == currentPresetId }) {
            dao.getById(currentPresetId)?.let { applyPresetTools(it) }
        }
        return presets.size
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
            updateNezumiAiDefaultTools()  // 既存のデフォルトプリセットのツールを更新
            ensurePlainPresetsForDownloadedModels()
            deleteLegacyGeneratedDefaults()
            ensureCurrentPresetSelected()
            return
        }

        val defaults = listOf(createNezumiAiDefault())
        dao.insertIgnore(defaults)
        ensurePlainPresetsForDownloadedModels()
        PreferencesHelper.setCurrentPresetId(context, DEFAULT_NEZUMI_AI_ID)
        applyPresetTools(defaults.first())
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
 icon = "",
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
                applyPresetTools(storedPreset)
                return
            }
        }
        val preset = dao.getDefault() ?: dao.getAll().firstOrNull(::shouldShowPreset)
        PreferencesHelper.setCurrentPresetId(context, preset?.id.orEmpty())
        if (preset != null) {
            applyPresetTools(preset)
        }
    }

    private suspend fun ensureNezumiAiDefaultExists() {
        if (dao.getById(DEFAULT_NEZUMI_AI_ID) != null) return
        dao.insert(createNezumiAiDefault())
    }

    private suspend fun updateNezumiAiDefaultTools() {
        val existing = dao.getById(DEFAULT_NEZUMI_AI_ID) ?: return
        // バグ修正 (プリセットを保存してもアプリを再起動すると戻る問題):
        //   旧実装は起動のたびに `defaultInitiallyEnabledToolIds` (現在は空リスト) +
        //   WEB_SEARCH/FLASHLIGHT だけの狭い集合を再構築して DB を上書きしていた。
        //   このためユーザーが GET_TIME / GET_BATTERY / MEMORY / IMAGE_GENERATION
        //   などにチェックを入れて保存しても、次回起動で強制的に外されて
        //   しまうというリグレッションが発生していた。
        //
        //   修正方針:
        //     (a) 既存の enabledTools JSON が正常にパースできる場合は、
        //         ユーザーの選択を一切上書きしない。
        //     (b) パースに失敗したケース(旧バージョンからの壊れデータなど)
        //         だけ、`defaultInitiallyEnabledToolIds` で初期化し直す。
        //     (c) 新ツールが PresetConstants に追加されても自動で現在の
        //         プリセットには入れない。ユーザーが明示的に選択する仕様に揃える。
        val parsed = runCatching {
            val arr = org.json.JSONArray(existing.enabledTools)
            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
        }
        if (parsed.isSuccess) {
            // パース成功 = ユーザーの選択は保存されているとみなし、一切変更しない。
            // (新規デフォルトツールの追加は createNezumiAiDefault 側で行う)
            return
        }
        // パース失敗 (壊れている or 形式変更) のときだけリセットする。
        val fallback = encodeToolIds(PresetConstants.defaultInitiallyEnabledToolIds)
        if (existing.enabledTools != fallback) {
            dao.update(existing.copy(enabledTools = fallback, updatedAt = System.currentTimeMillis()))
            if (PreferencesHelper.getCurrentPresetId(context) == DEFAULT_NEZUMI_AI_ID) {
                applyPresetTools(existing.copy(enabledTools = fallback))
            }
        }
    }

    private suspend fun deleteLegacyGeneratedDefaults() {
        dao.deleteByIds(LEGACY_GENERATED_DEFAULT_IDS)
    }

    private fun createNezumiAiDefault(): PresetEntity {
        val now = System.currentTimeMillis()
        return PresetEntity(
            id = DEFAULT_NEZUMI_AI_ID,
            name = "ネズミAI",
 icon = "",
            systemPrompt = "あなたはネズミAIです。親しみやすく、簡潔で、ユーザーの意図に寄り添って日本語で応答してください。",
            modelId = "Gemma4-2B",
            // web_search は API キー未設定、flashlight はカメラ権限が必要なため、初期は無効
            enabledTools = encodeToolIds(PresetConstants.defaultInitiallyEnabledToolIds),
            createdAt = now,
            updatedAt = now,
            isDefault = true,
            memoryEnabled = true,
            description = "自由に使えるデフォルトプリセット",
            toolCallingEnabled = true
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
