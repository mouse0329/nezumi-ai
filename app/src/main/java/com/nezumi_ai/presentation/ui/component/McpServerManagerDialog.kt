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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nezumi_ai.R
import com.nezumi_ai.data.mcp.McpClient
import com.nezumi_ai.data.mcp.McpServerConfig
import com.nezumi_ai.data.mcp.McpToolDescriptor
import com.nezumi_ai.data.mcp.McpTransport
import com.nezumi_ai.data.mcp.PrivateIpValidator
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
        title = { Text(stringResource(id = R.string.mcp_server_manager_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(id = R.string.mcp_server_manager_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Divider()
                if (servers.isEmpty()) {
                    Text(
                        stringResource(id = R.string.mcp_server_manager_empty),
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
                    Text(stringResource(id = R.string.mcp_server_manager_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.mcp_server_manager_done)) }
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
                        text = stringResource(id = R.string.mcp_server_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            TextButton(onClick = onEdit) { Text(stringResource(id = R.string.mcp_server_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(id = R.string.mcp_server_delete)) }
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
    var testSuccess by remember { mutableStateOf<Boolean?>(null) }
    var testedTools by remember { mutableStateOf<List<McpToolDescriptor>>(emptyList()) }

    val ctx = LocalContext.current
    val defaultNameStr = stringResource(id = R.string.mcp_server_default_name)
    val testingText = stringResource(id = R.string.mcp_server_testing)
    val testConnectText = stringResource(id = R.string.mcp_server_test_connect)

    // http:// はプライベートIP/localhost宛のみ許可。パブリックホストへは https:// を要求する。
    val urlValidation = remember(url) {
        if (url.isBlank()) null else PrivateIpValidator.validate(url)
    }
    val urlError = (urlValidation as? PrivateIpValidator.ValidationResult.Error)?.message

    val scope = rememberCoroutineScope()

    // 入力が変わったらテスト結果はリセット
    LaunchedEffect(url, transport, authHeader, extraHeaders) {
        testMessage = null
        testSuccess = null
        testedTools = emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(id = R.string.mcp_server_editor_title_add) else stringResource(id = R.string.mcp_server_editor_title_edit)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(id = R.string.mcp_server_label_display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(id = R.string.mcp_server_label_endpoint_url)) },
                    placeholder = { Text(stringResource(id = R.string.mcp_server_placeholder_endpoint_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = urlError != null,
                    supportingText = {
                        Text(
                            urlError
                                ?: stringResource(id = R.string.mcp_server_url_hint)
                        )
                    }
                )
                ExposedDropdownMenuBox(
                    expanded = transportExpanded,
                    onExpandedChange = { transportExpanded = !transportExpanded }
                ) {
                    OutlinedTextField(
                        value = transport.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.mcp_server_label_transport)) },
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
                    label = { Text(stringResource(id = R.string.mcp_server_label_auth_header)) },
                    placeholder = { Text(stringResource(id = R.string.mcp_server_placeholder_auth_header)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = extraHeaders,
                    onValueChange = { extraHeaders = it },
                    label = { Text(stringResource(id = R.string.mcp_server_label_extra_headers)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 120.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(id = R.string.mcp_server_enable_server), fontWeight = FontWeight.Bold)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Divider()
                TextButton(
                    enabled = !testing && url.isNotBlank() && urlError == null,
                    onClick = {
                        testing = true
                        testMessage = testingText
                        testedTools = emptyList()
                        val draft = buildConfigFromInputs(
                            initial, name.ifBlank { defaultNameStr }, url, transport, enabled,
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
                                testSuccess = true
                                testedTools = tools
                                testMessage = ctx.getString(R.string.mcp_server_test_ok, tools.size)
                            }.onFailure { e ->
                                testSuccess = false
                                testMessage = ctx.getString(R.string.mcp_server_test_failed, e.message ?: e.javaClass.simpleName)
                            }

                        }
                    }
                ) {
                    Text(if (testing) testingText else testConnectText)
                }
                testMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testSuccess == true) {
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
                                text = stringResource(id = R.string.mcp_server_tool_more, testedTools.size - 8),
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
                enabled = name.isNotBlank() && url.isNotBlank() && urlError == null,
                onClick = {
                    val cfg = buildConfigFromInputs(
                        initial, name.trim(), url.trim(), transport, enabled, authHeader, extraHeaders
                    )
                    onSave(cfg)
                }
            ) { Text(stringResource(id = R.string.mcp_server_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.mcp_server_cancel)) }
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
