package com.nezumi_ai.presentation.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nezumi_ai.R
import com.nezumi_ai.data.skill.Skill
import com.nezumi_ai.data.skill.SkillFileEntry
import com.nezumi_ai.data.skill.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUTO_SAVE_DEBOUNCE_MS = 800L
private const val ROOT_PATH = ""
private const val INDENT_DP = 16
private const val ROW_HEIGHT_DP = 28

/**
 * Explorer-style skill file manager.
 *
 * The tree root is the skill folder itself, rendered as a selectable node above
 * everything else. New file / new folder / rename / delete all act on the current
 * selection, so the user never has to type a path — only a name.
 */
@Composable
fun SkillDirectoryDialog(
    skill: Skill,
    repository: SkillRepository,
    onDismiss: () -> Unit,
    onSkillDeleted: () -> Unit,
    onFilesChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var files by remember(skill.name) { mutableStateOf<List<SkillFileEntry>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // "" means the skill root itself is selected.
    var selectedPath by remember(skill.name) { mutableStateOf<String>(ROOT_PATH) }
    var editorContent by remember { mutableStateOf("") }
    var savedContent by remember { mutableStateOf("") }
    var saveState by remember { mutableStateOf(SaveState.Idle) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val expanded = remember(skill.name) { mutableStateMapOf<String, Boolean>().apply { put(ROOT_PATH, true) } }

    fun reloadFiles() {
        repository.listUserSkillFiles(skill.name)
            .onSuccess { entries ->
                files = entries
                loadError = null
                // If selection points at something no longer existing, fall back to root.
                if (selectedPath != ROOT_PATH && entries.none { it.relativePath.trimEnd('/') == selectedPath.trimEnd('/') }) {
                    selectedPath = ROOT_PATH
                    editorContent = ""
                    savedContent = ""
                }
            }
            .onFailure { loadError = it.message }
    }

    LaunchedEffect(skill.name) { reloadFiles() }

    // Load file content when a file is selected. Folders / root clear the editor.
    LaunchedEffect(selectedPath, skill.name) {
        val entry = files.firstOrNull { it.relativePath == selectedPath && !it.isDirectory }
        if (entry == null) {
            editorContent = ""
            savedContent = ""
            saveState = SaveState.Idle
            return@LaunchedEffect
        }
        repository.readUserFile(skill.name, entry.relativePath)
            .onSuccess { content ->
                editorContent = content
                savedContent = content
                saveState = SaveState.Saved
            }
            .onFailure { statusMessage = it.message }
    }

    // Debounced auto-save.
    LaunchedEffect(selectedPath, skill.name) {
        val path = selectedPath
        if (path == ROOT_PATH) return@LaunchedEffect
        val entry = files.firstOrNull { it.relativePath == path && !it.isDirectory } ?: return@LaunchedEffect
        snapshotFlow { editorContent }
            .drop(1)
            .distinctUntilChanged()
            .debounce(AUTO_SAVE_DEBOUNCE_MS)
            .collect { pending ->
                if (pending == savedContent) return@collect
                saveState = SaveState.Saving
                val toWrite = pending
                val result = withContext(Dispatchers.IO) {
                    repository.writeUserFile(skill.name, entry.relativePath, toWrite)
                }
                result.onSuccess {
                    savedContent = toWrite
                    saveState = SaveState.Saved
                    onFilesChanged()
                }.onFailure {
                    saveState = SaveState.Idle
                    statusMessage = it.message
                }
            }
    }

    // Derive the folder in which "new" actions should place items.
    val creationParent: String = remember(selectedPath, files) {
        when {
            selectedPath == ROOT_PATH -> ROOT_PATH
            files.any { it.relativePath == selectedPath && it.isDirectory } ->
                selectedPath.trimEnd('/')
            else -> {
                val trimmed = selectedPath.trimEnd('/')
                val idx = trimmed.lastIndexOf('/')
                if (idx < 0) ROOT_PATH else trimmed.substring(0, idx)
            }
        }
    }
    val displayParent = if (creationParent.isEmpty()) "/" else "/$creationParent/"

    val isRootSelected = selectedPath == ROOT_PATH
    val isSkillMdSelected = selectedPath == "SKILL.md"
    // Whether the current selection is a folder (root is handled separately).
    val selectedIsDirectory = !isRootSelected &&
        files.any { it.relativePath.trimEnd('/') == selectedPath.trimEnd('/') && it.isDirectory }
    // Delete is disabled for SKILL.md; for root it deletes the whole skill.
    val deleteEnabled = !isSkillMdSelected
    // Rename is disabled for root (that's the skill itself) and SKILL.md.
    val renameEnabled = !isRootSelected && !isSkillMdSelected
    val visibleRows = remember(files, expanded.toMap()) { flattenTree(files, expanded) }

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
                        .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(skill.name, fontWeight = FontWeight.Bold)
                            if (skill.invalid) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.skills_unavailable_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.skills_directory_title),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (skill.invalid && skill.invalidReason != null) {
                            Text(
                                skill.invalidReason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.skills_close)
                        )
                    }
                }

                // Action bar: acts on the selected item.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(R.string.skills_selected_path, displayParent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { showNewFileDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.markdown_24),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.skills_new_file))
                        }
                        FilledTonalButton(
                            onClick = { showNewFolderDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.folder_24),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.skills_new_folder))
                        }
                        OutlinedButton(
                            enabled = renameEnabled,
                            onClick = { showRenameDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.skills_rename))
                        }
                        OutlinedButton(
                            enabled = deleteEnabled,
                            onClick = { showDeleteConfirm = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                when {
                                    isRootSelected -> stringResource(R.string.skills_delete)
                                    selectedIsDirectory -> stringResource(R.string.skills_delete_folder)
                                    else -> stringResource(R.string.skills_delete_file)
                                }
                            )
                        }
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
                        // Tree pane: scrolls both vertically and horizontally so deep or
                        // long directory names never get clipped.
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp)
                        ) {
                            // Skill root node — depth 0, always visible.
                            SkillTreeRow(
                                label = skill.name,
                                isDirectory = true,
                                isRoot = true,
                                depth = 0,
                                isExpanded = expanded[ROOT_PATH] == true,
                                selected = isRootSelected,
                                lineFlags = LineFlags(hasParentLines = emptyList(), isLast = true),
                                onClick = {
                                    selectedPath = ROOT_PATH
                                },
                                onToggle = {
                                    expanded[ROOT_PATH] = expanded[ROOT_PATH] != true
                                }
                            )
                            if (expanded[ROOT_PATH] == true) {
                                visibleRows.forEach { row ->
                                    val path = row.entry.relativePath
                                    val pathKey = path.trimEnd('/')
                                    SkillTreeRow(
                                        label = row.entry.displayName.trimEnd('/'),
                                        isDirectory = row.entry.isDirectory,
                                        isRoot = false,
                                        depth = row.depth,
                                        isExpanded = expanded[pathKey] == true,
                                        selected = path == selectedPath,
                                        lineFlags = row.lineFlags,
                                        onClick = { selectedPath = path },
                                        onToggle = {
                                            if (row.entry.isDirectory) {
                                                // Directory entries end with "/", but
                                                // flattenTree() keys the expanded map by
                                                // the trimmed path — keep them in sync.
                                                expanded[pathKey] = expanded[pathKey] != true
                                            } else {
                                                selectedPath = path
                                            }
                                        }
                                    )
                                }
                                if (visibleRows.isEmpty()) {
                                    Text(
                                        stringResource(R.string.skills_directory_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = (INDENT_DP + 8).dp, top = 6.dp, bottom = 6.dp)
                                    )
                                }
                            }
                        }

                        VerticalDivider(modifier = Modifier.fillMaxHeight())

                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .padding(8.dp)
                        ) {
                            val fileEntry = files.firstOrNull { it.relativePath == selectedPath && !it.isDirectory }
                            if (fileEntry == null) {
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        fileEntry.relativePath,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(bottom = 8.dp)
                                    )
                                    Text(
                                        text = when (saveState) {
                                            SaveState.Saving -> stringResource(R.string.skills_saving)
                                            SaveState.Saved -> stringResource(R.string.skills_saved)
                                            SaveState.Idle -> ""
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedTextField(
                                    value = editorContent,
                                    onValueChange = { editorContent = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
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

    if (showDeleteConfirm) {
        val target = selectedPath
        val isRoot = target == ROOT_PATH
        val targetIsDirectory = !isRoot &&
            files.any { it.relativePath.trimEnd('/') == target.trimEnd('/') && it.isDirectory }
        val displayTarget = if (isRoot) skill.name else target.trimEnd('/')
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    when {
                        isRoot -> stringResource(R.string.skills_delete_confirm_title)
                        targetIsDirectory -> stringResource(R.string.skills_delete_folder_confirm_title)
                        else -> stringResource(R.string.skills_delete_file_confirm_title)
                    }
                )
            },
            text = {
                Text(
                    when {
                        isRoot -> stringResource(R.string.skills_delete_confirm_message, skill.name)
                        targetIsDirectory -> stringResource(R.string.skills_delete_folder_confirm_message, displayTarget)
                        else -> stringResource(R.string.skills_delete_file_confirm_message, displayTarget)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (isRoot) {
                        repository.deleteUserSkill(skill.name)
                            .onSuccess {
                                onSkillDeleted()
                                onDismiss()
                            }
                            .onFailure { statusMessage = it.message }
                    } else {
                        repository.deleteUserFile(skill.name, target)
                            .onSuccess {
                                selectedPath = ROOT_PATH
                                editorContent = ""
                                savedContent = ""
                                reloadFiles()
                                onFilesChanged()
                            }
                            .onFailure { statusMessage = it.message }
                    }
                }) {
                    Text(stringResource(R.string.skills_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.preset_cancel))
                }
            }
        )
    }

    if (showNewFileDialog) {
        SkillNameInputDialog(
            titleRes = R.string.skills_new_file,
            labelRes = R.string.skills_name_label,
            parentDisplay = displayParent,
            hint = "note.md",
            onDismiss = { showNewFileDialog = false },
            onConfirm = { rawName ->
                val name = rawName.trim().trim('/')
                if (name.isEmpty()) return@SkillNameInputDialog
                val leaf = if (name.endsWith(".md", ignoreCase = true)) name else "$name.md"
                val target = if (creationParent.isEmpty()) leaf else "$creationParent/$leaf"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        repository.writeUserFile(skill.name, target, "")
                    }
                    result.onSuccess {
                        showNewFileDialog = false
                        // Ensure the parent folder is expanded so the new file is visible.
                        if (creationParent.isNotEmpty()) expanded[creationParent] = true
                        expanded[ROOT_PATH] = true
                        reloadFiles()
                        selectedPath = target
                        onFilesChanged()
                    }.onFailure { statusMessage = it.message }
                }
            }
        )
    }

    if (showNewFolderDialog) {
        SkillNameInputDialog(
            titleRes = R.string.skills_new_folder,
            labelRes = R.string.skills_name_label,
            parentDisplay = displayParent,
            hint = "drafts",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { rawName ->
                val name = rawName.trim().trim('/')
                if (name.isEmpty()) return@SkillNameInputDialog
                val target = if (creationParent.isEmpty()) name else "$creationParent/$name"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        repository.createUserDirectory(skill.name, target)
                    }
                    result.onSuccess {
                        showNewFolderDialog = false
                        if (creationParent.isNotEmpty()) expanded[creationParent] = true
                        expanded[ROOT_PATH] = true
                        expanded[target] = true
                        reloadFiles()
                        selectedPath = target
                        onFilesChanged()
                    }.onFailure { statusMessage = it.message }
                }
            }
        )
    }

    if (showRenameDialog && renameEnabled) {
        val target = selectedPath
        val currentName = target.trimEnd('/').substringAfterLast('/')
        val trimmedTarget = target.trimEnd('/')
        val parentIdx = trimmedTarget.lastIndexOf('/')
        val renameParentDisplay = if (parentIdx < 0) "/" else "/${trimmedTarget.substring(0, parentIdx)}/"
        SkillNameInputDialog(
            titleRes = R.string.skills_rename_title,
            labelRes = R.string.skills_name_label,
            parentDisplay = renameParentDisplay,
            hint = currentName,
            initialName = currentName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { rawName ->
                val name = rawName.trim().trim('/')
                if (name.isEmpty() || name == currentName) return@SkillNameInputDialog
                // Keep the .md contract for files, mirroring the new-file flow.
                val finalName = if (!selectedIsDirectory && !name.endsWith(".md", ignoreCase = true)) "$name.md" else name
                val result = repository.renameUserEntry(skill.name, target, finalName)
                result.onSuccess { newPath ->
                    showRenameDialog = false
                    if (selectedIsDirectory) {
                        // Carry the expanded state over to the renamed key.
                        expanded[newPath.trimEnd('/')] = expanded[trimmedTarget] == true
                        expanded.remove(trimmedTarget)
                    }
                    reloadFiles()
                    selectedPath = newPath
                    onFilesChanged()
                }.onFailure { statusMessage = it.message }
            }
        )
    }
}

