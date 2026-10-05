@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nezumi_ai.presentation.ui.fragment

// ModelSettingsFragment から切り出したモデル設定ダイアログ。状態は Fragment の internal プロパティを読む。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.lifecycleScope
import com.nezumi_ai.MyApplication
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import com.nezumi_ai.data.inference.PromptTemplateStore
import com.nezumi_ai.utils.GgufMetadataReader
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.voicevox.VoicevoxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.nezumi_ai.data.inference.cloud.*
import com.nezumi_ai.presentation.ui.fragment.ModelSettingsFragment.CloudDialogState
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudModelId
import com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry
import com.nezumi_ai.data.inference.cloud.LocalModelListFetcher
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors

@Composable
internal fun ImportingDialog() {
    Dialog(onDismissRequest = {}) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SvgSpinner(
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(id = R.string.import_task_loading),
                    color = colorResource(id = R.color.text_primary)
                )
            }
        }
    }
}

/**
 * GGUF ヘッダーの全メタデータを Hugging Face の「Xet Pointer Details」のような
 * key / value テーブルとして表示するダイアログ。llama.cpp を経由せず、
 * GgufMetadataReader でヘッダーを直接パースして得た結果を表示する。
 */
@Composable
internal fun ModelSettingsFragment.GgufMetadataDialog() {
    val model = metadataDialogModel ?: return
    Dialog(onDismissRequest = {
        metadataDialogModel = null
        metadataDialogData = null
        metadataDialogError = null
    }) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(id = R.string.gguf_metadata_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = {
                        metadataDialogModel = null
                        metadataDialogData = null
                        metadataDialogError = null
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.common_close),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Text(
                    text = File(model.path).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Divider()

                val data = metadataDialogData
                val error = metadataDialogError
                when {
                    metadataDialogLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Text(
                            text = stringResource(id = R.string.gguf_metadata_read_error, error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    data != null -> {
                        // サマリー行（version / tensor_count / kv_count）。HF の表と同じ並び。
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            MetadataSummaryRow(stringResource(id = R.string.gguf_metadata_version), data.version.toString())
                            MetadataSummaryRow(stringResource(id = R.string.gguf_metadata_tensor_count), data.tensorCount.toString())
                            MetadataSummaryRow(stringResource(id = R.string.gguf_metadata_kv_count), data.kvCount.toString())
                        }
                        Divider()
                        val clipboard = LocalClipboardManager.current
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            itemsIndexed(data.entries) { index, (key, value) ->
                                val rowColor = if (index % 2 == 0) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowColor)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(0.45f)
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(0.55f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val copiedToastText = stringResource(id = R.string.gguf_metadata_copied_toast)
                        val versionLabel = stringResource(id = R.string.gguf_metadata_version)
                        val tensorCountLabel = stringResource(id = R.string.gguf_metadata_tensor_count)
                        val kvCountLabel = stringResource(id = R.string.gguf_metadata_kv_count)
                        TextButton(onClick = {
                            val text = buildString {
                                appendLine("$versionLabel\t${data.version}")
                                appendLine("$tensorCountLabel\t${data.tensorCount}")
                                appendLine("$kvCountLabel\t${data.kvCount}")
                                data.entries.forEach { (k, v) -> appendLine("$k\t$v") }
                            }
                            clipboard.setText(AnnotatedString(text))
                            toast(copiedToastText)
                        }) {
                            Text(stringResource(id = R.string.gguf_metadata_copy_all))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MetadataSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun ModelSettingsFragment.ImportedModelSettingsDialog(model: ModelFileManager.ImportedTaskModel) {
    val loweredPath = model.path.lowercase()
    val isGguf = loweredPath.endsWith(".gguf")
    val isLiteRt = loweredPath.endsWith(".litertlm") || loweredPath.endsWith(".task")
    val supportsToolCalling = isGguf || isLiteRt
    val dialogTitle = ImportedModelCapabilityStore.resolveDisplayName(
        requireContext(), model.path, model.shortDisplayName
    )
    Dialog(onDismissRequest = {
        // ダイアログを閉じる際に未保存値を即時 flush する。
        // 保存ボタンを廃止したため、回転中のデバウンス保存が未完了のまま
        // 閉じるシナリオを回避する。
        modelSettingsAutoSaveJob?.cancel()
        modelSettingsAutoSaveJob = null
        val pendingModel = modelSettingsDialogModel
        if (pendingModel != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                autoPersistModelSettingsFromDialog()
                refreshImportedTasks()
            }
        }
        modelSettingsDialogModel = null
    }) {
        LaunchedEffect(
            modelSettingsDialogModel,
            capabilityDialogTemplateMode,
            capabilityDialogTemplateCustom,
            capabilityDialogCachedAutoTemplate
        ) {
            val path = modelSettingsDialogModel?.path ?: return@LaunchedEffect
            val defined = dialogTemplateDeclaresThinking()
            capabilityDialogThinkingDefined = defined
            if (!defined) {
                // 非対応の間は UI 上だけ OFF。ストアは書き換えない (persistThinking = false)。
                capabilityDialogThinkingEnabled = false
            } else if (ImportedModelCapabilityStore.hasThinkingSetting(requireContext(), path)) {
                // 保存済みの ON/OFF を復元する (非対応 → 対応に戻したとき UI が OFF のまま残るのを防ぐ)。
                capabilityDialogThinkingEnabled =
                    ImportedModelCapabilityStore.get(requireContext(), path).thinkingEnabled
            } else if (!capabilityDialogThinkingEnabled) {
                capabilityDialogThinkingEnabled = true
            }
        }
        @OptIn(FlowPreview::class)
        LaunchedEffect(modelSettingsDialogModel) {
            // ダイアログ内の全値を snapshotFlow で監視し、350ms でデバウンスして自動保存する。
            snapshotFlow {
                // 取りこぼしないよう state を全て単一 String キーに封入して監視する。
                buildString {
                    append(capabilityDialogImageEnabled); append('|')
                    append(capabilityDialogAudioEnabled); append('|')
                    append(capabilityDialogThinkingEnabled); append('|')
                    append(capabilityDialogToolCallingEnabled); append('|')
                    append(capabilityDialogMmprojPath); append('|')
                    append(settingsDialogDisplayName); append('|')
                    append(settingsDialogStopTokens); append('|')
                    append(capabilityDialogTemplateMode); append('|')
                    append(capabilityDialogTemplateCustom)
                }
            }
                .filter { !modelSettingsAutoSaveSuspended }
                .distinctUntilChanged()
                .debounce(350)
                .collect { autoPersistModelSettingsFromDialog() }
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(id = R.string.model_settings_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isGguf) {
                            // GGUF ヘッダーの全メタデータを表で確認するボタン。
                            TextButton(onClick = {
                                metadataDialogModel = model
                                metadataDialogData = null
                                metadataDialogError = null
                                metadataDialogLoading = true
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            GgufMetadataReader.readFullMetadata(File(model.path))
                                        }
                                    }
                                    result.onSuccess { metadataDialogData = it }
                                    result.onFailure { e -> metadataDialogError = e.message ?: "" }
                                    metadataDialogLoading = false
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(id = R.string.gguf_metadata_button),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        // 保存ボタンを廃止し、クローズのみの X ボタン。
                        IconButton(onClick = {
                            modelSettingsAutoSaveJob?.cancel()
                            val pendingModel = modelSettingsDialogModel
                            if (pendingModel != null) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    autoPersistModelSettingsFromDialog()
                                    refreshImportedTasks()
                                }
                            }
                            modelSettingsDialogModel = null
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(id = R.string.common_close),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Text(
                    text = dialogTitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                model.hfRepoQualifier?.let { repo ->
                    Text(
                        text = "HF: $repo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Divider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(id = R.string.model_settings_enable_image_input), color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = capabilityDialogImageEnabled,
                        onCheckedChange = { capabilityDialogImageEnabled = it },
                        colors = nezumiSwitchColors()
                    )
                }
                if (isGguf && capabilityDialogImageEnabled) {
                    Text(
                        text = stringResource(
                            id = R.string.model_settings_mmproj_status,
                            if (capabilityDialogMmprojPath.isNotBlank()) java.io.File(capabilityDialogMmprojPath).name else stringResource(id = R.string.model_settings_mmproj_unselected)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    ExposedDropdownMenuBox(
                        expanded = mmprojDropdownExpanded,
                        onExpandedChange = { mmprojDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = if (capabilityDialogMmprojPath.isBlank()) stringResource(id = R.string.model_settings_mmproj_unselected) else java.io.File(capabilityDialogMmprojPath).name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(id = R.string.model_settings_mmproj_file_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = mmprojDropdownExpanded)
                            },
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = mmprojDropdownExpanded,
                            onDismissRequest = { mmprojDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.model_settings_mmproj_unselected)) },
                                onClick = {
                                    capabilityDialogMmprojPath = ""
                                    mmprojDropdownExpanded = false
                                }
                            )
                            if (capabilityDialogRepoMmprojLoading) {
                                DropdownMenuItem(text = { Text(stringResource(id = R.string.model_settings_mmproj_loading_candidates)) }, onClick = {})
                            } else {
                                val repoQualifier = model.hfRepoQualifier
                                val localRepoMmprojTasks = if (repoQualifier != null) {
                                    importedMmprojTasks.filter { it.hfRepoQualifier == repoQualifier }
                                } else importedMmprojTasks
                                localRepoMmprojTasks.forEach { mmprojModel ->
                                    DropdownMenuItem(
                                        text = { Text(mmprojModel.shortDisplayName) },
                                        onClick = {
                                            capabilityDialogMmprojPath = mmprojModel.path
                                            mmprojDropdownExpanded = false
                                        }
                                    )
                                }
                                val localPaths = localRepoMmprojTasks.map { it.fileNameStem.substringAfter("__") }
                                capabilityDialogRepoMmprojCandidates.filter { candidate ->
                                    val candidateStem = candidate.path.replace('/', '_').replace(Regex("[^A-Za-z0-9._-]"), "_")
                                    localPaths.none { it == candidateStem || candidate.path.endsWith(it) }
                                }.forEach { candidate ->
                                    val localFile = model.hfRepoQualifier?.let {
                                        ModelFileManager.hfModelIdFromRepoQualifier(it)
                                    }?.let { hfId ->
                                        ModelFileManager.huggingFaceImportedFile(requireContext(), hfId, candidate.path)
                                    }
                                    if (localFile != null && localFile.isFile) return@forEach
                                    val label = candidate.path.substringAfterLast("/") +
                                        (candidate.sizeBytes?.let { " (${it / 1024 / 1024}MB, 未DL)" } ?: " (未DL)")
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            mmprojDropdownExpanded = false
                                            val hfModelId = model.hfRepoQualifier?.let {
                                                ModelFileManager.hfModelIdFromRepoQualifier(it)
                                            }
                                            if (hfModelId != null) {
                                                val enqueued = ModelDownloadWorker.enqueueCustomHf(
                                                    requireContext(), hfModelId, candidate.path
                                                )
                                                if (enqueued) {
                                                    toast("mmproj のダウンロードを開始しました: ${candidate.path.substringAfterLast("/")}")
                                                } else if (ModelFileManager.huggingFaceImportedFile(
                                                        requireContext(), hfModelId, candidate.path
                                                    ).isFile
                                                ) {
                                                    capabilityDialogMmprojPath =
                                                        ModelFileManager.huggingFaceImportedFile(
                                                            requireContext(), hfModelId, candidate.path
                                                        ).absolutePath
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(id = R.string.model_settings_enable_audio_input), color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = capabilityDialogAudioEnabled,
                        onCheckedChange = { capabilityDialogAudioEnabled = it },
                        colors = nezumiSwitchColors()
                    )
                }
                // Thinking トグルはプロンプトテンプレートが Thinking を定義しているときだけ出す。
                if (capabilityDialogThinkingDefined) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(id = R.string.model_settings_enable_thinking), color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = capabilityDialogThinkingEnabled,
                            onCheckedChange = { capabilityDialogThinkingEnabled = it },
                            colors = nezumiSwitchColors()
                        )
                    }
                }
                if (supportsToolCalling) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(id = R.string.model_settings_tool_calling_enable), color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = if (isLiteRt) stringResource(id = R.string.model_settings_tool_calling_support_litertlm) else stringResource(id = R.string.model_settings_tool_calling_support_gguf),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = capabilityDialogToolCallingEnabled,
                            onCheckedChange = { capabilityDialogToolCallingEnabled = it },
                            colors = nezumiSwitchColors()
                        )
                    }
                }
                Divider()
                Text(
                    text = stringResource(id = R.string.model_settings_display_name_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settingsDialogDisplayName,
                    onValueChange = { settingsDialogDisplayName = it },
                    label = { Text(stringResource(id = R.string.model_settings_display_name_label)) },
                    singleLine = true
                )
                Text(
                    text = stringResource(id = R.string.model_settings_forbidden_filename_chars),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isGguf) {
                    Divider()
                    Text(
                        text = stringResource(id = R.string.model_settings_chat_template_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(id = R.string.model_settings_chat_template_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 要望対応: テンプレート選択は「GGUF メタデータのテンプレート」か
                    // 「手動入力」の二択に絞る。旧ビルトイン選択 (llama3 / chatml 等) は
                    // ダイアログを開いた時点で MODE_AUTO (= GGUF メタデータ) に読み替える。
                    val ggufTemplateLabel = stringResource(id = R.string.model_settings_template_gguf_metadata)
                    val customLabel = stringResource(id = R.string.model_settings_template_custom)
                    val templateOptions = remember(ggufTemplateLabel, customLabel) {
                        listOf(
                            PromptTemplateStore.MODE_AUTO to ggufTemplateLabel,
                            PromptTemplateStore.MODE_CUSTOM to customLabel
                        )
                    }
                    val currentLabel = templateOptions.firstOrNull { it.first == capabilityDialogTemplateMode }?.second
                        ?: ggufTemplateLabel
                    ExposedDropdownMenuBox(
                        expanded = capabilityDialogTemplateExpanded,
                        onExpandedChange = { capabilityDialogTemplateExpanded = it }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(id = R.string.model_settings_chat_template_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = capabilityDialogTemplateExpanded)
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = capabilityDialogTemplateExpanded,
                            onDismissRequest = { capabilityDialogTemplateExpanded = false }
                        ) {
                            templateOptions.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        val previousMode = capabilityDialogTemplateMode
                                        capabilityDialogTemplateMode = id
                                        capabilityDialogTemplateExpanded = false
                                        capabilityDialogTemplateError = null
                                        when {
                                            // 要望: 手動に切り替えたときは GGUF メタデータの
                                            // テンプレートを自動入力する (編集の出発点)。
                                            id == PromptTemplateStore.MODE_CUSTOM &&
                                                previousMode != PromptTemplateStore.MODE_CUSTOM -> {
                                                val ggufTemplate = runCatching {
                                                    GgufMetadataReader.readChatTemplate(
                                                        File(modelSettingsDialogModel?.path.orEmpty())
                                                    )
                                                }.getOrNull()
                                                if (!ggufTemplate.isNullOrBlank()) {
                                                    capabilityDialogTemplateCustom = ggufTemplate
                                                } else if (capabilityDialogTemplateCustom.isBlank()) {
                                                    capabilityDialogTemplateCustom =
                                                        PromptTemplateStore.BUILTIN_TEMPLATES
                                                            .firstOrNull { it.id == previousMode }?.template
                                                            ?: ""
                                                }
                                            }
                                            // 要望: GGUF メタデータへ戻したら手動テンプレートは削除する。
                                            id == PromptTemplateStore.MODE_AUTO -> {
                                                capabilityDialogTemplateCustom = ""
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    // ビルトイン選択時は説明を表示
                    PromptTemplateStore.BUILTIN_TEMPLATES.firstOrNull { it.id == capabilityDialogTemplateMode }?.let { b ->
                        Text(
                            text = b.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (capabilityDialogTemplateMode == PromptTemplateStore.MODE_CUSTOM) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = capabilityDialogTemplateCustom,
                            onValueChange = {
                                capabilityDialogTemplateCustom = it
                                capabilityDialogTemplateError = null
                            },
                            label = { Text(stringResource(id = R.string.model_settings_custom_template_label)) },
                            placeholder = { Text("{% for message in messages %}...{% endfor %}{% if add_generation_prompt %}...{% endif %}") },
                            minLines = 5,
                            isError = capabilityDialogTemplateError != null
                        )
                        Text(
                            text = stringResource(id = R.string.model_settings_template_variables),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(id = R.string.model_settings_template_jinja_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        capabilityDialogTemplateError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Divider()
                Text(
                    text = stringResource(id = R.string.model_settings_stop_tokens_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settingsDialogStopTokens,
                    onValueChange = { settingsDialogStopTokens = it },
                    label = { Text(stringResource(id = R.string.model_settings_additional_stop_tokens_label)) },
                    placeholder = { Text("<|im_end|>,<|im_start|>") },
                    minLines = 2
                )
                Text(
                    text = stringResource(id = R.string.model_settings_stop_tokens_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // NOTE: 保存 / キャンセル ボタン行は完全に廃止。
                // 入力は LaunchedEffect のデバウンス保存で自動反映され、
                // ダイアログの X / 外側タップで flush される。
            }
        }
    }

    if (showToolCallingDisableConfirmDialog && toolCallingDisableConfirmModel != null && toolCallingDisableConfirmNewCapabilities != null) {
        AlertDialog(
            onDismissRequest = { showToolCallingDisableConfirmDialog = false },
            title = { Text(stringResource(id = R.string.model_tool_calling_disable_confirm_title)) },
            text = {
                Text(stringResource(id = R.string.model_tool_calling_disable_confirm_message, toolCallingDisableConflictCount))
            },
            confirmButton = {
                Button(onClick = {
                    val modelForConfirm = toolCallingDisableConfirmModel ?: return@Button
                    val newCapabilitiesForConfirm = toolCallingDisableConfirmNewCapabilities ?: return@Button
                    val tokensForConfirm = toolCallingDisableConfirmTokens
                    toolCallingDisableConfirmModel = null
                    toolCallingDisableConfirmNewCapabilities = null
                    toolCallingDisableConfirmTokens = emptyList()
                    showToolCallingDisableConfirmDialog = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            presetRepository.disableToolCallingForPresetsUsingModel(modelForConfirm.path)
                        }
                        persistModelSettings(modelForConfirm, newCapabilitiesForConfirm, isGguf, tokensForConfirm)
                    }
                }) { Text(stringResource(id = R.string.common_yes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    // 確認キャンセル時: トグルを元に戻して自動保存のリトライを回避する。
                    modelSettingsAutoSaveSuspended = true
                    capabilityDialogToolCallingEnabled = true
                    toolCallingDisableConfirmModel = null
                    toolCallingDisableConfirmNewCapabilities = null
                    toolCallingDisableConfirmTokens = emptyList()
                    showToolCallingDisableConfirmDialog = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(500)
                        modelSettingsAutoSaveSuspended = false
                    }
                }) { Text("キャンセル") }
            }
        )
    }
}

/**
 * 追加済みモデル一覧に並べるクラウドモデル 1 件分の行。
 * タップで編集モーダル、削除ボタンで登録解除。
 */
@Composable
internal fun ModelSettingsFragment.CloudModelListItem(modelId: String) {
    val context = requireContext()
    val parsed = CloudModelId.parse(modelId)
    val configured = CloudUserModelRegistry.isConfiguredForContext(context, modelId)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val overrideKey = CloudUserModelRegistry.getOverrideApiKeyForContext(context, modelId)
                val overrideUrl = CloudUserModelRegistry.getOverrideBaseUrlForContext(context, modelId)
                cloudDialogState = CloudDialogState(
                    editingModelId = modelId,
                    provider = parsed?.provider ?: CloudApiKeyStore.Provider.LM_STUDIO,
                    modelName = parsed?.modelName ?: "",
                    apiKey = overrideKey,
                    baseUrl = overrideUrl
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = colorResource(id = R.color.surface_card)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = CloudModelId.displayLabel(modelId),
                    fontWeight = FontWeight.SemiBold,
                    color = colorResource(id = R.color.text_primary)
                )
                ModelQuantTags(
                    nameSource = parsed?.modelName ?: modelId,
                    extraTags = listOf(stringResource(id = R.string.model_tag_cloud))
                )
                Text(
                    text = stringResource(
                        id = if (configured) R.string.cloud_models_status_configured
                        else R.string.cloud_models_status_missing
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorResource(
                        id = if (configured) R.color.primary else R.color.text_secondary
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(
                onClick = {
                    CloudUserModelRegistry.removeForContext(context, modelId)
                    cloudModelsRevision++
                }
            ) {
                Text(
                    stringResource(id = R.string.cloud_models_remove_button),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun ModelQuantTags(nameSource: String, extraTags: List<String> = emptyList()) {
    val meta = modelNameMeta(nameSource)
    val tags = (listOf(meta.size, meta.quant) + extraTags).filter { it.isNotBlank() }.distinct()
    if (tags.isEmpty()) return
    val sub = colorResource(id = R.color.text_secondary)
    Row(
        modifier = Modifier.padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        tags.forEach { tag ->
            Text(
                text = tag,
                color = sub,
                fontSize = 11.sp,
                modifier = Modifier
                    .border(1.dp, sub.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

/** クラウドモデル追加/編集ダイアログを常にコンポジションに載せる（ModelScreen 先頭から呼ぶ）。 */
@Composable
internal fun ModelSettingsFragment.CloudModelDialogHost() {
    val context = requireContext()
    cloudDialogState?.let { dialogState ->
        CloudModelDialog(
            state = dialogState,
            onDismiss = { cloudDialogState = null },
            onSave = { saved ->
                val isNew = saved.editingModelId == null
                val modelId = saved.editingModelId
                    ?: CloudModelId.build(saved.provider, saved.modelName)

                // 編集でプロバイダ/モデル名が変わった場合は古い登録を消して新しい ID で登録する。
                if (!isNew && saved.editingModelId != modelId) {
                    CloudUserModelRegistry.removeForContext(context, saved.editingModelId)
                }
                CloudUserModelRegistry.addForContext(context, modelId)
                CloudUserModelRegistry.saveOverrideForContext(context, modelId, saved.apiKey, saved.baseUrl)

                toast(getString(R.string.cloud_models_credentials_saved))
                cloudModelsRevision++
                cloudDialogState = null
            }
        )
    }
}

/**
 * クラウドモデルの追加/編集モーダル。
 *
 * - プロバイダーはドロップダウンで選択。選んだプロバイダーに応じて入力項目を出し分ける。
 * - ローカル系 (LM Studio / Ollama ローカル) はモデル選択と URL のみ (API キー不要)。
 *   モデル名は `/v1/models` から取得した一覧をドロップダウンで選ぶ。
 * - クラウド系 (Ollama クラウドを含む) はモデル名を自由入力。API キーとアクセスポイントを設定できる。
 *   Ollama クラウドはモデル一覧を `/api/tags` から API キー付きで取得できる。
 */
@Composable
internal fun ModelSettingsFragment.CloudModelDialog(
    state: CloudDialogState,
    onDismiss: () -> Unit,
    onSave: (CloudDialogState) -> Unit
) {
    // Ollama クラウド (旧称リモート) はリモートサーバーではなく ollama.com の
    // クラウドサービスなので、他のクラウドプロバイダと同じく API キー必須として扱う。
    val isLocalProvider = state.provider == CloudApiKeyStore.Provider.LM_STUDIO ||
        state.provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL
    val requiresApiKey = state.provider.requiresApiKey

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    id = if (state.editingModelId == null) R.string.cloud_models_add_dialog_title_add
                    else R.string.cloud_models_add_dialog_title_edit
                )
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // プロバイダー選択
                Column {
                    Text(
                        text = stringResource(id = R.string.cloud_models_provider_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = state.providerDropdownExpanded,
                        onExpandedChange = {
                            cloudDialogState = state.copy(providerDropdownExpanded = it)
                        }
                    ) {
                        OutlinedTextField(
                            value = providerLabel(state.provider),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = state.providerDropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = state.providerDropdownExpanded,
                            onDismissRequest = {
                                cloudDialogState = state.copy(providerDropdownExpanded = false)
                            }
                        ) {
                            CloudApiKeyStore.Provider.values().forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(providerLabel(provider)) },
                                    onClick = {
                                        cloudDialogState = state.copy(
                                            provider = provider,
                                            providerDropdownExpanded = false,
                                            fetchedModels = emptyList(),
                                            modelName = ""
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Base URL (ローカル系は URL のみ。クラウド系もアクセスポイント変更可)
                Column {
                    Text(
                        text = stringResource(id = R.string.cloud_models_base_url_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = { cloudDialogState = state.copy(baseUrl = it) },
                        placeholder = {
                            Text(
                                text = state.provider.defaultBaseUrl
                                    ?: stringResource(id = R.string.cloud_models_base_url_required_hint)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.provider.defaultBaseUrl?.let { defaultBaseUrl ->
                        Text(
                            text = stringResource(
                                id = R.string.cloud_models_base_url_default_hint,
                                defaultBaseUrl
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                }

                // API キー (クラウド系のみ必須。ローカル系は任意)
                if (requiresApiKey || !isLocalProvider) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.cloud_models_api_key_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = { cloudDialogState = state.copy(apiKey = it) },
                            placeholder = {
                                Text(stringResource(id = R.string.cloud_models_api_key_placeholder))
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // モデル名
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(id = R.string.cloud_models_model_name_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                        if (isLocalProvider || state.provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE) {
                            TextButton(
                                onClick = {
                                    cloudDialogState = state.copy(fetchingModels = true)
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val baseUrl = state.baseUrl.ifBlank {
                                            state.provider.defaultBaseUrl.orEmpty()
                                        }
                                        val models = LocalModelListFetcher.fetch(
                                            provider = state.provider,
                                            baseUrl = baseUrl,
                                            apiKey = state.apiKey
                                        )
                                        cloudDialogState = cloudDialogState?.copy(
                                            fetchedModels = models,
                                            fetchingModels = false,
                                            errorMessage = if (models.isEmpty()) {
                                                getString(R.string.cloud_models_fetch_models_empty)
                                            } else null
                                        )
                                    }
                                }
                            ) {
                                Text(
                                    text = stringResource(
                                        id = if (state.fetchingModels) R.string.cloud_models_fetch_models_loading
                                        else R.string.cloud_models_fetch_models
                                    ),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    val canPickFromFetched = isLocalProvider || state.provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE
                    if (canPickFromFetched && state.fetchedModels.isNotEmpty()) {
                        // 取得できた一覧をドロップダウンで選ぶ
                        ExposedDropdownMenuBox(
                            expanded = state.modelDropdownExpanded,
                            onExpandedChange = {
                                cloudDialogState = state.copy(modelDropdownExpanded = it)
                            }
                        ) {
                            OutlinedTextField(
                                value = state.modelName,
                                onValueChange = { cloudDialogState = state.copy(modelName = it) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = state.modelDropdownExpanded)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = state.modelDropdownExpanded,
                                onDismissRequest = {
                                    cloudDialogState = state.copy(modelDropdownExpanded = false)
                                }
                            ) {
                                state.fetchedModels.forEach { modelName ->
                                    DropdownMenuItem(
                                        text = { Text(modelName) },
                                        onClick = {
                                            cloudDialogState = state.copy(
                                                modelName = modelName,
                                                modelDropdownExpanded = false
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = state.modelName,
                            onValueChange = { cloudDialogState = state.copy(modelName = it) },
                            placeholder = {
                                Text(modelNameHint(state.provider))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // エラーメッセージ
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.error)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val modelName = state.modelName.trim()
                    if (modelName.isEmpty()) {
                        cloudDialogState = state.copy(
                            errorMessage = getString(R.string.cloud_models_add_failed_blank)
                        )
                        return@Button
                    }
                    if (requiresApiKey && state.apiKey.isBlank()) {
                        cloudDialogState = state.copy(
                            errorMessage = getString(R.string.cloud_models_add_failed_not_configured)
                        )
                        return@Button
                    }
                    val resolvedUrl = state.baseUrl.ifBlank { state.provider.defaultBaseUrl.orEmpty() }
                    if ((isLocalProvider || state.provider.defaultBaseUrl == null) && !(resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))) {
                        cloudDialogState = state.copy(
                            errorMessage = getString(R.string.cloud_models_base_url_required_hint)
                        )
                        return@Button
                    }
                    onSave(state.copy(modelName = modelName, baseUrl = resolvedUrl))
                }
            ) {
                Text(
                    stringResource(
                        id = if (state.editingModelId == null) R.string.cloud_models_add_button
                        else R.string.cloud_models_save_button
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.cancel))
            }
        }
    )
}

internal fun ModelSettingsFragment.providerLabel(provider: CloudApiKeyStore.Provider): String = when (provider) {
    CloudApiKeyStore.Provider.CLAUDE -> getString(R.string.cloud_models_provider_claude)
    CloudApiKeyStore.Provider.GEMINI -> getString(R.string.cloud_models_provider_gemini)
    CloudApiKeyStore.Provider.OPENAI -> getString(R.string.cloud_models_provider_openai)
    CloudApiKeyStore.Provider.OLLAMA_LOCAL -> getString(R.string.cloud_models_provider_ollama_local)
    CloudApiKeyStore.Provider.OLLAMA_REMOTE -> getString(R.string.cloud_models_provider_ollama_remote)
    CloudApiKeyStore.Provider.LM_STUDIO -> getString(R.string.cloud_models_provider_lmstudio)
}

internal fun ModelSettingsFragment.modelNameHint(provider: CloudApiKeyStore.Provider): String = when (provider) {
    CloudApiKeyStore.Provider.CLAUDE -> getString(R.string.cloud_models_model_name_hint_claude)
    CloudApiKeyStore.Provider.GEMINI -> getString(R.string.cloud_models_model_name_hint_gemini)
    CloudApiKeyStore.Provider.OPENAI -> getString(R.string.cloud_models_model_name_hint_openai)
    CloudApiKeyStore.Provider.OLLAMA_LOCAL,
    CloudApiKeyStore.Provider.OLLAMA_REMOTE -> getString(R.string.cloud_models_model_name_hint_ollama)
    CloudApiKeyStore.Provider.LM_STUDIO -> getString(R.string.cloud_models_model_name_hint_lmstudio)
}

@Composable
internal fun ModelSettingsFragment.ImageModelLicenseConfirmDialog() {
    val model = imageLicensePendingModel ?: return
    val info = imageLicenseInfo
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = { dismissImageLicenseDialog() },
        title = { Text("画像生成モデルのライセンス確認") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "「${model.displayName}」をダウンロードします。このモデルは元モデルの配布ライセンス（例: CreativeML Open RAIL-M 等）に従い、商用利用の可否や生成物の用途制限（未成年者の性的搾取、偽情報生成、嫌がらせ、差別的表現などの禁止を含む場合があります）が定められています。内容を確認してから同意してください。",
                    style = MaterialTheme.typography.bodySmall
                )
                when {
                    imageLicenseLoading -> {
                        Text("ライセンス情報を取得しています...", style = MaterialTheme.typography.bodySmall)
                    }
                    info == null -> {
                        Text("ライセンス情報を取得できませんでした。", style = MaterialTheme.typography.bodySmall)
                    }
                    !info.found -> {
                        Text(
                            text = "このモデルのライセンスファイル（LICENSE.md / README.md）を自動取得できませんでした。" +
                                "ダウンロード前に必ずHuggingFaceのモデルページで利用条件をご確認ください。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { uriHandler.openUri(info.repoUrl) }) {
                            Text("HuggingFaceでモデルページを開く")
                        }
                    }
                    else -> {
                        info.licenseId?.let { lic ->
                            Text("ライセンス種別: $lic", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        val sourceLabel = when (info.source) {
                            com.nezumi_ai.data.inference.ImageModelLicenseSource.LICENSE_FILE -> "LICENSE.md より取得"
                            com.nezumi_ai.data.inference.ImageModelLicenseSource.README -> "README.md より取得"
                            else -> null
                        }
                        sourceLabel?.let {
                            Text(it, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.labelSmall)
                        }
                        info.bodyText?.let { body ->
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        TextButton(onClick = { uriHandler.openUri(info.repoUrl) }) {
                            Text("HuggingFaceで全文を開く")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirmImageModelDownload() },
                enabled = !imageLicenseLoading
            ) {
                Text("同意してダウンロード")
            }
        },
        dismissButton = {
            TextButton(onClick = { dismissImageLicenseDialog() }) {
                Text("キャンセル")
            }
        }
    )
}

@Composable
internal fun ModelSettingsFragment.VoicevoxLicenseConfirmDialog() {
    val styleId = voicevoxLicensePendingStyleId ?: return
    val style = com.nezumi_ai.voicevox.VoicevoxManager.allStyles.firstOrNull { it.styleId == styleId }
    val uriHandler = LocalUriHandler.current

    fun dismiss() { voicevoxLicensePendingStyleId = null }

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("音声ライブラリのライセンス確認") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "「${style?.detailName ?: "選択した声"}」の音声モデルをダウンロードします。" +
                        "生成音声を利用する際は VOICEVOX 本体および話者ごとのクレジット表記・利用規約の遵守が必要です。",
                    style = MaterialTheme.typography.bodySmall
                )
                val license = style?.license
                if (license == null) {
                    Text(
                        text = "この話者のライセンス情報を確認できませんでした。ダウンロード前に VOICEVOX 公式サイトで規約をご確認ください。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { uriHandler.openUri(com.nezumi_ai.voicevox.VoicevoxLicense.VOICEVOX_TERMS_URL) }) {
                        Text("VOICEVOX利用規約を開く")
                    }
                } else {
                    Text("クレジット表記: ${license.credit}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("商用利用: ${license.commercialLabel}", style = MaterialTheme.typography.bodySmall)
                    license.note?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (license.termsUrl.isNotBlank()) {
                        TextButton(onClick = { uriHandler.openUri(license.termsUrl) }) {
                            Text("この話者の利用規約を開く")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val app = requireContext().applicationContext as MyApplication
                app.selectVoicevoxStyle(styleId)
                refreshVoicevoxState()
                dismiss()
            }) {
                Text("同意してダウンロード")
            }
        },
        dismissButton = {
            TextButton(onClick = { dismiss() }) {
                Text("キャンセル")
            }
        }
    )
}
