package com.nezumi_ai.presentation.ui.fragment

// SettingsComposeFragment から切り出したスキル設定カード。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nezumi_ai.R
import com.nezumi_ai.data.skill.SkillRepository
import com.nezumi_ai.data.skill.SkillScanResult
import com.nezumi_ai.presentation.ui.component.SkillDirectoryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsComposeFragment.SkillManagementCard(
    result: SkillScanResult,
    onImport: () -> Unit,
    settingsSearchJumpState: SettingsSearchJumpState
) {
    var creatingSkill by remember { mutableStateOf(false) }
    var browsingSkill by remember { mutableStateOf<com.nezumi_ai.data.skill.Skill?>(null) }
    var deletingSkill by remember { mutableStateOf<com.nezumi_ai.data.skill.Skill?>(null) }
    var renamingSkill by remember { mutableStateOf<com.nezumi_ai.data.skill.Skill?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.skills_settings_title),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.settingsSearchAnchor(
                    R.string.skills_settings_title,
                    settingsSearchJumpState
                )
            )
            Text(stringResource(R.string.skills_settings_description), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { creatingSkill = true }) { Text(stringResource(R.string.skills_create)) }
                TextButton(onClick = onImport) { Text(stringResource(R.string.skills_import_zip)) }
            }
            result.skills.forEach { skill ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 一覧では 16 文字を上限とし、超えたら末尾に "…" を付けて省略表示する。
                            // 詳細画面 (SkillDirectoryDialog) やリネームダイアログではフル名を扱う。
                            Text(truncateSkillName(skill.name))
                            if (skill.invalid) {
                                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.skills_unavailable_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        val subtitle = if (skill.invalid) skill.invalidReason.orEmpty() else skill.description
                        if (subtitle.isNotEmpty()) {
                            Text(
                                truncateSkillName(subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (skill.invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (skill.source == com.nezumi_ai.data.skill.Skill.Source.USER) {
                        TextButton(onClick = { browsingSkill = skill }) { Text(stringResource(R.string.skills_browse_files)) }
                        TextButton(onClick = { renamingSkill = skill }) { Text(stringResource(R.string.skills_rename)) }
                        TextButton(onClick = { deletingSkill = skill }) { Text(stringResource(R.string.skills_delete)) }
                    }
                }
            }
            if (result.skills.isEmpty()) Text(stringResource(R.string.skills_empty), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (creatingSkill) {
        SkillCreateDialog(
            onDismiss = { creatingSkill = false },
            onCreate = { name ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val repo = SkillRepository(requireContext())
                    val outcome = repo.createUserSkill(name)
                    withContext(Dispatchers.Main) {
                        skillScanResult = repo.scan(force = true)
                        val error = outcome.exceptionOrNull()
                        if (error != null) {
                            skillDialogMessage = error.message
                        } else {
                            browsingSkill = skillScanResult.skills.firstOrNull { it.name == name }
                        }
                        creatingSkill = false
                    }
                }
            }
        )
    }
    browsingSkill?.let { skill ->
        SkillDirectoryDialog(
            skill = skill,
            repository = SkillRepository(requireContext()),
            onDismiss = { browsingSkill = null },
            onSkillDeleted = {
                skillScanResult = SkillRepository(requireContext()).scan(force = true)
                browsingSkill = null
            },
            onFilesChanged = {
                skillScanResult = SkillRepository(requireContext()).scan(force = true)
            }
        )
    }
    renamingSkill?.let { skill ->
        SkillRenameDialog(
            currentName = skill.name,
            onDismiss = { renamingSkill = null },
            onRename = { newName ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val repo = SkillRepository(requireContext())
                    val outcome = repo.renameUserSkill(skill.name, newName)
                    withContext(Dispatchers.Main) {
                        skillScanResult = repo.scan(force = true)
                        outcome.exceptionOrNull()?.let { skillDialogMessage = it.message }
                        renamingSkill = null
                    }
                }
            }
        )
    }
    deletingSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { deletingSkill = null },
            title = { Text(stringResource(R.string.skills_delete_confirm_title)) },
            text = { Text(stringResource(R.string.skills_delete_confirm_message, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val outcome = SkillRepository(requireContext()).deleteUserSkill(skill.name)
                        withContext(Dispatchers.Main) {
                            skillScanResult = SkillRepository(requireContext()).scan(force = true)
                            skillDialogMessage = outcome.exceptionOrNull()?.message
                            deletingSkill = null
                        }
                    }
                }) {
                    Text(stringResource(R.string.skills_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSkill = null }) {
                    Text(stringResource(R.string.preset_cancel))
                }
            }
        )
    }
    skillDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { skillDialogMessage = null },
            title = { Text(stringResource(R.string.skills_directory_error_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { skillDialogMessage = null }) { Text(stringResource(android.R.string.ok)) } }
        )
    }
    skillInfoMessage?.let { message ->
        // 成功通知。従来はこの経路でも "エラー" タイトルの AlertDialog が
        // 出ていたので、専用ダイアログにタイトルを与えて意味を揃える。
        AlertDialog(
            onDismissRequest = { skillInfoMessage = null },
            title = { Text(stringResource(R.string.skills_settings_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { skillInfoMessage = null }) { Text(stringResource(android.R.string.ok)) } }
        )
    }
}

@Composable
internal fun SettingsComposeFragment.SkillCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skills_name)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = com.nezumi_ai.data.skill.SkillPathResolver.isValidName(name),
                onClick = { onCreate(name) }
            ) { Text(stringResource(R.string.preset_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.preset_cancel)) }
        }
    )
}

/**
 * ユーザースキルのフォルダ名 (= skill.name) を変更するダイアログ。
 * SkillPathResolver.isValidName と同じパターン ([a-z0-9-]{1,64}) でバリデーションし、
 * 現在の名前と同じ場合は確定ボタンを無効化する。
 */
@Composable
internal fun SkillRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skills_name)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = com.nezumi_ai.data.skill.SkillPathResolver.isValidName(name) && name != currentName,
                onClick = { onRename(name) }
            ) { Text(stringResource(R.string.preset_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.preset_cancel)) }
        }
    )
}

/**
 * スキル一覧上で長い名前や説明が UI を崩さないよう、
 * 16 文字を上限とし、超えた分は "…" で切り捨てて返す。
 * (コードポイント単位。スキル名は ASCII のみなので実質文字と一致するが、
 *  説明も同じ UI の一行に収めるため共通ヘルパーとして使う。)
 */
/**
 * Skill 一覧カードの名前行・説明行は 1 行に収めるため、ロケール依存の上限を超えたら
 * "…" で折り返す。リソースの preset_skill_description_max_chars を使うので、
 * JA=16 / EN=32 のようにロケールごとに値を切り替えられる。
 */
@Composable
internal fun truncateSkillName(source: String): String {
    val limit = androidx.compose.ui.res.integerResource(id = R.integer.preset_skill_description_max_chars)
    return if (source.length <= limit) source else source.take(limit) + "…"
}
