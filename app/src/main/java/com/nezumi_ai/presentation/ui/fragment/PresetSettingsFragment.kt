package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.mcp.McpPreferences
import com.nezumi_ai.presentation.ui.component.McpServerManagerDialog
import com.nezumi_ai.presentation.viewmodel.ChatViewModel
import com.nezumi_ai.data.skill.SkillRepository
import com.nezumi_ai.data.preset.PresetConstants
import com.nezumi_ai.data.preset.PresetModelCatalog
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.utils.PreferencesHelper
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpFontFamily
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpTypography

class PresetSettingsFragment : Fragment() {
    private lateinit var presetRepository: PresetRepository
    private var presetLoadStarted = false

    /**
     * プリセットを閉じた時点でチャットのモデルロードを始める。
     * ChatFragment.onResume 待ちにすると、GGUF はテンプレート走査が先に走り
     * llama.cpp のロードが始まらない。
     */
    private fun startPresetModelLoad() {
        if (presetLoadStarted || !isAdded) return
        presetLoadStarted = true
        val vm = runCatching {
            ViewModelProvider(requireActivity()).get(ChatViewModel::class.java)
        }.getOrNull() ?: return
        vm.preloadActivePresetModel()
    }

    override fun onPause() {
        // システム戻るでも onBack と同じくロードを始める。
        startPresetModelLoad()
        super.onPause()
    }

    // フラッシュライトツールは Android 上では CAMERA 権限が必要なため、
    // チェックボックスで有効化したときに権限をリクエストする。
    // 実際の ON/OFF の後処理は Compose 側のコールバックに任せる。
    private var pendingFlashlightGrant: ((Boolean) -> Unit)? = null
    private val requestCameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = pendingFlashlightGrant
        pendingFlashlightGrant = null
        if (!granted) {
            Toast.makeText(
                requireContext(),
                getString(R.string.preset_flashlight_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
        cb?.invoke(granted)
    }

    /**
     * フラッシュライトを有効化しようとしたときにカメラ権限を保証する。
     * すでに許可済みなら即座に true でコールバックする。
     * 未許可ならシステムダイアログを出し、結果をコールバックで返す。
     */
    private fun ensureCameraPermissionForFlashlight(onResult: (Boolean) -> Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            onResult(true)
            return
        }
        pendingFlashlightGrant = onResult
        requestCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        presetRepository = PresetRepository(db.presetDao(), requireContext().applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // listFiles / 検証を含むため IO へ。メインスレッドで走らせるとプリセット画面復帰が重い。
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            presetRepository.ensurePlainPresetsForDownloadedModels()
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                PresetScreen()
            }
        }
    }