private enum class SaveState { Idle, Saving, Saved }

/**
 * hasParentLines[i] tells whether the ancestor at depth (i+1) still has a
 * following sibling — used to draw the vertical connector line at that column.
 * isLast marks the current row as the last child at its own depth.
 */
private data class LineFlags(val hasParentLines: List<Boolean>, val isLast: Boolean)

private data class SkillTreeRow(
    val entry: SkillFileEntry,
    val depth: Int,
    val lineFlags: LineFlags
)

/**
 * Turns the flat, pre-sorted list from SkillRepository into a filtered flat
 * list respecting the collapsed state, and computes the guide-line flags each
 * row needs to render its indent connectors.
 */
private fun flattenTree(
    all: List<SkillFileEntry>,
    expanded: Map<String, Boolean>
): List<SkillTreeRow> {
    if (all.isEmpty()) return emptyList()
    // Group entries by their parent path so we can walk depth-first and know
    // which sibling is last at each level.
    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx < 0) ROOT_PATH else trimmed.substring(0, idx)
    }
    val byParent: Map<String, List<SkillFileEntry>> = all
        .groupBy { parentOf(it.relativePath) }
        .mapValues { (_, list) ->
            list.sortedWith(compareBy<SkillFileEntry>({ !it.isDirectory }, { it.displayName.lowercase() }))
        }

    val out = mutableListOf<SkillTreeRow>()
    fun walk(parentPath: String, depth: Int, ancestorHasMore: List<Boolean>) {
        val children = byParent[parentPath] ?: return
        children.forEachIndexed { index, child ->
            val isLast = index == children.size - 1
            out += SkillTreeRow(
                entry = child,
                depth = depth,
                lineFlags = LineFlags(hasParentLines = ancestorHasMore, isLast = isLast)
            )
            if (child.isDirectory) {
                val childPath = child.relativePath.trimEnd('/')
                if (expanded[childPath] == true) {
                    walk(childPath, depth + 1, ancestorHasMore + !isLast)
                }
            }
        }
    }
    walk(ROOT_PATH, depth = 1, ancestorHasMore = emptyList())
    return out
}

