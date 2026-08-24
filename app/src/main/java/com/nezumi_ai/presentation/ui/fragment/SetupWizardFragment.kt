package com.nezumi_ai.presentation.ui.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.app.ActivityManager
import android.content.Context
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.RecommendedModelCatalog
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SetupWizardFragment : Fragment() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sessionRepository: ChatSessionRepository

    private var currentStep by mutableStateOf(0)
    private var selectedModel by mutableStateOf<String?>(null)
    private var embeddingDownloaded by mutableStateOf(false)
    private var embeddingDownloading by mutableStateOf(false)
    private var embeddingProgress by mutableStateOf("")
    private var preloadMemoryWarningThresholdPercent by mutableStateOf(MemoryObserver.DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT)
    private var pendingDownloadModel: ModelFileManager.LocalModel? = null
    private var isCompleting by mutableStateOf(false)

    private val modelStates = mutableStateMapOf<ModelFileManager.LocalModel, DownloadUiState>()
    
    // ダウンロード警告ダイアログ用
    private var showDownloadWarning by mutableStateOf(false)
    private var downloadWarningModelName by mutableStateOf("")
    private var downloadWarningModel: ModelFileManager.LocalModel? = null
    private var downloadWarningSystemMemInfo: MemoryObserver.SystemMemoryInfo? = null
    private var downloadWarningIsMemoryLow by mutableStateOf(false)
    private var downloadWarningIsStorageLow by mutableStateOf(false)
    private var downloadWarningAvailableStorageGB by mutableStateOf(0f)
    private var downloadWarningRequiredStorageGB by mutableStateOf(0f)
    
    // チャット開始時メモリ警告ダイアログ用
    private var showChatMemoryWarning by mutableStateOf(false)
    private var chatWarningModelName by mutableStateOf("")
    private var chatWarningSystemMemInfo: MemoryObserver.SystemMemoryInfo? = null
    private var pendingSkipModelSelection by mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            pendingDownloadModel?.let { enqueueModelDownload(it) }
            pendingDownloadModel = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository.fromDatabase(database)
        sessionRepository = ChatSessionRepository(
            database.chatSessionDao(),
            settingsRepository,
            MessageRepository(database.messageDao())
        )
        ModelFileManager.LocalModel.entries.forEach { model ->
            modelStates[model] = DownloadUiState(isDownloaded = false)
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { SetupWizardScreen() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshModelStatus()
        observeDownloadWork()
        embeddingDownloaded = MemoryTextEmbedder.hasEmbeddingFiles(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            selectedModel = normalizeModelSelection(settingsRepository.getSelectedModel())
            preloadMemoryWarningThresholdPercent = settingsRepository.getPreloadMemoryWarningThresholdPercent()
        }
    }

    @Composable
    private fun SetupWizardScreen() {
        val containerColor = colorResource(id = R.color.bg_session_list)
        val cardColor = colorResource(id = R.color.surface_card)
        val accent = colorResource(id = R.color.primary)
        val accentSoft = colorResource(id = R.color.nezumi_primary_container)
        val textPrimary = colorResource(id = R.color.text_primary)
        val textSecondary = colorResource(id = R.color.text_secondary)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(containerColor)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = stringResource(id = R.string.setup_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                }
                item {
                    StepHeader(
                        accent = accent,
                        accentSoft = accentSoft,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary
                    )
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (currentStep) {
                                0 -> WelcomeStep(textPrimary, textSecondary)
                                1 -> EmbeddingStep(accent, textPrimary, textSecondary)
                                else -> ModelStep(accent, textPrimary, textSecondary)
                            }
                        }
                    }
                }
            }
        }
        
        if (showDownloadWarning && downloadWarningModel != null) {
            DownloadWarningDialog(
                modelName = downloadWarningModelName,
                systemMemInfo = downloadWarningSystemMemInfo,
                isMemoryLow = downloadWarningIsMemoryLow,
                isStorageLow = downloadWarningIsStorageLow,
                availableStorageGB = downloadWarningAvailableStorageGB,
                requiredStorageGB = downloadWarningRequiredStorageGB,
                onDownload = {
                    showDownloadWarning = false
                    val enqueued = ModelDownloadWorker.enqueue(requireContext(), downloadWarningModel!!)
                    if (!enqueued) {
                        toast(requireContext().getString(R.string.setup_download_already_in_progress))
                    }
                },
                onCancel = {
                    showDownloadWarning = false
                }
            )
        }
        
        if (showChatMemoryWarning && chatWarningSystemMemInfo != null) {
            ChatMemoryWarningDialog(
                modelName = chatWarningModelName,
                systemMemInfo = chatWarningSystemMemInfo!!,
                onContinue = {
                    showChatMemoryWarning = false
                    executeCompleteSetup(pendingSkipModelSelection)
                },
                onCancel = {
                    showChatMemoryWarning = false
                    isCompleting = false
                }
            )
        }
    }
    
    @Composable
    private fun ChatMemoryWarningDialog(
        modelName: String,
        systemMemInfo: MemoryObserver.SystemMemoryInfo,
        onContinue: () -> Unit,
        onCancel: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onCancel,
            title = {
                Text(stringResource(id = R.string.chat_memory_warning_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(id = R.string.chat_memory_warning_body, modelName))
                    Divider()
                    Text(
                        text = stringResource(id = R.string.setup_memory_warning_device_memory_header),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = stringResource(
                            id = R.string.chat_memory_warning_device_info,
                            systemMemInfo.usedMemoryMB,
                            systemMemInfo.totalMemoryMB
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(id = R.string.chat_memory_warning_usage, systemMemInfo.usedPercent),
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    val statusColor = when {
                        systemMemInfo.usedPercent < 70 -> MaterialTheme.colorScheme.primary
                        systemMemInfo.usedPercent < 85 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }
                    
                    val statusText = when {
                        systemMemInfo.usedPercent < 70 -> stringResource(id = R.string.setup_status_normal)
                        systemMemInfo.usedPercent < 85 -> stringResource(id = R.string.setup_status_warning)
                        else -> stringResource(id = R.string.setup_status_danger)
                    }
                    
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            },
            confirmButton = {
                Button(onClick = onContinue) {
                    Text(stringResource(id = R.string.chat_memory_warning_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel) {
                    Text(stringResource(id = R.string.chat_memory_warning_cancel))
                }
            }
        )
    }

    @Composable
    private fun DownloadWarningDialog(
        modelName: String,
        systemMemInfo: MemoryObserver.SystemMemoryInfo?,
        isMemoryLow: Boolean,
        isStorageLow: Boolean,
        availableStorageGB: Float,
        requiredStorageGB: Float,
        onDownload: () -> Unit,
        onCancel: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onCancel,
            title = {
                Text(
                    stringResource(
                        id = when {
                            isMemoryLow && isStorageLow -> R.string.setup_warning_title_memory_storage
                            isMemoryLow -> R.string.setup_warning_title_memory
                            else -> R.string.setup_warning_title_storage
                        }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isMemoryLow && isStorageLow) {
                        Text(stringResource(id = R.string.setup_download_memory_storage_low, modelName))
                    } else if (isMemoryLow) {
                        Text(stringResource(id = R.string.setup_download_memory_low, modelName))
                    } else {
                        Text(stringResource(id = R.string.setup_download_storage_low, modelName))
                    }
                    
                    if (isMemoryLow && systemMemInfo != null) {
                        Divider()
                        Text(
                            text = stringResource(id = R.string.setup_memory_warning_device_memory_header),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = stringResource(
                                id = R.string.setup_memory_usage_format,
                                systemMemInfo.usedMemoryMB,
                                systemMemInfo.totalMemoryMB
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(id = R.string.setup_memory_percent_format, systemMemInfo.usedPercent),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = if (systemMemInfo.lowMemoryFlag) stringResource(id = R.string.setup_status_danger) else stringResource(id = R.string.setup_status_normal),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (systemMemInfo.lowMemoryFlag) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    if (isStorageLow) {
                        Divider()
                        Text(
                            text = stringResource(id = R.string.setup_storage_header),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = stringResource(id = R.string.setup_storage_available_format, String.format(Locale.US, "%.2f", availableStorageGB)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = stringResource(id = R.string.setup_storage_required_format, String.format(Locale.US, "%.2f", requiredStorageGB)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDownload,
                    enabled = !isStorageLow
                ) {
                    Text(if (isStorageLow) stringResource(id = R.string.setup_storage_full) else stringResource(id = R.string.setup_download_button))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel) {
                    Text(stringResource(id = R.string.setup_cancel))
                }
            }
        )
    }

    @Composable
    private fun StepHeader(
        accent: androidx.compose.ui.graphics.Color,
        accentSoft: androidx.compose.ui.graphics.Color,
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        val labels = listOf(
            stringResource(id = R.string.setup_step_welcome),
            stringResource(id = R.string.setup_step_memory),
            stringResource(id = R.string.setup_step_model)
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(id = R.string.setup_step_count_format, currentStep + 1, labels.size),
                color = textSecondary,
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                labels.forEachIndexed { index, label ->
                    val active = index == currentStep
                    val completed = index < currentStep
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                active -> accent
                                completed -> accentSoft
                                else -> accentSoft.copy(alpha = 0.45f)
                            }
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            color = if (active) colorResource(id = R.color.nezumi_on_primary) else textPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun WelcomeStep(
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_nezumi_ai),
                contentDescription = getString(R.string.app_name),
                modifier = Modifier.size(84.dp)
            )
        }
        Text(
            text = stringResource(id = R.string.setup_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = stringResource(id = R.string.setup_welcome_desc),
            color = textSecondary
        )
        Text(
            text = stringResource(id = R.string.setup_welcome_steps),
            color = textPrimary
        )
        Text(
            text = stringResource(id = R.string.setup_welcome_ok_later),
            color = textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.setup_welcome_history_info),
            color = textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(
            onClick = { 
                findNavController().navigate(R.id.settingsFragment)
            }
        ) {
            Text(
                text = stringResource(id = R.string.setup_welcome_change_in_settings),
                color = colorResource(id = R.color.primary),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = { currentStep = 1 }) {
                Text(stringResource(id = R.string.setup_start_button))
            }
        }
    }

    @Composable
    private fun ModelStep(
        accent: androidx.compose.ui.graphics.Color,
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        Text(
            text = stringResource(id = R.string.setup_model_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = stringResource(id = R.string.setup_model_desc),
            color = textSecondary
        )

        builtinModelOptions().forEach { option ->
            val state = modelStates[option.model] ?: DownloadUiState()
            val selected = selectedModel == option.settingValue
            val sizeBytes = getModelSizeBytes(option.model)
            val modelId = ModelFileManager.modelFileName(option.model)
            val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent, useAvailable = false, modelIdentifier = modelId)
            
            val resourceCheck = if (!state.isDownloaded) {
                ModelFileManager.checkDownloadResources(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent, modelIdentifier = modelId)
            } else {
                ModelFileManager.ResourceCheckResult(false, false, 0f, 0f, null)
            }
            val isStorageLow = resourceCheck.isStorageLow
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) accent else textSecondary.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .clickable { selectedModel = option.settingValue },
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.surface_card)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.title,
                                color = textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = option.subtitle,
                                color = textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = when {
                                    state.isDownloaded -> stringResource(id = R.string.setup_ready_status)
                                    state.isDownloading -> stringResource(id = R.string.setup_downloading_status)
                                    else -> stringResource(id = R.string.setup_not_acquired_status)
                                },
                                color = if (state.isDownloaded) accent else textSecondary
                            )
                            if (isMemoryLow) {
                                Text(
                                    text = stringResource(id = R.string.setup_memory_low),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (isStorageLow) {
                                Text(
                                    text = stringResource(id = R.string.setup_storage_low),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    if (state.isDownloading) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = state.progressText.ifBlank { stringResource(id = R.string.setup_downloading_text) },
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (selected && !state.isDownloaded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { requestNotificationPermissionForDownload(option.model) },
                                enabled = !state.isDownloading && !isStorageLow
                            ) {
                                Text(if (state.isDownloading) stringResource(id = R.string.setup_downloading_text) else if (isStorageLow) stringResource(id = R.string.setup_storage_full) else stringResource(id = R.string.setup_download_button))
                            }
                            TextButton(onClick = { selectedModel = null }) {
                                Text(stringResource(id = R.string.setup_deselect_button))
                            }
                        }
                    }
                }
            }
        }

        // おすすめ GGUF（RecommendedModelCatalog）。カード見た目は Gemma と同系。
        RecommendedModelCatalog.recommended()
            .filter { it.engine == RecommendedModelCatalog.Engine.GGUF }
            .forEach { entry ->
                val repo = entry.hfRepo ?: return@forEach
                val file = entry.hfFile ?: return@forEach
                val local = ModelFileManager.huggingFaceImportedFile(requireContext(), repo, file)
                val downloaded = local.isFile && local.length() > 0L
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = textSecondary.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(18.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = colorResource(id = R.color.surface_card)
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.displayName,
                                    color = textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = entry.shortDescription,
                                    color = textSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "llama.cpp",
                                    color = accent,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                text = if (downloaded) {
                                    stringResource(id = R.string.setup_ready_status)
                                } else {
                                    stringResource(id = R.string.setup_not_acquired_status)
                                },
                                color = if (downloaded) accent else textSecondary
                            )
                        }
                        if (!downloaded) {
                            Button(
                                onClick = {
                                    val ok = ModelDownloadWorker.enqueueCustomHf(requireContext(), repo, file)
                                    toast(
                                        if (ok) requireContext().getString(R.string.model_download_queued_named, entry.displayName)
                                        else requireContext().getString(R.string.model_download_already_running)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(id = R.string.setup_download_button))
                            }
                        }
                    }
                }
            }

        Text(
            text = stringResource(id = R.string.setup_skip_hint),
            color = textSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { currentStep = 1 }, enabled = !isCompleting) {
                Text(stringResource(id = R.string.setup_back_button))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { completeSetup(skipModelSelection = true) },
                    enabled = !isCompleting
                ) {
                    Text(stringResource(id = R.string.setup_skip_button))
                }
                Button(
                    onClick = { completeSetup(skipModelSelection = false) },
                    enabled = canFinishWithoutSkip() && !isCompleting
                ) {
                    if (isCompleting) SvgSpinner(modifier = Modifier.size(18.dp))
                    else Text(stringResource(id = R.string.setup_chat_button))
                }
            }
        }
    }

    @Composable
    private fun EmbeddingStep(
        accent: androidx.compose.ui.graphics.Color,
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        Text(
            text = stringResource(id = R.string.setup_embedding_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = stringResource(id = R.string.setup_embedding_desc),
            color = textSecondary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.surface_card)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "static-embedding-japanese",
                            color = textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = R.string.setup_embedding_model_subtitle),
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = when {
                            embeddingDownloaded -> stringResource(id = R.string.setup_ready_status)
                            embeddingDownloading -> stringResource(id = R.string.setup_downloading_status)
                            else -> stringResource(id = R.string.setup_not_acquired_status)
                        },
                        color = if (embeddingDownloaded) accent else textSecondary
                    )
                }
                if (embeddingDownloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (embeddingProgress.isNotBlank()) {
                        Text(
                            text = embeddingProgress,
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (!embeddingDownloaded) {
                    Button(
                        onClick = { startEmbeddingDownload() },
                        enabled = !embeddingDownloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (embeddingDownloading) stringResource(id = R.string.setup_downloading_text) else stringResource(id = R.string.setup_download_button))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { currentStep = 0 }, enabled = !isCompleting) {
                Text(stringResource(id = R.string.setup_back_button))
            }
            Button(
                onClick = { currentStep = 2 },
                enabled = embeddingDownloaded && !isCompleting
            ) {
                Text(stringResource(id = R.string.setup_next_button))
            }
        }
    }

    private fun requestNotificationPermissionForDownload(model: ModelFileManager.LocalModel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownloadModel = model
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        enqueueModelDownload(model)
    }

    private fun enqueueModelDownload(model: ModelFileManager.LocalModel) {
        val modelName = when (model) {
            ModelFileManager.LocalModel.GEMMA4_2B -> "Gemma 4 2B"
            ModelFileManager.LocalModel.GEMMA4_4B -> "Gemma 4 4B"
            ModelFileManager.LocalModel.GEMMA3N_2B -> "Gemma 3N 2B"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "Gemma 3N 4B"
        }
        
        val sizeBytes = getModelSizeBytes(model)
        val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent, modelIdentifier = ModelFileManager.modelFileName(model))
        
        if (resourceCheck.isMemoryLow || resourceCheck.isStorageLow) {
            downloadWarningModelName = modelName
            downloadWarningModel = model
            downloadWarningSystemMemInfo = resourceCheck.systemMemoryInfo
            downloadWarningIsMemoryLow = resourceCheck.isMemoryLow
            downloadWarningIsStorageLow = resourceCheck.isStorageLow
            downloadWarningAvailableStorageGB = resourceCheck.availableStorageGB
            downloadWarningRequiredStorageGB = resourceCheck.requiredStorageGB
            showDownloadWarning = true
        } else {
            val enqueued = ModelDownloadWorker.enqueue(requireContext(), model)
            if (!enqueued) {
                toast(requireContext().getString(R.string.setup_download_already_in_progress))
            }
        }
    }

    private fun observeDownloadWork() {
        ModelFileManager.LocalModel.entries.forEach { model ->
            WorkManager.getInstance(requireContext())
                .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.modelWorkName(model))
                .observe(viewLifecycleOwner) { infos ->
                    val info = infos.maxByOrNull { it.runAttemptCount }
                    renderDownloadState(model, info)
                }
        }
    }

    private fun refreshModelStatus() {
        builtinModelOptions().forEach { option ->
            val state = modelStates[option.model] ?: return@forEach
            state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), option.model)
            state.isDownloading = false
            state.progress = 0f
            state.progressText = ""
        }
    }

    private fun renderDownloadState(model: ModelFileManager.LocalModel, workInfo: WorkInfo?) {
        val state = modelStates[model] ?: return
        if (workInfo == null) {
            state.isDownloading = false
            state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), model)
            state.progress = 0f
            state.progressText = ""
            return
        }

        when (workInfo.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                state.isDownloading = true
                val downloaded = workInfo.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                val total = workInfo.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                if (total > 0L) {
                    val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    state.progress = percent / 100f
                    state.progressText = "$percent% (${formatGb(downloaded)} / ${formatGb(total)})"
                } else {
                    state.isDownloading = true
                    state.progress = 0f
                    state.progressText = requireContext().getString(R.string.setup_preparing)
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                state.isDownloading = false
                state.isDownloaded = true
                state.progress = 1f
                state.progressText = requireContext().getString(R.string.setup_download_complete)
            }

            WorkInfo.State.FAILED -> {
                state.isDownloading = false
                state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), model)
                state.progress = 0f
                val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                state.progressText = if (error.isNullOrBlank()) requireContext().getString(R.string.setup_download_failed) else error
                toast(requireContext().getString(R.string.setup_download_failed_format, state.progressText))
            }

            WorkInfo.State.CANCELLED -> {
                state.isDownloading = false
                state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), model)
                state.progress = 0f
                state.progressText = ""
            }
        }
    }

    private fun startEmbeddingDownload() {
        embeddingDownloading = true
        embeddingProgress = requireContext().getString(R.string.setup_preparing)
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                MemoryTextEmbedder.ensureEmbeddingFilesDownloaded(
                    requireContext().applicationContext
                ) { file, downloaded, total ->
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    embeddingProgress = "$file: $pct%"
                }
            }.getOrDefault(false)
            embeddingDownloading = false
            embeddingDownloaded = ok
            if (!ok) toast(requireContext().getString(R.string.setup_download_failed_generic))
        }
    }

    private fun canFinishWithoutSkip(): Boolean {
        val selected = selectedModel ?: return false
        val option = builtinModelOptions().firstOrNull { it.settingValue == selected } ?: return false
        return modelStates[option.model]?.isDownloaded == true
    }

    private fun completeSetup(skipModelSelection: Boolean) {
        if (!skipModelSelection && !canFinishWithoutSkip()) {
            toast(requireContext().getString(R.string.setup_model_selection_required))
            return
        }

        val modelToCheck = if (skipModelSelection) {
            selectedModel?.takeIf { model ->
                builtinModelOptions().firstOrNull { it.settingValue == model }?.let { option ->
                    modelStates[option.model]?.isDownloaded == true
                } == true
            }
        } else {
            selectedModel
        }

        if (!modelToCheck.isNullOrBlank()) {
            val option = builtinModelOptions().firstOrNull { it.settingValue == modelToCheck }
            if (option != null) {
                val sizeBytes = getModelSizeBytes(option.model)
                if (MemoryObserver.isMemoryLowForFileSize(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent, useAvailable = false, modelIdentifier = ModelFileManager.modelFileName(option.model))) {
                    isCompleting = true
                    chatWarningModelName = modelToCheck
                    chatWarningSystemMemInfo = MemoryObserver.getSystemMemoryInfoSync(requireContext())
                    pendingSkipModelSelection = skipModelSelection
                    showChatMemoryWarning = true
                    return
                }
            }
        }

        executeCompleteSetup(skipModelSelection)
    }

    private fun executeCompleteSetup(skipModelSelection: Boolean) {
        isCompleting = true
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // バックエンドは既存設定 (既定: CPU) をそのまま使う。
                    // 切り替えはモデル設定画面で行う。
                    val modelToApply = if (skipModelSelection) {
                        selectedModel?.takeIf { model ->
                            builtinModelOptions().firstOrNull { it.settingValue == model }?.let { option ->
                                modelStates[option.model]?.isDownloaded == true
                            } == true
                        }
                    } else {
                        selectedModel
                    }
                    if (!modelToApply.isNullOrBlank()) {
                        settingsRepository.updateModel(modelToApply)
                    }

                    PreferencesHelper.markInitialSetupCompleted(requireContext())
                    val sessionId = sessionRepository.createSession(getString(R.string.setup_default_session_name))
                    if (!modelToApply.isNullOrBlank()) {
                        sessionRepository.updateSessionModel(sessionId, modelToApply)
                    }
                    sessionId
                }
            }.onSuccess { sessionId ->
                navigateToChat(sessionId)
            }.onFailure {
                isCompleting = false
                toast(requireContext().getString(R.string.setup_setup_complete_failed_format, it.message ?: ""))
            }
        }
    }

    private fun getModelSizeBytes(model: ModelFileManager.LocalModel): Long {
        return when (model) {
            ModelFileManager.LocalModel.GEMMA4_2B -> 2_400_000_000L  // 約 2.4GB
            ModelFileManager.LocalModel.GEMMA4_4B -> 8_000_000_000L  // 約 8GB (12GB端末推奨)
            ModelFileManager.LocalModel.GEMMA3N_2B -> 2_000_000_000L  // 約 2GB
            ModelFileManager.LocalModel.GEMMA3N_4B -> 4_000_000_000L  // 約 4GB
        }
    }

    private fun navigateToChat(sessionId: Long) {
        findNavController().navigate(
            R.id.chatFragment,
            Bundle().apply { putLong("sessionId", sessionId) },
            navOptions {
                popUpTo(R.id.setupWizardFragment) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        )
    }

    private fun openLatestOrCreateSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    sessionRepository.getLatestSession()?.id
                        ?: sessionRepository.createSession(getString(R.string.setup_default_session_name))
                }
            }.onSuccess { sessionId ->
                navigateToChat(sessionId)
            }.onFailure {
                toast(requireContext().getString(R.string.setup_chat_restore_failed_format, it.message ?: ""))
            }
        }
    }

    private fun normalizeModelSelection(model: String): String? {
        return builtinModelOptions().firstOrNull { it.settingValue.equals(model, ignoreCase = true) }?.settingValue
    }

    private fun builtinModelOptions(): List<ModelOption> {
        return listOf(
            ModelOption(
                model = ModelFileManager.LocalModel.GEMMA4_2B,
                settingValue = "Gemma4-2B",
                title = "Gemma 4 2B",
                subtitle = getString(R.string.setup_model_gemma4_2b_subtitle)
            ),
            ModelOption(
                model = ModelFileManager.LocalModel.GEMMA4_4B,
                settingValue = "Gemma4-4B",
                title = "Gemma 4 4B",
                subtitle = getString(R.string.setup_model_gemma4_4b_subtitle)
            )
        )
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private data class ModelOption(
        val model: ModelFileManager.LocalModel,
        val settingValue: String,
        val title: String,
        val subtitle: String
    )

    private class DownloadUiState(
        isDownloaded: Boolean = false
    ) {
        var isDownloaded by mutableStateOf(isDownloaded)
        var isDownloading by mutableStateOf(false)
        var progress by mutableFloatStateOf(0f)
        var progressText by mutableStateOf("")
    }
}
