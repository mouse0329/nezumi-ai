package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.zIndex
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
import com.nezumi_ai.data.mcp.McpPreferences
import com.nezumi_ai.presentation.ui.component.McpServerManagerDialog
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

    @Composable
    private fun PresetScreen() {
        val scope = rememberCoroutineScope()
        val presets by presetRepository.observePresets().collectAsState(initial = emptyList())
        var currentPresetId by remember {
            mutableStateOf(PreferencesHelper.getCurrentPresetId(requireContext()))
        }
        var editingPreset by remember { mutableStateOf<PresetEntity?>(null) }
        var showCreateDialog by remember { mutableStateOf(false) }
        var presetSearchQuery by remember { mutableStateOf("") }
        val displayedPresets = remember(presets, presetSearchQuery) {
            val q = presetSearchQuery.trim()
            if (q.isEmpty()) presets
            else presets.filter { p ->
                p.name.contains(q, ignoreCase = true) ||
                    p.description.contains(q, ignoreCase = true) ||
                    p.tagsCsv.contains(q, ignoreCase = true)
            }
        }

        // ドラッグ並び替え状態
        var draggingList by remember { mutableStateOf<List<PresetEntity>?>(null) }
        var dragIndex by remember { mutableIntStateOf(-1) }
        var dragOffsetY by remember { mutableFloatStateOf(0f) }
        var autoScrollJob by remember { mutableStateOf<Job?>(null) }
 // 自動スクロールの、ループ内で参照される最新のポインタY位置と方向。
        // onDrag のローカル変数を while(true) のコルーチン内で参照しても stale になるので、
        // 毎回 onDrag で mutableStateOf に上書きして共有する。
        var autoScrollDirection by remember { mutableIntStateOf(0) } // -1: up, 0: none, 1: down
        var autoScrollDistance by remember { mutableFloatStateOf(0f) } // edge への食い込み量(0..edgeThreshold)
 // 並び替え確定後、DB からの新しい順序が Flow で届くまで表示する"暫定並び順"。
        //   これがある間は displayedPresets(=DBの古い順序) を上書きし、
        //   "決定時に一瞬前の状態が表示される" フリッカーを防ぐ。
        var pendingOrderIds by remember { mutableStateOf<List<String>?>(null) }

        // pendingOrderIds に基づいて displayedPresets を並び替えた最終表示リスト。
        // DB からの新しい順序と pendingOrderIds が一致したら pendingOrderIds を解除する。
        val sortedDisplayed = remember(displayedPresets, pendingOrderIds) {
            val pending = pendingOrderIds
            if (pending == null) {
                displayedPresets
            } else {
                val byId = displayedPresets.associateBy { it.id }
                val reordered = pending.mapNotNull { byId[it] }
                // pending に含まれない新規/検索でフィルタされた項目はそのまま末尾に追加
                val remaining = displayedPresets.filter { it.id !in pending }
                reordered + remaining
            }
        }

        // DB の順序が pending と一致したら pending を解除する（Flow が追いついた合図）。
        LaunchedEffect(displayedPresets, pendingOrderIds) {
            val pending = pendingOrderIds ?: return@LaunchedEffect
            val actualIdsInPendingOrder = displayedPresets.map { it.id }
                .filter { it in pending }
            if (actualIdsInPendingOrder == pending.filter { it in displayedPresets.map { p -> p.id } }) {
                pendingOrderIds = null
            }
        }

        // ドラッグ中は draggingList を使い、それ以外は sortedDisplayed を使う
        val visibleList = draggingList ?: sortedDisplayed

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

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
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

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = presetSearchQuery,
                        onValueChange = { presetSearchQuery = it },
                        label = { Text("プリセットを検索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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

            itemsIndexed(
                items = visibleList,
                key = { _, preset -> preset.id }
            ) { index, preset ->
                val isDragging = index == dragIndex
                PresetRow(
                    preset = preset,
                    selected = preset.id == currentPresetId,
                    canMoveUp = index > 0,
                    canMoveDown = index < visibleList.lastIndex,
                    isDragging = isDragging,
                    dragOffsetY = if (isDragging) dragOffsetY else 0f,
                    onDragStart = {
                        dragIndex = index
                        dragOffsetY = 0f
                        draggingList = visibleList.toMutableList()
                    },
                    onDrag = { dy, pointerYInViewport, threshold ->
                        dragOffsetY += dy
                        when {
                            dragOffsetY > threshold && dragIndex < (draggingList?.lastIndex ?: 0) -> {
                                val list = draggingList!!.toMutableList()
                                val tmp = list[dragIndex + 1]; list[dragIndex + 1] = list[dragIndex]; list[dragIndex] = tmp
                                draggingList = list
                                dragIndex++
                                dragOffsetY -= threshold
                            }
                            dragOffsetY < -threshold && dragIndex > 0 -> {
                                val list = draggingList!!.toMutableList()
                                val tmp = list[dragIndex - 1]; list[dragIndex - 1] = list[dragIndex]; list[dragIndex] = tmp
                                draggingList = list
                                dragIndex--
                                dragOffsetY += threshold
                            }
                        }

 // 自動スクロール:
                        //   ・従来の while(true){ scrollToItem() } は 1item ごとにジャンプしてカクついていた。
                        //     animateScrollBy と小さめの幅で連続スクロールさせることで滑らかにする。
                        //   ・edge 判定をー pointerYInViewport は Card 内座標なので、
                        //     LazyColumn の viewport 基準に変換してから判定する。
                        val viewportHeight = listState.layoutInfo.viewportSize.height
                        // ドラッグ中の item は itemsIndexed により LazyList の index は
                        // 前置アイテム(Spacer / ヘッダ / 検索欄) の分だけオフセットする。
                        // 確実に見つけるため、検索欄とヘッダーを除いた"data item"の相対位置を見る。
                        val leadingItemCount = 3 // Spacer + Header + Search
                        val currentItemInfo = listState.layoutInfo.visibleItemsInfo
                            .find { it.index == dragIndex + leadingItemCount }
                        val pointerYInList: Float = if (currentItemInfo != null) {
                            // Card の上端（viewport 基準） + drag offset + ポインタのCard内Y
                            currentItemInfo.offset + dragOffsetY + pointerYInViewport
                        } else {
                            pointerYInViewport
                        }
                        val edgeThreshold = 150f
                        val shouldScrollUp = pointerYInList < edgeThreshold
                        val shouldScrollDown = pointerYInList > viewportHeight - edgeThreshold

                        // 最新の方向と食い込み量を State へ書き込み、自動スクロールループから参照させる。
                        val newDirection = when {
                            shouldScrollDown -> 1
                            shouldScrollUp -> -1
                            else -> 0
                        }
                        autoScrollDirection = newDirection
                        autoScrollDistance = when (newDirection) {
                            1 -> (pointerYInList - (viewportHeight - edgeThreshold)).coerceAtLeast(0f)
                            -1 -> (edgeThreshold - pointerYInList).coerceAtLeast(0f)
                            else -> 0f
                        }

                        if (newDirection != 0) {
                            // 既にジョブが回っているなら手を付けない（キャンセル→再起動の回避）。
                            val running = autoScrollJob?.isActive == true
                            if (!running) {
                                autoScrollJob = scope.launch {
                                    while (autoScrollDirection != 0) {
                                        val speedFactor = (autoScrollDistance / edgeThreshold).coerceIn(0.1f, 1f)
                                        val pixelsPerFrame = 24f * speedFactor // 1frameあたり最大24px
                                        val delta = pixelsPerFrame * autoScrollDirection
                                        listState.scrollBy(delta)
                                        delay(16)
                                    }
                                }
                            }
                        } else {
                            autoScrollJob?.cancel()
                            autoScrollJob = null
                        }
                    },
                    onDragEnd = {
                        autoScrollDirection = 0
                        autoScrollDistance = 0f
                        autoScrollJob?.cancel()
                        autoScrollJob = null
                        val finalList = draggingList
                        dragIndex = -1
                        dragOffsetY = 0f
                        if (finalList != null) {
 // フリッカー防止:
                            //   draggingList を null に戻す前に、確定した順序を pendingOrderIds に登録する。
                            //   これにより、DB Flow が更新後の順序を配信するまでの間も、
                            //   sortedDisplayed が pendingOrderIds に従って並ぶ。
                            val finalIds = finalList.map { it.id }
                            pendingOrderIds = finalIds
                            draggingList = null
                            scope.launch {
                                try {
                                    presetRepository.reorder(finalIds)
                                    android.util.Log.d("PresetReorder", "success: ${finalList.map { it.name }}")
                                } catch (e: Exception) {
                                    android.util.Log.e("PresetReorder", "failed", e)
                                    // 失敗時は pending を解除して DB の順序に戻す
                                    pendingOrderIds = null
                                }
                            }
                        } else {
                            draggingList = null
                        }
                    },
                    onMoveUp = {
                        scope.launch {
                            val ids = visibleList.toMutableList()
                            val tmp = ids[index - 1]; ids[index - 1] = ids[index]; ids[index] = tmp
                            presetRepository.reorder(ids.map { it.id })
                        }
                    },
                    onMoveDown = {
                        scope.launch {
                            val ids = visibleList.toMutableList()
                            val tmp = ids[index + 1]; ids[index + 1] = ids[index]; ids[index] = tmp
                            presetRepository.reorder(ids.map { it.id })
                        }
                    },
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
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        isDragging: Boolean,
        dragOffsetY: Float,
        onDragStart: () -> Unit,
        onDrag: (Float, Float, Float) -> Unit,
        onDragEnd: () -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onSelect: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        var itemHeightPx by remember { mutableFloatStateOf(0f) }
        val thresholdPx = if (itemHeightPx > 0f) itemHeightPx else 120f

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer { translationY = dragOffsetY }
                .onGloballyPositioned { coordinates ->
                    itemHeightPx = coordinates.size.height.toFloat()
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount -> onDrag(dragAmount.y, change.position.y, thresholdPx) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    )
                }
                .clickable(onClick = onSelect),
            colors = CardDefaults.cardColors(
                containerColor = if (isDragging)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    MaterialTheme.colorScheme.surface
            )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) {
                            Text(
 text = "",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Text("↑", color = if (canMoveUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Text("↓", color = if (canMoveDown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
 var icon by remember { mutableStateOf(initialPreset?.icon ?: "") }
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
        val mcpPrefs = remember { McpPreferences.get(requireContext()) }
        val mcpServers by mcpPrefs.servers.collectAsState()
        var selectedMcpServerIds by remember {
            mutableStateOf(McpPreferences.decodeServerIds(initialPreset?.mcpServerIds))
        }
        var showMcpManager by remember { mutableStateOf(false) }

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
                                Divider(modifier = Modifier.padding(vertical = 4.dp))
                                // MCP: プリセットのツール一覧の直下に配置
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("MCP サーバー", fontWeight = FontWeight.Bold)
                                        val subLabel = if (mcpServers.isEmpty()) {
                                            "未登録"
                                        } else {
                                            val active = mcpServers.count { it.id in selectedMcpServerIds }
                                            "$active / ${mcpServers.size} 個を有効化"
                                        }
                                        Text(
                                            text = subLabel,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    TextButton(onClick = { showMcpManager = true }) {
                                        Text("MCPを追加")
                                    }
                                }
                                if (mcpServers.isNotEmpty()) {
                                    mcpServers.forEach { server ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedMcpServerIds = toggleId(selectedMcpServerIds, server.id)
                                                },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = server.id in selectedMcpServerIds,
                                                onCheckedChange = {
                                                    selectedMcpServerIds = toggleId(selectedMcpServerIds, server.id)
                                                }
                                            )
                                            Column {
                                                Text(server.name)
                                                Text(
                                                    text = "${server.transport.label} • ${server.url}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
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
                                mcpServerIds = McpPreferences.encodeServerIds(selectedMcpServerIds)
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
        // CALENDAR_DISABLED: ToolOption(PresetConstants.TOOL_CALENDAR, "カレンダー")
    )

    private data class ToolOption(val id: String, val label: String)
}
