package com.nezumi_ai.presentation.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nezumi_ai.data.mcp.McpClient
import com.nezumi_ai.data.mcp.McpServerConfig
import com.nezumi_ai.data.mcp.McpToolDescriptor
import com.nezumi_ai.data.mcp.McpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MCP サーバー管理ダイアログ。
 *
 * プリセット編集モーダルから開かれ、
 * サーバー一覧の表示・追加編集・削除・プリセットへの有効化選択・接続テストを行う。
 */
@Composable
fun McpServerManagerDialog(
    servers: List<McpServerConfig>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onUpsert: (McpServerConfig) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MCP サーバー") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Streamable HTTP / SSE で動作する MCP サーバーを登録して、プリセットに紐付けます。",
                    style = MaterialTheme.typography.bodySmall
                )
                Divider()
                if (servers.isEmpty()) {
                    Text(
                        "登録されているサーバーはありません。下の「新規追加」からサーバーを登録してください。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(servers, key = { it.id }) { server ->
                            McpServerRow(
                                server = server,
                                checked = server.id in selectedIds,
                                onToggleSelect = { checked ->
                                    onSelectionChange(
                                        if (checked) selectedIds + server.id else selectedIds - server.id
                                    )
                                },
                                onEdit = {
                                    editing = server
                                    showEditor = true
                                },
                                onDelete = { onDelete(server.id) }
                            )
                        }
                    }
                }
                Divider()
                Button(
                    onClick = {
                        editing = null
                        showEditor = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ 新規追加")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完了") }
        }
    )

    if (showEditor) {
        McpServerEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = {
                onUpsert(it)
                showEditor = false
            }
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServerConfig,
    checked: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onToggleSelect)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEdit() }
            ) {
                Text(server.name, fontWeight = FontWeight.Bold)
                Text(
                    text = "${server.transport.label} • ${server.url}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!server.enabled) {
                    Text(
                        text = "(無効)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            TextButton(onClick = onEdit) { Text("編集") }
            TextButton(onClick = onDelete) { Text("削除") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerEditorDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var transport by remember { mutableStateOf(initial?.transport ?: McpTransport.STREAMABLE_HTTP) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var authHeader by remember {
        mutableStateOf(initial?.headers?.get("Authorization") ?: "")
    }
    var extraHeaders by remember {
        val other = initial?.headers?.filterKeys { it != "Authorization" }.orEmpty()
        mutableStateOf(other.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    var transportExpanded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testedTools by remember { mutableStateOf<List<McpToolDescriptor>>(emptyList()) }

    val scope = rememberCoroutineScope()

    // 入力が変わったらテスト結果はリセット
    LaunchedEffect(url, transport, authHeader, extraHeaders) {
        testMessage = null
        testedTools = emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "MCP サーバーを追加" else "MCP サーバーを編集") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("表示名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("エンドポイント URL") },
                    placeholder = { Text("https://example.com/mcp") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                ExposedDropdownMenuBox(
                    expanded = transportExpanded,
                    onExpandedChange = { transportExpanded = !transportExpanded }
                ) {
                    OutlinedTextField(
                        value = transport.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transport") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = transportExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    DropdownMenu(
                        expanded = transportExpanded,
                        onDismissRequest = { transportExpanded = false }
                    ) {
                        McpTransport.entries.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = {
                                    transport = opt
                                    transportExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = authHeader,
                    onValueChange = { authHeader = it },
                    label = { Text("Authorization ヘッダ (任意)") },
                    placeholder = { Text("Bearer sk-xxxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = extraHeaders,
                    onValueChange = { extraHeaders = it },
                    label = { Text("追加ヘッダ (任意, 1行に1つ: key: value)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 120.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("このサーバーを有効化", fontWeight = FontWeight.Bold)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Divider()
                TextButton(
                    enabled = !testing && url.isNotBlank(),
                    onClick = {
                        testing = true
                        testMessage = "接続中..."
                        testedTools = emptyList()
                        val draft = buildConfigFromInputs(
                            initial, name.ifBlank { "MCP Server" }, url, transport, enabled,
                            authHeader, extraHeaders
                        )
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val client = McpClient(draft)
                                    val ping = client.ping()
                                    if (!ping.ok) Result.failure<List<McpToolDescriptor>>(
                                        RuntimeException(ping.errorMessage ?: "initialize failed")
                                    ) else Result.success(client.listTools())
                                }.getOrElse { Result.failure(it) }
                            }
                            testing = false
                            result.onSuccess { tools ->
                                testedTools = tools
                                testMessage = "接続 OK: ${tools.size} 個のツールを取得"
                            }.onFailure { e ->
                                testMessage = "接続失敗: ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                ) {
                    Text(if (testing) "テスト中..." else "接続テスト")
                }
                testMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("接続 OK")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                if (testedTools.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        testedTools.take(8).forEach {
                            Text(
                                text = "• ${it.name}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (testedTools.size > 8) {
                            Text(
                                text = "... 他 ${testedTools.size - 8} 件",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.heightIn(min = 4.dp))
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    val cfg = buildConfigFromInputs(
                        initial, name.trim(), url.trim(), transport, enabled, authHeader, extraHeaders
                    )
                    onSave(cfg)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

private fun buildConfigFromInputs(
    base: McpServerConfig?,
    name: String,
    url: String,
    transport: McpTransport,
    enabled: Boolean,
    authHeader: String,
    extraHeadersRaw: String
): McpServerConfig {
    val headers = mutableMapOf<String, String>()
    if (authHeader.isNotBlank()) headers["Authorization"] = authHeader.trim()
    extraHeadersRaw.lineSequence().forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) {
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotBlank()) headers[k] = v
        }
    }
    val now = System.currentTimeMillis()
    return if (base == null) {
        McpServerConfig(
            name = name,
            url = url,
            transport = transport,
            enabled = enabled,
            headers = headers,
            createdAt = now,
            updatedAt = now
        )
    } else {
        base.copy(
            name = name,
            url = url,
            transport = transport,
            enabled = enabled,
            headers = headers,
            updatedAt = now
        )
    }
}
