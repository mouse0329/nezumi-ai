package com.nezumi_ai.presentation.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nezumi_ai.R
import com.nezumi_ai.data.skill.Skill
import com.nezumi_ai.data.skill.SkillFileEntry
import com.nezumi_ai.data.skill.SkillRepository

@Composable
fun SkillDirectoryDialog(
    skill: Skill,
    repository: SkillRepository,
    onDismiss: () -> Unit,
    onSkillDeleted: () -> Unit,
    onFilesChanged: () -> Unit
) {
    var files by remember(skill.name) { mutableStateOf<List<SkillFileEntry>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var savedContent by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteSkillConfirm by remember { mutableStateOf(false) }
    var showDeleteFileConfirm by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }

    fun reloadFiles(selectPath: String? = selectedPath) {
        repository.listUserSkillFiles(skill.name)
            .onSuccess { entries ->
                files = entries
                loadError = null
                if (selectPath != null && entries.none { !it.isDirectory && it.relativePath == selectPath }) {
                    selectedPath = null
                    editorContent = ""
                    savedContent = ""
                }
            }
            .onFailure { loadError = it.message }
    }

    LaunchedEffect(skill.name) { reloadFiles() }

    LaunchedEffect(selectedPath) {
        val path = selectedPath ?: return@LaunchedEffect
        repository.readUserFile(skill.name, path)
            .onSuccess { content ->
                editorContent = content
                savedContent = content
            }
            .onFailure { statusMessage = it.message }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.skills_directory_title),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.preset_cancel))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showNewFileDialog = true }) {
                        Text(stringResource(R.string.skills_new_file))
                    }
                    TextButton(onClick = { showDeleteSkillConfirm = true }) {
                        Text(stringResource(R.string.skills_delete))
                    }
                }

                HorizontalDivider()

                if (loadError != null) {
                    Text(
                        loadError.orEmpty(),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Row(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(0.38f)
                                .fillMaxHeight()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(files, key = { it.relativePath }) { entry ->
                                SkillFileRow(
                                    entry = entry,
                                    selected = entry.relativePath == selectedPath,
                                    onClick = {
                                        if (!entry.isDirectory) selectedPath = entry.relativePath
                                    }
                                )
                            }
                            if (files.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.skills_directory_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }

                        VerticalDivider(modifier = Modifier.fillMaxHeight())

                        Column(
                            modifier = Modifier
                                .weight(0.62f)
                                .fillMaxHeight()
                                .padding(8.dp)
                        ) {
                            if (selectedPath == null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        stringResource(R.string.skills_directory_select_file),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Text(
                                    selectedPath.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                OutlinedTextField(
                                    value = editorContent,
                                    onValueChange = { editorContent = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TextButton(
                                        enabled = editorContent != savedContent,
                                        onClick = {
                                            val path = selectedPath ?: return@TextButton
                                            repository.writeUserFile(skill.name, path, editorContent)
                                                .onSuccess {
                                                    savedContent = editorContent
                                                    statusMessage = null
                                                    onFilesChanged()
                                                }
                                                .onFailure { statusMessage = it.message }
                                        }
                                    ) {
                                        Text(stringResource(R.string.preset_save))
                                    }
                                    if (selectedPath != "SKILL.md") {
                                        TextButton(onClick = { showDeleteFileConfirm = true }) {
                                            Text(stringResource(R.string.skills_delete_file))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            title = { Text(stringResource(R.string.skills_directory_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { statusMessage = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showDeleteSkillConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSkillConfirm = false },
            title = { Text(stringResource(R.string.skills_delete_confirm_title)) },
            text = { Text(stringResource(R.string.skills_delete_confirm_message, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSkillConfirm = false
                    repository.deleteUserSkill(skill.name)
                        .onSuccess {
                            onSkillDeleted()
                            onDismiss()
                        }
                        .onFailure { statusMessage = it.message }
                }) {
                    Text(stringResource(R.string.skills_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSkillConfirm = false }) {
                    Text(stringResource(R.string.preset_cancel))
                }
            }
        )
    }

    if (showDeleteFileConfirm) {
        val path = selectedPath
        AlertDialog(
            onDismissRequest = { showDeleteFileConfirm = false },
            title = { Text(stringResource(R.string.skills_delete_file_confirm_title)) },
            text = { Text(stringResource(R.string.skills_delete_file_confirm_message, path.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteFileConfirm = false
                    if (path == null) return@TextButton
                    repository.deleteUserFile(skill.name, path)
                        .onSuccess {
                            selectedPath = null
                            editorContent = ""
                            savedContent = ""
                            reloadFiles(null)
                            onFilesChanged()
                        }
                        .onFailure { statusMessage = it.message }
                }) {
                    Text(stringResource(R.string.skills_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFileConfirm = false }) {
                    Text(stringResource(R.string.preset_cancel))
                }
            }
        )
    }

    if (showNewFileDialog) {
        SkillNewFileDialog(
            onDismiss = { showNewFileDialog = false },
            onCreate = { path, content ->
                val relativePath = "references/$path"
                repository.writeUserFile(skill.name, relativePath, content)
                    .onSuccess {
                        showNewFileDialog = false
                        reloadFiles(relativePath)
                        selectedPath = relativePath
                        onFilesChanged()
                    }
                    .onFailure { statusMessage = it.message }
            }
        )
    }
}

@Composable
private fun SkillFileRow(
    entry: SkillFileEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    val depth = entry.relativePath.count { it == '/' }
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = !entry.isDirectory, onClick = onClick)
            .padding(start = (8 + depth * 12).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (entry.isDirectory) "📁" else "📄",
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (entry.isDirectory) FontFamily.Default else FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SkillNewFileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var path by remember { mutableStateOf("guides/guide.md") }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_new_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.skills_markdown_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.skills_markdown_content)) },
                    minLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(path.trim(), content) }) {
                Text(stringResource(R.string.preset_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.preset_cancel))
            }
        }
    )
}
