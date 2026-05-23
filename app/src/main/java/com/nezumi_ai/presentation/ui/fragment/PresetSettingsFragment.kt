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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.nezumi_ai.utils.PreferencesHelper
import java.util.UUID
import kotlinx.coroutines.launch
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
            MaterialTheme {
                PresetScreen()
            }
        }
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
                .background(colorResource(id = R.color.bg_session_list)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.statusBarsPadding()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { findNavController().navigateUp() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "戻る",
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                    Text(
                        text = "プリセット",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorResource(id = R.color.text_primary),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(presets.size) { index ->
                val preset = presets[index]
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
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.surface_card))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${preset.icon} ${preset.name}${if (preset.isLocked) "  🔒" else ""}",
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (preset.description.isNotBlank()) {
                            Text(
                                text = preset.description,
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (selected) {
                        Text(
                            text = "✓",
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "${modelLabel(preset.modelId)} / メモリ ${if (preset.memoryEnabled) "ON" else "OFF"}",
                    color = colorResource(id = R.color.text_secondary),
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
        var enabledTools by remember {
            mutableStateOf(parseToolIds(initialPreset?.enabledTools ?: PresetRepository.encodeToolIds(PresetConstants.allToolIds)))
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
                                    color = colorResource(id = R.color.text_secondary),
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
                                isLocked = initialPreset?.isLocked ?: false
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

    private val toolOptions = listOf(
        ToolOption(PresetConstants.TOOL_TIME, "現在時刻"),
        ToolOption(PresetConstants.TOOL_BATTERY, "バッテリー"),
        ToolOption(PresetConstants.TOOL_ALARM, "アラーム"),
        ToolOption(PresetConstants.TOOL_TIMER, "タイマー"),
        ToolOption(PresetConstants.TOOL_FLASHLIGHT, "フラッシュライト"),
        ToolOption(PresetConstants.TOOL_IMAGE_GENERATION, "画像生成"),
        ToolOption(PresetConstants.TOOL_MEMORY, "メモリ検索"),
        ToolOption(PresetConstants.TOOL_CALENDAR, "カレンダー")
    )

    private data class ToolOption(val id: String, val label: String)
}