    @Composable
    private fun PresetScreen() {
        val scope = rememberCoroutineScope()
        val ctx = requireContext()
        val presetsFlow = remember { presetRepository.observePresets() }
        val presets by presetsFlow.collectAsState(initial = emptyList())
        var detached by remember { mutableStateOf(PreferencesHelper.isPresetDetached(ctx)) }
        var currentPresetId by remember {
            mutableStateOf(PreferencesHelper.getCurrentPresetId(ctx).takeIf { it.isNotBlank() && !detached })
        }
        var overrideModelId by remember { mutableStateOf(PreferencesHelper.getPresetModelOverride(ctx)) }
        var tab by remember { mutableStateOf(PresetPickerTab.PRESET) }
        var query by remember { mutableStateOf("") }
        var pins by remember { mutableStateOf(PreferencesHelper.getPresetPins(ctx).toSet()) }
        var expandedIds by remember { mutableStateOf(setOf<String>()) }
        var pendingDelete by remember { mutableStateOf<PresetEntity?>(null) }
        val appCtx = ctx.applicationContext
        val downloadedModelOptions by produceState(
            initialValue = emptyList<com.nezumi_ai.data.preset.PresetModelOption>(),
            presets
        ) {
            value = withContext(Dispatchers.IO) {
                com.nezumi_ai.data.preset.PresetModelCatalog.downloadedModels(appCtx)
            }
        }
        val fallbackModelId by produceState(initialValue = "") {
            value = withContext(Dispatchers.IO) {
                com.nezumi_ai.data.repository.SettingsRepository
                    .fromDatabase(NezumiAiDatabase.getInstance(appCtx))
                    .getSelectedModel()
            }
        }
        val currentPreset = presets.firstOrNull { it.id == currentPresetId }
        val mcpPrefs = remember { McpPreferences.get(requireContext()) }
        val mcpServers by mcpPrefs.servers.collectAsState()
        val mcpNameById = remember(mcpServers) { mcpServers.associate { it.id to it.name } }
        var editingPreset by remember { mutableStateOf<PresetEntity?>(null) }
        var showCreateDialog by remember { mutableStateOf(false) }

        fun persistPins(next: Set<String>) {
            pins = next
            PreferencesHelper.setPresetPins(appCtx, next)
        }

        fun rememberModel(modelId: String) {
            if (modelId.isBlank()) return
            scope.launch(Dispatchers.IO) {
                com.nezumi_ai.data.repository.SettingsRepository
                    .fromDatabase(NezumiAiDatabase.getInstance(appCtx))
                    .updateModel(modelId)
            }
        }

        if (showCreateDialog) {
            PresetEditDialog(
                initialPreset = null,
                onDismiss = { showCreateDialog = false },
                onSave = { preset ->
                    scope.launch {
                        presetRepository.createPreset(preset)
                        showCreateDialog = false
                        toast(getString(R.string.preset_toast_created))
                    }
                }
            )
        }

        editingPreset?.let { preset ->
            PresetEditDialog(
                initialPreset = preset,
                onDismiss = { editingPreset = null },
                onSave = { updated ->
                    scope.launch {
                        if (presetRepository.updatePreset(updated)) {
                            editingPreset = null
                            toast(getString(R.string.preset_toast_saved))
                            val currentId = PreferencesHelper.getCurrentPresetId(requireContext())
                            if (currentId == updated.id) {
                                presetRepository.applyActivePresetToolsSync()
                            }
                        } else {
                            toast(getString(R.string.preset_toast_locked_edit))
                        }
                    }
                }
            )
        }

        pendingDelete?.let { preset ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.preset_delete_confirm_title)) },
                text = { Text(stringResource(R.string.preset_delete_confirm_body, preset.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        scope.launch {
                            if (presetRepository.deletePreset(preset.id)) {
                                if (currentPresetId == preset.id) {
                                    detached = true
                                    currentPresetId = null
                                    overrideModelId = PreferencesHelper.getPresetModelOverride(appCtx)
                                }
                                toast(getString(R.string.preset_toast_deleted))
                            } else {
                                toast(getString(R.string.preset_toast_cannot_delete))
                            }
                        }
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.preset_cancel))
                    }
                }
            )
        }

        PresetPickerContent(
            tab = tab,
            query = query,
            presets = presets,
            models = downloadedModelOptions,
            currentPreset = currentPreset,
            detached = detached,
            overrideModelId = overrideModelId,
            fallbackModelId = fallbackModelId,
            pins = pins,
            expandedIds = expandedIds,
            modelLabel = { modelLabel(it, downloadedModelOptions) },
            toolLabel = { id -> toolOptions.firstOrNull { it.id == id }?.label ?: id },
            enabledToolIds = { preset ->
                if (!preset.toolCallingEnabled) emptyList() else parseToolIds(preset.enabledTools).toList()
            },
            mcpNames = { preset ->
                McpPreferences.decodeServerIds(preset.mcpServerIds).map { id ->
                    mcpNameById[id]?.takeIf { it.isNotBlank() } ?: id
                }
            },
            onTab = { next ->
                tab = next
                query = ""
            },
            onQuery = { query = it },
            onBack = {
                startPresetModelLoad()
                findNavController().navigateUp()
            },
            onCreate = { showCreateDialog = true },
            onTogglePin = { key ->
                persistPins(if (key in pins) pins - key else pins + key)
            },
            onToggleExpanded = { id ->
                expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
            },
            onSelectPreset = { preset ->
                scope.launch {
                    presetRepository.selectPreset(preset.id)
                    detached = false
                    currentPresetId = preset.id
                    overrideModelId = ""
                    toast(getString(R.string.preset_toast_selected, preset.name))
                }
            },
            onSelectNone = {
                val keep = overrideModelId.ifBlank {
                    currentPreset?.modelId?.takeIf { it.isNotBlank() } ?: fallbackModelId
                }
                scope.launch {
                    presetRepository.clearCurrentPreset(keep)
                    detached = true
                    currentPresetId = null
                    overrideModelId = keep
                    tab = PresetPickerTab.MODEL
                    query = ""
                    rememberModel(keep)
                    toast(getString(R.string.preset_toast_none_pick_model))
                }
            },
            onClearPreset = {
                val keep = overrideModelId.ifBlank {
                    currentPreset?.modelId?.takeIf { it.isNotBlank() } ?: fallbackModelId
                }
                scope.launch {
                    presetRepository.clearCurrentPreset(keep)
                    detached = true
                    currentPresetId = null
                    overrideModelId = keep
                    rememberModel(keep)
                    toast(getString(R.string.preset_toast_cleared))
                }
            },
            onResetOverride = {
                scope.launch {
                    presetRepository.setModelOverride("")
                    overrideModelId = ""
                    toast(getString(R.string.preset_toast_model_reset))
                }
            },
            onSelectModel = { modelId ->
                val preset = if (detached) null else currentPreset
                scope.launch {
                    if (preset == null) {
                        presetRepository.setModelOverride(modelId)
                        overrideModelId = modelId
                        rememberModel(modelId)
                        toast(getString(R.string.preset_toast_standalone))
                    } else if (modelId == preset.modelId) {
                        presetRepository.setModelOverride("")
                        overrideModelId = ""
                        toast(getString(R.string.preset_toast_model_reset))
                    } else {
                        presetRepository.setModelOverride(modelId)
                        overrideModelId = modelId
                        rememberModel(modelId)
                        toast(getString(R.string.preset_toast_model_override))
                    }
                }
            },
            onEdit = { preset ->
                if (preset.isLocked) toast(getString(R.string.preset_toast_locked_edit))
                else editingPreset = preset
            },
            onDelete = { pendingDelete = it }
        )
    }

    @Composable
    private fun PresetCheckRow(
        title: String,
        subtitle: String?,
        checked: Boolean,
        onToggle: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            if (subtitle == null) {
                Text(title)
            } else {
                Column {
                    Text(title)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun PresetEditDialog(
        initialPreset: PresetEntity?,
        onDismiss: () -> Unit,
        onSave: (PresetEntity) -> Unit
    ) {
        var name by remember { mutableStateOf(initialPreset?.name ?: "") }
 var icon by remember { mutableStateOf(initialPreset?.icon ?: "") }
        var description by remember { mutableStateOf(initialPreset?.description ?: "") }
        var systemPrompt by remember { mutableStateOf(initialPreset?.systemPrompt ?: "") }
        val appCtx = requireContext().applicationContext
        val availableModels by produceState(
            initialValue = emptyList<com.nezumi_ai.data.preset.PresetModelOption>()
        ) {
            value = withContext(Dispatchers.IO) {
                PresetModelCatalog.downloadedModels(appCtx)
            }
        }
        var modelId by remember {
            mutableStateOf(initialPreset?.modelId.orEmpty())
        }
        // availableModels は IO で非同期取得するため、初回 composition 時点では空。
        // ロード完了後に、未設定 or 選択肢に無い modelId を利用可能モデルへ補正する。
        LaunchedEffect(availableModels) {
            if (availableModels.isEmpty()) return@LaunchedEffect
            val current = modelId
            val valid = current.isNotBlank() && availableModels.any { it.id == current }
            if (!valid) {
                modelId = initialPreset?.modelId?.takeIf { id -> availableModels.any { it.id == id } }
                    ?: availableModels.firstOrNull()?.id
                    ?: ""
            }
        }
        var memoryEnabled by remember { mutableStateOf(initialPreset?.memoryEnabled ?: true) }
        var toolCallingEnabled by remember {
            mutableStateOf(initialPreset?.toolCallingEnabled ?: false)
        }
        var skillsEnabled by remember { mutableStateOf(initialPreset?.skillsEnabled ?: false) }
        var hiddenSkillNames by remember { mutableStateOf(parseToolIds(initialPreset?.hiddenSkillNames ?: "[]")) }
        val installedSkills by produceState(initialValue = emptyList<com.nezumi_ai.data.skill.Skill>()) {
            value = withContext(Dispatchers.IO) { SkillRepository(appCtx).scan().skills }
        }
        // 新規プリセット作成時はすべてのツールをチェックを外した状態（空集合）で始める。
        // 以前は PresetConstants.allToolIds を初期値にしていたため、ツールコールを ON にすると
        // 全ツールが自動で有効化されてしまう仕様だったが、
        // ここではユーザーが明示的に選ぶ仕様に変更する。
        // 既存プリセットの編集時は保存された選択を尊重する。
        var enabledTools by remember {
            mutableStateOf(
                if (initialPreset != null) {
                    parseToolIds(initialPreset.enabledTools)
                } else {
                    emptySet()
                }
            )
        }
        val mcpPrefs = remember { McpPreferences.get(requireContext()) }
        val mcpServers by mcpPrefs.servers.collectAsState()
        var selectedMcpServerIds by remember {
            mutableStateOf(McpPreferences.decodeServerIds(initialPreset?.mcpServerIds))
        }
        var showMcpManager by remember { mutableStateOf(false) }

        // ツール行のトグル処理。enabledTools を呼び出し時に読む (State 経由) ため remember で安定化でき、
        // トグルのたびに全行のラムダが作り直されて全行が再コンポーズされるのを防ぐ。
        val onToolToggle: (String) -> Unit = remember {
            { toolId: String ->
                val willEnable = toolId !in enabledTools
                // フラッシュライトを有効化するときはカメラ権限を先に確保する。
                if (toolId == PresetConstants.TOOL_FLASHLIGHT && willEnable) {
                    ensureCameraPermissionForFlashlight { granted ->
                        if (granted) {
                            enabledTools = toggleTool(enabledTools, toolId)
                        }
                    }
                } else {
                    enabledTools = toggleTool(enabledTools, toolId)
                }
            }
        }

        val selectedModelToolCallingAllowed by remember(modelId) {
            derivedStateOf {
                val isImportedModel = modelId.contains('/') || modelId.contains('\\')
                if (!isImportedModel) return@derivedStateOf true
                ImportedModelCapabilityStore.get(requireContext(), modelId).toolCallingEnabled
            }
        }

        LaunchedEffect(selectedModelToolCallingAllowed) {
            if (!selectedModelToolCallingAllowed) {
                toolCallingEnabled = false
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (initialPreset == null) stringResource(id = R.string.preset_screen_new_title) else stringResource(id = R.string.preset_screen_edit_title)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = icon,
                                onValueChange = { icon = it.take(4) },
                                label = { Text(stringResource(id = R.string.preset_field_icon)) },
                                modifier = Modifier.weight(0.35f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text(stringResource(id = R.string.preset_field_name)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(id = R.string.preset_field_description)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            label = { Text(stringResource(id = R.string.preset_field_system_prompt)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            minLines = 4
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(id = R.string.preset_edit_model_label), fontWeight = FontWeight.Bold)
                            if (availableModels.isEmpty()) {
                                Text(
                                    text = stringResource(id = R.string.preset_edit_no_downloaded_models),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                availableModels.forEach { option ->
                                    FilterChip(
                                        selected = modelId == option.id,
                                        onClick = { modelId = option.id },
                                        label = { Text(option.label) }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Divider()
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(id = R.string.preset_edit_memory_label), fontWeight = FontWeight.Bold)
                            Switch(
                                checked = memoryEnabled,
                                onCheckedChange = { memoryEnabled = it },
                                colors = nezumiSwitchColors()
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(id = R.string.preset_edit_tool_calling_label), fontWeight = FontWeight.Bold)
                                Text(
                                    text = stringResource(id = R.string.preset_edit_tool_calling_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!selectedModelToolCallingAllowed) {
                                    Text(
                                        text = stringResource(id = R.string.preset_edit_tool_calling_disabled_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Switch(
                                checked = toolCallingEnabled,
                                onCheckedChange = { if (selectedModelToolCallingAllowed) toolCallingEnabled = it },
                                enabled = selectedModelToolCallingAllowed,
                                colors = nezumiSwitchColors()
                            )
                        }
                    }
                    if (toolCallingEnabled) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.preset_edit_skills_label), fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.preset_edit_skills_desc), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = skillsEnabled, onCheckedChange = { skillsEnabled = it }, colors = nezumiSwitchColors())
                            }
                        }
                        if (skillsEnabled) item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                installedSkills.forEach { skill ->
                                    PresetCheckRow(
                                        title = skill.name,
                                        subtitle = skill.description,
                                        checked = skill.name !in hiddenSkillNames,
                                        onToggle = {
                                            hiddenSkillNames = toggleId(hiddenSkillNames, skill.name)
                                        }
                                    )
                                }
                                if (installedSkills.isEmpty()) Text(stringResource(R.string.preset_edit_skills_empty), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(id = R.string.preset_edit_tools_label), fontWeight = FontWeight.Bold)
                                toolOptions.forEach { option ->
                                    PresetCheckRow(
                                        title = option.label,
                                        subtitle = null,
                                        checked = option.id in enabledTools,
                                        onToggle = { onToolToggle(option.id) }
                                    )
                                }
                                Divider(modifier = Modifier.padding(vertical = 4.dp))
                                // MCP: プリセットのツール一覧の直下に配置
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(id = R.string.preset_edit_mcp_server_label), fontWeight = FontWeight.Bold)
                                        val subLabel = if (mcpServers.isEmpty()) {
                                            stringResource(id = R.string.preset_edit_mcp_servers_unregistered)
                                        } else {
                                            val active = mcpServers.count { it.id in selectedMcpServerIds }
                                            stringResource(id = R.string.preset_edit_mcp_servers_enabled_format, active, mcpServers.size)
                                        }
                                        Text(
                                            text = subLabel,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    TextButton(onClick = { showMcpManager = true }) {
                                        Text(stringResource(id = R.string.preset_edit_mcp_add))
                                    }
                                }
                                if (mcpServers.isNotEmpty()) {
                                    mcpServers.forEach { server ->
                                        PresetCheckRow(
                                            title = server.name,
                                            subtitle = "${server.transport.label} • ${server.url}",
                                            checked = server.id in selectedMcpServerIds,
                                            onToggle = {
                                                selectedMcpServerIds = toggleId(selectedMcpServerIds, server.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedName = name.trim()
                        if (trimmedName.isBlank()) {
                            toast(getString(R.string.preset_toast_name_required))
                            return@Button
                        }
                        if (modelId.isBlank()) {
                            toast(getString(R.string.preset_toast_no_downloaded_models))
                            return@Button
                        }
                        val now = System.currentTimeMillis()
                        onSave(
                            PresetEntity(
                                id = initialPreset?.id ?: UUID.randomUUID().toString(),
                                name = trimmedName,
 icon = icon.ifBlank { ""},
                                description = description.trim(),
                                systemPrompt = systemPrompt.trim(),
                                modelId = modelId,
                                enabledTools = PresetRepository.encodeToolIds(enabledTools.toList()),
                                createdAt = initialPreset?.createdAt ?: now,
                                updatedAt = now,
                                isDefault = initialPreset?.isDefault ?: false,
                                memoryEnabled = memoryEnabled,
                                isLocked = initialPreset?.isLocked ?: false,
                                toolCallingEnabled = toolCallingEnabled,
                                // バグ修正: 編集保存時に sortOrder / tagsCsv を引き継がないと
                                // 既定値（Long.MAX_VALUE / 空）に戻され、リストの一番下に飛ばされてしまう。
                                sortOrder = initialPreset?.sortOrder ?: Long.MAX_VALUE,
                                tagsCsv = initialPreset?.tagsCsv ?: "",
                                mcpServerIds = McpPreferences.encodeServerIds(selectedMcpServerIds),
                                skillsEnabled = skillsEnabled,
                                hiddenSkillNames = PresetRepository.encodeToolIds(hiddenSkillNames.toList())
                            )
                        )
                    }
                ) {
                    Text(stringResource(id = R.string.preset_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.preset_cancel))
                }
            }
        )

        if (showMcpManager) {
            McpServerManagerDialog(
                servers = mcpServers,
                selectedIds = selectedMcpServerIds,
                onSelectionChange = { selectedMcpServerIds = it },
                onUpsert = { mcpPrefs.upsert(it) },
                onDelete = {
                    mcpPrefs.remove(it)
                    selectedMcpServerIds = selectedMcpServerIds - it
                },
                onDismiss = { showMcpManager = false }
            )
        }
    }

    private fun toggleId(current: Set<String>, id: String): Set<String> {
        return if (id in current) current - id else current + id
    }

    private fun parseToolIds(raw: String): Set<String> {
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) {
                    val id = array.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun toggleTool(current: Set<String>, id: String): Set<String> {
        return if (id in current) current - id else current + id
    }

    private fun formatToolLabels(enabledToolsJson: String): String {
        val ids = parseToolIds(enabledToolsJson)
        return toolOptions.filter { it.id in ids }.joinToString(", ") { it.label }
    }

    private fun modelLabel(modelId: String): String =
        modelLabel(modelId, PresetModelCatalog.downloadedModels(requireContext()))

    // ★ パフォーマンス修正: downloadedModels() の呼び出しを呼び出し元でキャッシュできるよう、
    //   解決済みリストを受け取るオーバーロードを追加。PresetRow 描画ループのような
    //   ホットパスからは必ずこちらを使い、都度ディスク I/O が走らないようにする。
    private fun modelLabel(modelId: String, downloadedOptions: List<com.nezumi_ai.data.preset.PresetModelOption>): String =
        downloadedOptions.firstOrNull { it.id == modelId }?.label
            ?: when {
                modelId == PresetConstants.MODEL_GEMMA4_LITERT -> "Gemma 4 2B"
                com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(modelId) ->
                    com.nezumi_ai.data.inference.cloud.CloudModelId.displayLabel(modelId)
                else -> modelId
            }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun NezumiComposeTheme(content: @Composable () -> Unit) {
        val bg = colorResource(id = R.color.bg_session_list)
        val primary = colorResource(id = R.color.primary)
        val onPrimary = colorResource(id = R.color.nezumi_on_primary)
        val primaryContainer = colorResource(id = R.color.nezumi_primary_container)
        val onPrimaryContainer = colorResource(id = R.color.nezumi_on_primary_container)
        val surface = colorResource(id = R.color.surface_card)
        val onSurface = colorResource(id = R.color.text_primary)
        val onSurfaceVariant = colorResource(id = R.color.text_secondary)

        val colorScheme = if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = primary,
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer,
                onSecondaryContainer = onPrimaryContainer,
                tertiary = primary,
                onTertiary = onPrimary,
                tertiaryContainer = primaryContainer,
                onTertiaryContainer = onPrimaryContainer,
                background = bg,
                onBackground = onSurface,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surface,
                onSurfaceVariant = onSurfaceVariant
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = primary,
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer,
                onSecondaryContainer = onPrimaryContainer,
                tertiary = primary,
                onTertiary = onPrimary,
                tertiaryContainer = primaryContainer,
                onTertiaryContainer = onPrimaryContainer,
                background = bg,
                onBackground = onSurface,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surface,
                onSurfaceVariant = onSurfaceVariant
            )
        }

        val assetContext = LocalContext.current

        val notoFamily = remember(assetContext.assets) {

            createNotoSansJpFontFamily(assetContext.assets)

        }

        val notoTypography = remember(notoFamily) {

            createNotoSansJpTypography(notoFamily)

        }

        MaterialTheme(

            colorScheme = colorScheme,

            typography = notoTypography,

            content = content

        )
    }

    private val toolOptions by lazy {
        listOf(
            ToolOption(PresetConstants.TOOL_TIME, getString(R.string.preset_tool_name_time)),
            ToolOption(PresetConstants.TOOL_BATTERY, getString(R.string.preset_tool_name_battery)),
            ToolOption(PresetConstants.TOOL_ALARM, getString(R.string.preset_tool_name_alarm)),
            ToolOption(PresetConstants.TOOL_TIMER, getString(R.string.preset_tool_name_timer)),
            ToolOption(PresetConstants.TOOL_FLASHLIGHT, getString(R.string.preset_tool_name_flashlight)),
            ToolOption(PresetConstants.TOOL_IMAGE_GENERATION, getString(R.string.preset_tool_name_image_generation)),
            ToolOption(PresetConstants.TOOL_MEMORY, getString(R.string.preset_tool_name_memory)),
            ToolOption(PresetConstants.TOOL_MEMORY_SAVE, getString(R.string.preset_tool_name_memory_save)),
            ToolOption(PresetConstants.TOOL_WEB_SEARCH, getString(R.string.preset_tool_name_web_search)),
            // web_search で見つけた URL の本文を Markdown で取得する
            ToolOption(PresetConstants.TOOL_WEB_FETCH, getString(R.string.preset_tool_name_web_fetch)),
            // ドキュメント作成: Markdown から Word/PDF/Excel ファイルを生成。
            // (Word/PDF/Excel の読み取りは添付時に自動で Markdown 変換されるため
            //  ツールとしては存在しない)
            ToolOption(PresetConstants.TOOL_CONVERT_MD_TO_DOCUMENT, getString(R.string.preset_tool_name_convert_md_to_document)),
            // CALENDAR_DISABLED: ToolOption(PresetConstants.TOOL_CALENDAR, getString(R.string.preset_tool_name_calendar))
        )
    }

    private data class ToolOption(val id: String, val label: String)
}
