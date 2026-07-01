package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.preset.PresetConstants
import com.nezumi_ai.data.preset.PresetModelCatalog
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.utils.PreferencesHelper
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class PresetSettingsFragment : Fragment() {
    private lateinit var presetRepository: PresetRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        presetRepository = PresetRepository(db.presetDao(), requireContext().applicationContext)
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
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

    private enum class PresetSortKey(val label: String) {
        NAME("名前順"),
        UPDATED("更新順"),
        CREATED("作成順")
    }

    @Composable
    private fun PresetScreen() {
        val scope = rememberCoroutineScope()
        val presets by presetRepository.observePresets().collectAsState(initial = emptyList())
        var currentPresetId by remember {
            mutableStateOf(PreferencesHelper.getCurrentPresetId(requireContext()))
        }
        var editingPreset by remember { mutableStateOf<PresetEntity?>(null) }
        var showCreateDialog by remember { mutableStateOf(false) }

        // ★ プリセット一覧の「検索＋並び替え」ストート。
        //   件数が 0 でも表示されるよう、LazyColumn item として常設にしている。
        var presetSearchQuery by remember { mutableStateOf("") }
        // ★ v5.1 fix: 以前は DEFAULT を初期値にしていたが、PresetEntity.sortOrder は
        //   デフォルトで Long.MAX_VALUE で隅てるため、項目を追加しても
        //   並び順が見た目「変わらない」ように見えていた。
        //   初期値を NAME (名前順) に変更し、ユーザーがボタンを押すと
        //   NAME → UPDATED → CREATED と明らかに順番が入れ替わるようにする。
        var presetSortKey by remember { mutableStateOf(PresetSortKey.NAME) }
        var presetSortDescending by remember { mutableStateOf(false) }
        val displayedPresets = remember(presets, presetSearchQuery, presetSortKey, presetSortDescending) {
            val q = presetSearchQuery.trim()
            val filtered = if (q.isEmpty()) presets else presets.filter { p ->
                p.name.contains(q, ignoreCase = true) ||
                    p.description.contains(q, ignoreCase = true) ||
                    p.tagsCsv.contains(q, ignoreCase = true)
            }
            val cmp: Comparator<PresetEntity> = when (presetSortKey) {
                PresetSortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                PresetSortKey.UPDATED -> compareBy { it.updatedAt }
                PresetSortKey.CREATED -> compareBy { it.createdAt }
            }
            val sorted = filtered.sortedWith(cmp)
            if (presetSortDescending) sorted.reversed() else sorted
        }

        if (showCreateDialog) {
            PresetEditDialog(
                initialPreset = null,
                onDismiss = { showCreateDialog = false },
                onSave = { preset ->
                    scope.launch {
                        presetRepository.createPreset(preset)
                        showCreateDialog = false
                        toast("プリセットを作成しました")
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
                            toast("プリセットを保存しました")
                        } else {
                            toast("ロックされたプリセットは編集できません")
                        }
                    }
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.statusBarsPadding()) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { findNavController().navigateUp() }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_back),
                                contentDescription = "戻る",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "プリセット",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val manager = com.nezumi_ai.data.inference.ModelManager.getInstance(requireContext())
                                    manager.unloadModel()
                                    withContext(Dispatchers.Main) {
                                        toast("モデルを開放しました")
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        toast("モデル開放に失敗: ${e.message}")
                                    }
                                }
                            }
                        }
                    ) {
                        Text("モデル開放")
                    }
                }
            }

            // ★ 検索バーと並び替えを「プリセット一覧の上」に常時表示。
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = presetSearchQuery,
                        onValueChange = { presetSearchQuery = it },
                        label = { Text("プリセットを検索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                presetSortKey = when (presetSortKey) {
                                    PresetSortKey.NAME -> PresetSortKey.UPDATED
                                    PresetSortKey.UPDATED -> PresetSortKey.CREATED
                                    PresetSortKey.CREATED -> PresetSortKey.NAME
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("並び替え: ${presetSortKey.label}")
                        }
                        androidx.compose.material3.OutlinedButton(
                            onClick = { presetSortDescending = !presetSortDescending }
                        ) {
                            Text(if (presetSortDescending) "降順" else "昇順")
                        }
                    }
                    if (presets.isEmpty()) {
                        Text(
                            text = "プリセットはまだありません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (displayedPresets.isEmpty()) {
                        Text(
                            text = "検索条件に一致するプリセットがありません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(displayedPresets.size) { index ->
                val preset = displayedPresets[index]
                PresetRow(
                    preset = preset,
                    selected = preset.id == currentPresetId,
                    onSelect = {
                        scope.launch {
                            presetRepository.selectPreset(preset.id)
                            currentPresetId = preset.id
                            toast("${preset.name} を選択しました")
                        }
                    },
                    onEdit = { editingPreset = preset },
                    onDelete = {
                        scope.launch {
                            if (presetRepository.deletePreset(preset.id)) {
                                currentPresetId = PreferencesHelper.getCurrentPresetId(requireContext())
                                toast("プリセットを削除しました")
                            } else {
                                toast("このプリセットは削除できません")
                            }
                        }
                    }
                )
            }

            item {
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("＋ 新しいプリセット")
                }
            }
        }
    }

    @Composable
    private fun PresetRow(
        preset: PresetEntity,
        selected: Boolean,
        onSelect: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${preset.icon} ${preset.name}",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (preset.description.isNotBlank()) {
                            Text(
                                text = preset.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (selected) {
                        Text(
                            text = "✓",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(modelLabel(preset.modelId))
                        append(" / メモリ ${if (preset.memoryEnabled) "ON" else "OFF"}")
                        if (preset.toolCallingEnabled) {
                            append(" / ツール呼び出し ON")
                            val toolLabels = formatToolLabels(preset.enabledTools)
                            if (toolLabels.isNotEmpty()) append(" ($toolLabels)")
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!preset.isLocked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onEdit) {
                            Text("編集")
                        }
                        if (!preset.isDefault) {
                            TextButton(onClick = onDelete) {
                                Text("削除")
                            }
                        }
                    }
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
        var icon by remember { mutableStateOf(initialPreset?.icon ?: "🐭") }
        var description by remember { mutableStateOf(initialPreset?.description ?: "") }
        var systemPrompt by remember { mutableStateOf(initialPreset?.systemPrompt ?: "") }
        val availableModels = remember { PresetModelCatalog.downloadedModels(requireContext()) }
        var modelId by remember {
            mutableStateOf(
                initialPreset?.modelId?.takeIf { id -> availableModels.any { it.id == id } }
                    ?: availableModels.firstOrNull()?.id
                    ?: ""
            )
        }
        var memoryEnabled by remember { mutableStateOf(initialPreset?.memoryEnabled ?: true) }
        var toolCallingEnabled by remember {
            mutableStateOf(initialPreset?.toolCallingEnabled ?: false)
        }
        var enabledTools by remember {
            mutableStateOf(parseToolIds(initialPreset?.enabledTools ?: PresetRepository.encodeToolIds(PresetConstants.allToolIds)))
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
            title = { Text(if (initialPreset == null) "新しいプリセット" else "プリセット編集") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = icon,
                                onValueChange = { icon = it.take(4) },
                                label = { Text("アイコン") },
                                modifier = Modifier.weight(0.35f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("名前") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("説明") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            label = { Text("システムプロンプト") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            minLines = 4
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("モデル", fontWeight = FontWeight.Bold)
                            if (availableModels.isEmpty()) {
                                Text(
                                    text = "ダウンロード済みモデルがありません",
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
                            Text("メモリ機能", fontWeight = FontWeight.Bold)
                            Switch(
                                checked = memoryEnabled,
                                onCheckedChange = { memoryEnabled = it }
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
                                Text("ツール呼び出し", fontWeight = FontWeight.Bold)
                                Text(
                                    text = "有効時のみプリセットにツールを表示・適用します",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!selectedModelToolCallingAllowed) {
                                    Text(
                                        text = "選択中のモデルはツール呼び出しが無効です。モデル設定から有効化してください",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Switch(
                                checked = toolCallingEnabled,
                                onCheckedChange = { if (selectedModelToolCallingAllowed) toolCallingEnabled = it },
                                enabled = selectedModelToolCallingAllowed
                            )
                        }
                    }
                    if (toolCallingEnabled) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("ツール", fontWeight = FontWeight.Bold)
                                toolOptions.forEach { option ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                enabledTools = toggleTool(enabledTools, option.id)
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = option.id in enabledTools,
                                            onCheckedChange = { enabledTools = toggleTool(enabledTools, option.id) }
                                        )
                                        Text(option.label)
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
                            toast("プリセット名を入力してください")
                            return@Button
                        }
                        if (modelId.isBlank()) {
                            toast("ダウンロード済みモデルがありません")
                            return@Button
                        }
                        val now = System.currentTimeMillis()
                        onSave(
                            PresetEntity(
                                id = initialPreset?.id ?: UUID.randomUUID().toString(),
                                name = trimmedName,
                                icon = icon.ifBlank { "🐭" },
                                description = description.trim(),
                                systemPrompt = systemPrompt.trim(),
                                modelId = modelId,
                                enabledTools = PresetRepository.encodeToolIds(enabledTools.toList()),
                                createdAt = initialPreset?.createdAt ?: now,
                                updatedAt = now,
                                isDefault = initialPreset?.isDefault ?: false,
                                memoryEnabled = memoryEnabled,
                                isLocked = initialPreset?.isLocked ?: false,
                                toolCallingEnabled = toolCallingEnabled
                            )
                        )
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
            }
        )
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
        PresetModelCatalog.downloadedModels(requireContext()).firstOrNull { it.id == modelId }?.label
            ?: when (modelId) {
                PresetConstants.MODEL_GEMMA4_LITERT -> "Gemma 4 2B"
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

        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            content = content
        )
    }

    private val toolOptions = listOf(
        ToolOption(PresetConstants.TOOL_TIME, "現在時刻"),
        ToolOption(PresetConstants.TOOL_BATTERY, "バッテリー"),
        ToolOption(PresetConstants.TOOL_ALARM, "アラーム"),
        ToolOption(PresetConstants.TOOL_TIMER, "タイマー"),
        ToolOption(PresetConstants.TOOL_FLASHLIGHT, "フラッシュライト"),
        ToolOption(PresetConstants.TOOL_IMAGE_GENERATION, "画像生成"),
        ToolOption(PresetConstants.TOOL_MEMORY, "メモリ検索"),
        ToolOption(PresetConstants.TOOL_WEB_SEARCH, "ウェブ検索"),
        ToolOption(PresetConstants.TOOL_CALENDAR, "カレンダー")
    )

    private data class ToolOption(val id: String, val label: String)
}
