package com.nezumi_ai.data.repository

import android.content.Context
import com.nezumi_ai.R
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
        val downloadedIds = PresetModelCatalog.downloadedModels(context).map { it.id }.toSet()
        // 孤児プリセットの掃除: モデルが削除されたのに残っている plain プリセットを DB から削除する。
        // (shouldShowPreset は Room Flow の再発火待ちのため、モデルファイル削除だけでは
        //  プリセット一覧から消えない。ここで DB を直接更新して Flow を再発火させる)
        val allPresets = dao.getAll()
        allPresets
            .filter { it.id.startsWith(PLAIN_PRESET_ID_PREFIX) }
            .filter { it.id.removePrefix(PLAIN_PRESET_ID_PREFIX) !in downloadedIds }
            .forEach { dao.delete(it) }
        // バグ修正: モデル削除時、ユーザーが作成した（plain でない）プリセットが
        // 削除済みモデルの model_id を参照したまま残ってしまう問題への対処。
        // 以前は plain プリセットしか掃除しておらず、通常のプリセットは
        // 存在しないモデル ID を持ったまま一覧に残り続け、選択すると
        // 実在しないモデルでロードを試みて失敗していた。
        // ここでは該当プリセットを削除するのではなく model_id を未選択 ("") に
        // クリアし、ユーザーがそのプリセット向けに改めてモデルを選び直せるようにする。
        val now = System.currentTimeMillis()
        allPresets
            .filterNot { it.isLocked }
            .filter { it.modelId.isNotBlank() && it.modelId !in downloadedIds }
            .forEach { orphaned ->
                dao.update(orphaned.copy(modelId = "", updatedAt = now))
            }
        PresetModelCatalog.downloadedModels(context).forEach { model ->
            // ローカル・インポート・クラウドいずれも「システムプロンプトなし・ツールなし」の
            // ロック済み plain プリセットを用意する。
            // クラウドは isConfigured が false になると shouldShowPreset で一覧から消える。
            ensurePlainPreset(model.id, model.label)
        }
    }

    /** 指定プリセットがモデル未選択状態か（バグ修正: モデル削除で孤児化した場合を検知するため）。 */
    suspend fun isPresetModelUnselected(id: String): Boolean {
        val preset = dao.getById(id) ?: return false
        return preset.modelId.isBlank()
    }

    /**
     * モデル未選択状態のプリセットに、モデルを割り当てる。
     * ダイアログ「そのプリセットでモデルを選択してください」の確定操作から呼ばれる。
     * plain プリセットのようにロックされている場合でも model_id の更新は許可する
     * （ロックは「ユーザーによる自由編集の禁止」であり、モデル再割当はシステム側の復旧操作のため）。
     */
    suspend fun assignModelToPreset(id: String, modelId: String): Boolean {
        if (modelId.isBlank()) return false
        val preset = dao.getById(id) ?: return false
        dao.update(preset.copy(modelId = modelId, updatedAt = System.currentTimeMillis()))
        if (PreferencesHelper.getCurrentPresetId(context) == id) {
            dao.getById(id)?.let { applyPresetTools(it) }
        }
        return true
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
                description = plainDescription(),
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

    /**
     * デフォルトプリセット「ネズミAI」が指すモデルが利用可能かを返す。
     * Gemma4-2B 未ダウンロード等で利用不能な場合は false。
     * ChatFragment はこれを見て、利用不能なときだけモデル選択モーダルを出す。
     */
    suspend fun isDefaultPresetModelAvailable(): Boolean {
        val preset = dao.getById(DEFAULT_NEZUMI_AI_ID) ?: return true
        return PresetModelCatalog.isDownloaded(context, preset.modelId)
    }

    /**
     * デフォルトプリセットのモデルを、利用可能なモデルの先頭に付け替える。
     * ChatFragment のモデル選択モーダルで「自動で選ぶ」を押したときの動作。
     * @return 付け替えに成功したか (利用可能モデルが 1 つも無ければ false)。
     */
    suspend fun reassignDefaultPresetToFirstAvailableModel(): Boolean {
        val preset = dao.getById(DEFAULT_NEZUMI_AI_ID) ?: return false
        val first = PresetModelCatalog.downloadedModels(context).firstOrNull() ?: return false
        dao.update(preset.copy(modelId = first.id, updatedAt = System.currentTimeMillis()))
        if (PreferencesHelper.getCurrentPresetId(context) == DEFAULT_NEZUMI_AI_ID) {
            dao.getById(DEFAULT_NEZUMI_AI_ID)?.let { applyPresetTools(it) }
        }
        return true
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
            // Brand name always renders as "Nezumi AI" per product decision.
            name = context.getString(R.string.preset_default_nezumi_name),
            icon = "",
            // Locale-dependent system prompt (JP for ja resources, EN for en resources).
            systemPrompt = context.getString(R.string.preset_default_nezumi_system_prompt),
            modelId = "Gemma4-2B",
            // web_search は API キー未設定、flashlight はカメラ権限が必要なため、初期は無効
            enabledTools = encodeToolIds(PresetConstants.defaultInitiallyEnabledToolIds),
            createdAt = now,
            updatedAt = now,
            isDefault = true,
            memoryEnabled = true,
            description = context.getString(R.string.preset_default_nezumi_description),
            toolCallingEnabled = true
        )
    }

    private fun shouldShowPreset(preset: PresetEntity): Boolean {
        val isPlain = preset.id.startsWith(PLAIN_PRESET_ID_PREFIX)
        return !isPlain || PresetModelCatalog.isDownloaded(context, preset.modelId)
    }

    /**
     * "plain"プリセット（システムプロンプトなし・ツールなし）の説明文を現行ロケールで取得。
     * 初回作成時だけ使う。既存のプリセットはユーザーデータ保護のため上書きしない。
     */
    private fun plainDescription(): String = context.getString(R.string.preset_plain_description)

    companion object {
        const val DEFAULT_NEZUMI_AI_ID = "default_nezumi_ai"
        // 後方互換のために旧文字列も保持。新規はリソース化した preset_plain_description を使う。
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
