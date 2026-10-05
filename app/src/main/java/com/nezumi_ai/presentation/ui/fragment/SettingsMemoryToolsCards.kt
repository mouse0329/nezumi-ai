package com.nezumi_ai.presentation.ui.fragment

// SettingsComposeFragment から切り出したメモリ・ツール・ログの設定カード。

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.memory.MemorySaveMode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors

@Composable
internal fun SettingsComposeFragment.MemoryManagementCard(settingsSearchJumpState: SettingsSearchJumpState) {
    val localContext = LocalContext.current
    val memories by memoryRepository.observeMemories().collectAsState(initial = emptyList())
    var showMemoryListModal by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(id = R.string.settings_memory_delete_all_title)) },
            text = { Text(stringResource(id = R.string.settings_memory_delete_all_body)) },
            confirmButton = {
                Button(onClick = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        memoryRepository.softDeleteAll()
                        confirmDeleteAll = false
                        toast(localContext.getString(R.string.settings_memory_deleted_toast))
                    }
                }) {
                    Text(stringResource(id = R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(id = R.string.common_cancel))
                }
            }
        )
    }

    if (showMemoryListModal) {
        MemoryListModal(
            memories = memories,
            onDismiss = { showMemoryListModal = false },
            onDeleteMemory = { memoryId ->
                viewLifecycleOwner.lifecycleScope.launch {
                    memoryRepository.softDelete(memoryId)
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.primary_light)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.settingsSearchAnchor(
                        R.string.settings_memory_management_title,
                        settingsSearchJumpState
                    )
                ) {
                    Text(text = stringResource(id = R.string.settings_memory_management_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                    Text(
                        text = stringResource(id = R.string.settings_memory_count_format, memories.size),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row {
                    TextButton(
                        enabled = memories.isNotEmpty(),
                        onClick = { showMemoryListModal = true },
                        modifier = Modifier.settingsSearchAnchor(
                            R.string.settings_memory_list_show,
                            settingsSearchJumpState
                        )
                    ) {
                        Text(stringResource(id = R.string.settings_memory_list_show))
                    }
                    TextButton(
                        enabled = memories.isNotEmpty(),
                        onClick = { confirmDeleteAll = true },
                        modifier = Modifier.settingsSearchAnchor(
                            R.string.settings_memory_delete_all_title,
                            settingsSearchJumpState
                        )
                    ) {
                        Text(stringResource(id = R.string.settings_memory_delete_all_button2))
                    }
                }
            }

            Text(
                text = stringResource(id = R.string.settings_memory_save_mode_title),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.settings_memory_save_mode_title,
                    settingsSearchJumpState
                )
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // v2.1+ デフォルト方式: LLM が明示的に save_memory ツールを呼んだときのみ保存する。
                FilterChip(
                    selected = memorySaveMode == MemorySaveMode.TOOL_ONLY.name,
                    onClick = { memorySaveMode = MemorySaveMode.TOOL_ONLY.name },
                    label = { Text(stringResource(id = R.string.settings_memory_mode_tool_only_label)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = memorySaveMode == MemorySaveMode.LLM.name,
                    onClick = { memorySaveMode = MemorySaveMode.LLM.name },
                    label = { Text(stringResource(id = R.string.settings_memory_mode_llm_label)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = memorySaveMode == MemorySaveMode.RULE_BASED.name,
                    onClick = { memorySaveMode = MemorySaveMode.RULE_BASED.name },
                    label = { Text(stringResource(id = R.string.settings_memory_mode_rule_label)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun MemoryListModal(
    memories: List<com.nezumi_ai.data.database.entity.MemoryEntity>,
    onDismiss: () -> Unit,
    onDeleteMemory: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_memory_list_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(memories) { memory ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = memory.content,
                                color = colorResource(id = R.color.text_primary),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(id = R.string.settings_memory_importance_format, String.format("%.2f", memory.importance), memory.accessCount),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = { onDeleteMemory(memory.id) }) {
                            Text(stringResource(id = R.string.common_delete))
                        }
                    }
                    HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.14f), thickness = 1.dp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.common_close))
            }
        }
    )
}

/**
 * 設定 > ツール タブ
 * ページ取得のJS実行モードON/OFFと、MCPサーバーの登録・編集・削除を扱う。
 * 各ツール（アラーム・タイマー・画像生成等）自体の有効化は
 * プリセット編集画面（PresetSettingsFragment）側で行う。
 */
@Composable
internal fun ToolsSettingsCard(settingsSearchJumpState: SettingsSearchJumpState) {
    val localContext = LocalContext.current
    val toolPreferences = remember { com.nezumi_ai.data.inference.ToolPreferences(localContext) }
    var webFetchJsRenderEnabled by remember {
        mutableStateOf(toolPreferences.isWebFetchJsRenderEnabled())
    }
    val mcpPrefs = remember { com.nezumi_ai.data.mcp.McpPreferences.get(localContext) }
    val mcpServers by mcpPrefs.servers.collectAsState()
    var showMcpManager by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(id = R.string.preset_tool_name_web_fetch),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .settingsSearchAnchor(
                            R.string.tools_web_fetch_js_render,
                            settingsSearchJumpState
                        )
                ) {
                    Text(
                        text = stringResource(id = R.string.tools_web_fetch_js_render),
                        color = colorResource(id = R.color.text_primary)
                    )
                    Text(
                        text = stringResource(id = R.string.tools_web_fetch_js_render_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
                Switch(
                    checked = webFetchJsRenderEnabled,
                    onCheckedChange = { checked ->
                        toolPreferences.setWebFetchJsRenderEnabled(checked)
                        webFetchJsRenderEnabled = checked
                    },
                    colors = nezumiSwitchColors()
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .settingsSearchAnchor(
                            R.string.preset_edit_mcp_server_label,
                            settingsSearchJumpState
                        )
                ) {
                    Text(
                        text = stringResource(id = R.string.preset_edit_mcp_server_label),
                        fontWeight = FontWeight.Bold,
                        fontSize = MaterialTheme.typography.titleMedium.fontSize
                    )
                    val subLabel = if (mcpServers.isEmpty()) {
                        stringResource(id = R.string.preset_edit_mcp_servers_unregistered)
                    } else {
                        stringResource(id = R.string.mcp_server_manager_count_format, mcpServers.size)
                    }
                    Text(
                        text = subLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
                TextButton(onClick = { showMcpManager = true }) {
                    Text(stringResource(id = R.string.preset_edit_mcp_add))
                }
            }
            if (mcpServers.isNotEmpty()) {
                mcpServers.forEach { server ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(server.name, color = colorResource(id = R.color.text_primary))
                        Text(
                            text = "${server.transport.label} • ${server.url}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                }
            }
        }
    }

    if (showMcpManager) {
        // このダイアログは本来プリセットへの有効化選択も兼ねるが、ここでは
        // サーバーの登録・編集・削除のみを目的として開くため、選択状態は
        // 空集合のまま扱い、プリセット側の紐付けには影響させない。
        com.nezumi_ai.presentation.ui.component.McpServerManagerDialog(
            servers = mcpServers,
            selectedIds = emptySet(),
            onSelectionChange = {},
            onUpsert = { mcpPrefs.upsert(it) },
            onDelete = { mcpPrefs.remove(it) },
            onDismiss = { showMcpManager = false }
        )
    }
}

/**
 * 設定 > ログ タブ
 * ツールコール呼出履歴と logcat をサブタブで分けて表示する。
 * リリースビルドでも利用可能。
 */
@Composable
internal fun SettingsComposeFragment.LogsSettingsCard(settingsSearchJumpState: SettingsSearchJumpState) {
    var selectedLogSubTab by remember { mutableIntStateOf(0) }
    val logSubTabs = listOf(
        stringResource(id = R.string.logs_tab_tool_history),
        stringResource(id = R.string.logs_tab_logcat)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedLogSubTab,
                edgePadding = 0.dp,
                containerColor = colorResource(id = R.color.primary_light),
                modifier = Modifier
                    .settingsSearchAnchor(R.string.logs_tab_tool_history, settingsSearchJumpState)
                    .settingsSearchAnchor(R.string.logs_tab_logcat, settingsSearchJumpState)
            ) {
                logSubTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedLogSubTab == index,
                        onClick = { selectedLogSubTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedLogSubTab) {
                0 -> ToolHistorySection()
                else -> LogcatViewerSection(settingsSearchJumpState)
            }
        }
    }
}

/**
 * ツールコール呼出履歴（時刻・セッション・ツール・クエリ）を表示する。
 */
@Composable
internal fun ToolHistorySection() {
    val localContext = LocalContext.current
    val db = remember { NezumiAiDatabase.getInstance(localContext) }
    val toolHistoryRepo = remember {
        com.nezumi_ai.data.repository.ToolCallHistoryRepository(
            db.toolCallHistoryDao(),
            db.chatSessionDao()
        )
    }
    val history by toolHistoryRepo.observeRecent(300).collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var filtered by remember { mutableStateOf<List<com.nezumi_ai.data.database.entity.ToolCallHistoryEntity>?>(null) }
    val scope = rememberCoroutineScope()
    val timeFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(id = R.string.logs_tool_history_query)) }
            )
            Button(onClick = {
                val q = query.trim().lowercase()
                filtered = if (q.isEmpty()) null else history.filter {
                    it.toolName.lowercase().contains(q) ||
                        (it.query?.lowercase()?.contains(q) == true) ||
                        (it.sessionName?.lowercase()?.contains(q) == true)
                }
            }) { Text(stringResource(id = android.R.string.search_go)) }
            Button(onClick = {
                scope.launch {
                    toolHistoryRepo.clearAll()
                    filtered = null
                    query = ""
                }
            }) { Text(stringResource(id = R.string.logs_clear_tool_history)) }
        }
        val display = filtered ?: history
        if (display.isEmpty()) {
            Text(
                text = stringResource(id = R.string.logs_tool_history_empty),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                display.take(100).forEach { row ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = timeFmt.format(java.util.Date(row.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary)
                            )
                            Text(
                                text = "${stringResource(id = R.string.logs_tool_history_tool)}: ${row.toolName}",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${stringResource(id = R.string.logs_tool_history_session)}: " +
                                    (row.sessionName?.takeIf { it.isNotBlank() } ?: "#${row.sessionId}"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!row.query.isNullOrBlank()) {
                                Text(
                                    text = "${stringResource(id = R.string.logs_tool_history_query)}: ${row.query}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                text = if (row.success) "OK" else "FAIL",
                                color = if (row.success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