@Composable
private fun SkillTreeRow(
    label: String,
    isDirectory: Boolean,
    isRoot: Boolean,
    depth: Int,
    isExpanded: Boolean,
    selected: Boolean,
    lineFlags: LineFlags,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .height(ROW_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (depth > 0) {
            // Guide lines for each ancestor column.
            Canvas(
                modifier = Modifier
                    .width((depth * INDENT_DP).dp)
                    .fillMaxHeight()
            ) {
                val stepPx = INDENT_DP.dp.toPx()
                val strokePx = 1.dp.toPx()
                val heightPx = size.height
                val midY = heightPx / 2f
                // Ancestor vertical lines (depth-1 columns; skip depth 0 which is the root).
                lineFlags.hasParentLines.forEachIndexed { i, hasMore ->
                    if (hasMore) {
                        val x = stepPx * (i + 1) + stepPx / 2f
                        drawLine(
                            color = lineColor,
                            start = Offset(x, 0f),
                            end = Offset(x, heightPx),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Butt
                        )
                    }
                }
                // Own connector at column (depth-1).
                val ownX = stepPx * (depth - 1) + stepPx / 2f
                // Vertical portion: full height if not last, half if last.
                drawLine(
                    color = lineColor,
                    start = Offset(ownX, 0f),
                    end = Offset(ownX, if (lineFlags.isLast) midY else heightPx),
                    strokeWidth = strokePx
                )
                // Horizontal tick to the row.
                drawLine(
                    color = lineColor,
                    start = Offset(ownX, midY),
                    end = Offset(ownX + stepPx * 0.6f, midY),
                    strokeWidth = strokePx
                )
            }
        }
        // Expand/collapse chevron (only shown for directories).
        Box(
            modifier = Modifier
                .size(20.dp)
                .clickable(enabled = isDirectory, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (isDirectory) {
                Icon(
                    painter = painterResource(
                        if (isExpanded) R.drawable.expand_more_24 else R.drawable.chevron_right_24
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            painter = painterResource(
                if (isDirectory) R.drawable.folder_24 else R.drawable.markdown_24
            ),
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .padding(end = 2.dp),
            tint = if (isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (isDirectory) FontFamily.Default else FontFamily.Monospace,
            fontWeight = if (selected || isRoot) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
private fun SkillNameInputDialog(
    titleRes: Int,
    labelRes: Int,
    parentDisplay: String,
    hint: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.skills_selected_path, parentDisplay),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(labelRes)) },
                    placeholder = { Text(hint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty() && !name.contains('/'),
                onClick = { onConfirm(name) }
            ) {
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
