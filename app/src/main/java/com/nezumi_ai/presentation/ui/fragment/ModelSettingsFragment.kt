package com.nezumi_ai.presentation.ui.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.width
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nezumi_ai.MyApplication
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.HfAuthManager
import com.nezumi_ai.data.inference.HfOAuthManager
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ProjectConfig
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.presentation.ui.helper.SettingsHelper
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalUriHandler
import com.nezumi_ai.data.inference.PromptTemplateEngine
import com.nezumi_ai.data.inference.PromptTemplateStore
import com.nezumi_ai.utils.GgufMetadataReader
import com.nezumi_ai.utils.ImportedModelCapabilities
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.voicevox.VoicevoxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import androidx.compose.material3.ExperimentalMaterial3Api
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

enum class ModelType {
    LLM, IMAGE_GENERATION, TEXT_TO_SPEECH, DOWNLOAD_QUEUE
}

@OptIn(ExperimentalMaterial3Api::class)
open class ModelSettingsFragment : Fragment() {
    private lateinit var settingsRepository: SettingsRepository
    private var authService: AuthorizationService? = null
    private var preloadMemoryWarningThresholdPercent by mutableStateOf(MemoryObserver.DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT)
    private var pendingDownloadPermissionModel: ModelFileManager.LocalModel? = null

    private var hfLinked by mutableStateOf(false)
    private var hfSearchQuery by mutableStateOf("")
    private var hfSearchLoading by mutableStateOf(false)
    private var hfSearchError by mutableStateOf<String?>(null)
    private var hfSearchResults by mutableStateOf<List<ModelFileManager.HfModelSearchResult>>(emptyList())
    private var hfSearchNextPageUrl by mutableStateOf<String?>(null)
    private var hfSearchLoadingMore by mutableStateOf(false)
    private var hfSearchResultsDialogVisible by mutableStateOf(false)
    private var hfFilePickerModel by mutableStateOf<ModelFileManager.HfModelSearchResult?>(null)
    private var hfFilePickerLoading by mutableStateOf(false)
    private var hfFilePickerFiles by mutableStateOf<List<ModelFileManager.HfModelFile>>(emptyList())
    private var hfMmprojCandidates by mutableStateOf<List<ModelFileManager.HfModelFile>>(emptyList())
    private var hfReadmeText by mutableStateOf<String?>(null)
    private var hfReadmeLoading by mutableStateOf(false)
    private var hfReadmeError by mutableStateOf<String?>(null)
    private var hfReadmePageVisible by mutableStateOf(false)
    private var hfReadmePageTitle by mutableStateOf("")
    private var hfDownloadingFilePath by mutableStateOf<String?>(null)
    private var hfQueuedDownloads by mutableStateOf<List<HfQueuedDownloadUiState>>(emptyList())
    private val hfSucceededWorkIds = mutableSetOf<java.util.UUID>()
    private val imageModelSucceededWorkIds = mutableSetOf<java.util.UUID>()
    private var importedTasks by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())
    private var importedMmprojTasks by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())

    // ★ ローカルインポートモデルの「整理」UI 状態
    //   - 検索欄
    //   - 並び替えキー（名前 / 更新 / サイズ）
    //   - 昇順 / 降順
    //   これらは importedTasks が空かどうかに関係なく常表示し、
    //   importedTasks が 0 件の場合も「検索・ソートの位置」を見えるようにする。
    private var importedSearchQuery by mutableStateOf("")
    private var importedSortKey by mutableStateOf(ImportedSortKey.NAME)
    private var importedSortDescending by mutableStateOf(false)
    private var importedSortMenuExpanded by mutableStateOf(false)

    private enum class ImportedSortKey(val label: String) {
        NAME("名前順"),
        UPDATED("更新順"),
        SIZE("サイズ順")
    }
    private lateinit var presetRepository: PresetRepository
    private var isImportingModel by mutableStateOf(false)
    private var modelSettingsDialogModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var capabilityDialogImageEnabled by mutableStateOf(false)
    private var showToolCallingDisableConfirmDialog by mutableStateOf(false)
    private var toolCallingDisableConfirmModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var toolCallingDisableConfirmNewCapabilities by mutableStateOf<ImportedModelCapabilities?>(null)
    private var toolCallingDisableConfirmTokens by mutableStateOf<List<String>>(emptyList())
    private var toolCallingDisableConflictCount by mutableStateOf(0)
    private var capabilityDialogAudioEnabled by mutableStateOf(false)
    private var capabilityDialogThinkingEnabled by mutableStateOf(false)
    private var capabilityDialogToolCallingEnabled by mutableStateOf(false)
    private var capabilityDialogMmprojPath by mutableStateOf("")
    private var capabilityDialogCurrentCapabilities by mutableStateOf<ImportedModelCapabilities?>(null)
    private var capabilityDialogModelType by mutableStateOf<ModelType>(ModelType.LLM)
    private var mmprojDropdownExpanded by mutableStateOf(false)
    private var capabilityDialogRepoMmprojCandidates by mutableStateOf<List<ModelFileManager.HfModelFile>>(emptyList())
    private var capabilityDialogRepoMmprojLoading by mutableStateOf(false)

    // --- プロンプトテンプレート設定（Issue #31 / #32） ---
    private var capabilityDialogTemplateMode by mutableStateOf(PromptTemplateStore.MODE_AUTO)
    private var capabilityDialogTemplateCustom by mutableStateOf("")
    private var capabilityDialogTemplateError by mutableStateOf<String?>(null)
    private var capabilityDialogTemplateExpanded by mutableStateOf(false)
    
    private var imageModelsLoading by mutableStateOf(false)
    private var imageModelsError by mutableStateOf<String?>(null)
    private var availableImageModels by mutableStateOf<List<com.nezumi_ai.data.inference.ImageModel>>(emptyList())
    private var imageModelsDialogVisible by mutableStateOf(false)
    private var imageModelSearchQuery by mutableStateOf("")
    private var downloadingImageModelIds by mutableStateOf<Set<String>>(emptySet())
    private var imageModelDownloadStates by mutableStateOf<List<ImageModelDownloadUiState>>(emptyList())
    private var safetyModelDownloadState by mutableStateOf<ImageModelDownloadUiState?>(null)
    private var voicevoxState by mutableStateOf(VoicevoxModelUiState())
    private var voicevoxInitializing by mutableStateOf(false)
    private var voicevoxDownloading by mutableStateOf(false)
    private var voicevoxStyleMenuExpanded by mutableStateOf(false)
    private var voicevoxModelMenuExpanded by mutableStateOf(false)
    private var voicevoxSelectedCatalogEntry by mutableStateOf(
        VoicevoxManager.modelCatalog.firstOrNull { it.fileName == "3.vvm" }
            ?: VoicevoxManager.modelCatalog.firstOrNull()
            ?: VoicevoxManager.VoiceModelCatalogEntry("", VoicevoxManager.VoiceModelCategory.TALK, emptyList())
    )

    private val mmprojPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ModelFileManager.importTaskFromUri(requireContext(), uri)
                }
                result.onSuccess { capabilityDialogMmprojPath = it.absolutePath }
                    .onFailure { toast("mmproj追加失敗: ${it.message}") }
            }
        }
    private val voicevoxModelPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val target = voicevoxModelFile()
                        target.parentFile?.mkdirs()
                        requireContext().contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "入力ファイルを開けませんでした" }
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                        target
                    }
                }
                result.onSuccess {
                    (requireContext().applicationContext as MyApplication)
                        .getVoicevoxManager()
                        .release()
                    toast("音声モデルを追加しました")
                    refreshVoicevoxState()
                }.onFailure {
                    toast("音声モデル追加失敗: ${it.message}")
                }
            }
        }
    private var settingsDialogDisplayName by mutableStateOf("")
    private var settingsDialogStopTokens by mutableStateOf("")
    private var expandedModelKey by mutableStateOf<String?>(null)

    private var sdModels by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())
    
    private var selectedTab by mutableStateOf(ModelType.LLM)

    private val modelStates = mutableStateMapOf<ModelFileManager.LocalModel, ModelUiState>()
    private val ggufCardMetadataStates = mutableStateMapOf<String, GgufCardMetadataUiState>()

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            val authResponse = AuthorizationResponse.fromIntent(data)
            val authError = AuthorizationException.fromIntent(data)
            if (authError != null) {
                toast("OAuth失敗: ${authError.errorDescription}")
                return@registerForActivityResult
            }
            if (authResponse == null) {
                toast("OAuthレスポンスが取得できませんでした")
                return@registerForActivityResult
            }
            exchangeToken(authResponse)
        }

    private val importTaskLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            isImportingModel = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val modelPath = withContext(Dispatchers.IO) {
                        val file = ModelFileManager.importTaskFromUri(requireContext(), uri).getOrThrow()
                        if (file.name.lowercase().endsWith(".gguf")) runCatching { GgufMetadataReader.readSummary(file) }
                        file.absolutePath
                    }
                    toast("モデルを追加しました: ${File(modelPath).name}")
                    refreshImportedTasks()
                    val imported = ModelFileManager.ImportedTaskModel(
                        path = modelPath,
                        fileNameStem = File(modelPath).nameWithoutExtension,
                        shortDisplayName = File(modelPath).nameWithoutExtension,
                        hfRepoQualifier = null
                    )
                    openModelSettingsDialog(imported)
                } catch (e: Exception) {
                    toast("追加失敗: ${e.message}")
                }
                isImportingModel = false
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            pendingDownloadPermissionModel?.let { runModelDownload(it) }
            pendingDownloadPermissionModel = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository.fromDatabase(db)
        presetRepository = PresetRepository(db.presetDao(), requireContext().applicationContext)
        authService = AuthorizationService(requireContext())
        ModelFileManager.LocalModel.entries.forEach { modelStates[it] = ModelUiState(titleFor(it)) }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                ModelScreen()
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderHfTokenState()
        refreshImportedTasks()
        refreshModelStatus()
        refreshVoicevoxState()
        observeDownloadWork()
        observeCustomHfDownloadWork()
        observeImageModelDownloadWork()
        observeSafetyModelDownloadWork()
        viewLifecycleOwner.lifecycleScope.launch {
            preloadMemoryWarningThresholdPercent = settingsRepository.getPreloadMemoryWarningThresholdPercent()
        }
    }

    override fun onResume() {
        super.onResume()
        renderHfTokenState()
        refreshImportedTasks()
        refreshVoicevoxState()
    }

    @Composable
    private fun ModelScreen() {
        if (hfReadmePageVisible) {
            HfReadmePage()
            return
        }
        if (isImportingModel) {
            ImportingDialog()
        }
        hfFilePickerModel?.let { model ->
            HfFilePickerDialog(model)
        }
        modelSettingsDialogModel?.let { model ->
            ImportedModelSettingsDialog(model)
        }
        if (hfSearchResultsDialogVisible) {
            HfSearchResultsContent()
            return
        }
        if (imageModelsDialogVisible) {
            ImageModelsDialogContent()
            return
        }

        val displayedImportedTasks = remember(
            importedTasks, importedSearchQuery, importedSortKey, importedSortDescending
        ) {
            applyImportedModelFilters(importedTasks)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(colorResource(id = R.color.bg_session_list))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { findNavController().navigateUp() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = stringResource(id = R.string.back),
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                    Text(
                        text = "モデル",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorResource(id = R.color.text_primary),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            item { TabSelector() }
            
            when (selectedTab) {
                ModelType.LLM -> {
                    item { HfCard() }
                    item { HfModelSearchCard() }
                    item {
                        Text(
                            text = "組み込みモデル",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                    }
                    items(ModelFileManager.LocalModel.entries) { model ->
                        val state = modelStates[model]
                        if (state != null) {
                            val modelKey = "builtin_${model.name}"
                            val isExpanded = expandedModelKey == modelKey
                            val sizeBytes = getModelSizeBytes(model)
                            val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent)
                            ModelAccordionItem(
                                title = state.title,
                                status = state.status,
                                isExpanded = isExpanded,
                                onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                                onDownload = { requestNotificationPermissionForDownload(model) },
                                onDelete = {
                                    val ok = ModelFileManager.deleteModel(requireContext(), model)
                                    toast(if (ok) "削除しました" else "削除に失敗しました")
                                    refreshModelStatus(model)
                                    expandedModelKey = null
                                },
                                isDownloading = state.isDownloading,
                                isDownloaded = state.isDownloaded,
                                progress = state.progress,
                                progressText = state.progressText,
                                isMemoryLow = state.memoryWarning != null,
                                isStorageLow = resourceCheck.isStorageLow,
                                fileSizeLabel = formatBytes(sizeBytes)
                            )
                        }
                    }
                    item { EmbeddingModelsCard() }
                    // ★ 「カスタムモデル」見出しと整理 UI（検索 / 並び替え）を、
                    //   importedTasks が 0 件でも常に表示される位置に出す。
                    //   以前は if (importedTasks.isNotEmpty()) { … } の中に入れていたため、
                    //   件数が 0 だと「見えない」状態になっていた。
                    item {
                        Text(
                            text = "カスタムモデル",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 12.dp)
                        )
                    }
                    item { ImportedModelsFilterBar() }
                    
                    if (importedTasks.isEmpty()) {
                        item {
                            Text(
                                text = "カスタムモデルはまだインポートされていません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    } else if (displayedImportedTasks.isEmpty()) {
                        item {
                            Text(
                                text = "検索条件に一致するカスタムモデルがありません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        items(displayedImportedTasks) { model ->
                            val modelKey = "imported_${model.path}"
                            val isExpanded = expandedModelKey == modelKey
                            ImportedModelAccordionItem(
                                model = model,
                                isExpanded = isExpanded,
                                onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                                onDelete = {
                                    val result = ModelFileManager.deleteImportedTask(requireContext(), model.path)
                                    result.onSuccess {
                                        ImportedModelCapabilityStore.clear(requireContext(), model.path)
                                        PromptTemplateStore.clear(requireContext(), model.path)
                                        toast("削除しました")
                                        refreshImportedTasks()
                                        expandedModelKey = null
                                    }.onFailure {
                                        toast("削除に失敗しました: ${it.message}")
                                    }
                                }
                            )
                        }
                    }
                    item { MmprojFilesCard() }
                    item { LocalModelAddCard() }
                }
                ModelType.IMAGE_GENERATION -> {
                    item { SdImageGenFromHfCard() }
                    item { DownloadedImageModelsCard() }
                }
                ModelType.TEXT_TO_SPEECH -> {
                    item { VoicevoxSummaryCard() }
                    item { VoicevoxFilesCard() }
                    item { VoicevoxRuntimeCard() }
                }
                ModelType.DOWNLOAD_QUEUE -> {
                    item { DownloadQueueCard() }
                }
            }
        }
    }

    @Composable
    private fun ImportingDialog() {
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

    @Composable
    private fun ImportedModelSettingsDialog(model: ModelFileManager.ImportedTaskModel) {
        val loweredPath = model.path.lowercase()
        val isGguf = loweredPath.endsWith(".gguf")
        val isLiteRt = loweredPath.endsWith(".litertlm") || loweredPath.endsWith(".task")
        val supportsToolCalling = isGguf || isLiteRt
        val dialogTitle = ImportedModelCapabilityStore.resolveDisplayName(
            requireContext(), model.path, model.shortDisplayName
        )
        Dialog(onDismissRequest = { modelSettingsDialogModel = null }) {
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
                    Text(
                        text = "設定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
                        Text("画像入力を有効化", color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = capabilityDialogImageEnabled,
                            onCheckedChange = { capabilityDialogImageEnabled = it }
                        )
                    }
                    if (isGguf && capabilityDialogImageEnabled) {
                        Text(
                            text = "mmproj: ${capabilityDialogMmprojPath.takeIf { it.isNotBlank() }?.let { java.io.File(it).name } ?: "未選択"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        ExposedDropdownMenuBox(
                            expanded = mmprojDropdownExpanded,
                            onExpandedChange = { mmprojDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = if (capabilityDialogMmprojPath.isBlank()) "未選択" else java.io.File(capabilityDialogMmprojPath).name,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("mmprojファイル") },
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
                                    text = { Text("未選択") },
                                    onClick = {
                                        capabilityDialogMmprojPath = ""
                                        mmprojDropdownExpanded = false
                                    }
                                )
                                if (capabilityDialogRepoMmprojLoading) {
                                    DropdownMenuItem(text = { Text("候補を取得中…") }, onClick = {})
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
                        Text("音声入力を有効化", color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = capabilityDialogAudioEnabled,
                            onCheckedChange = { capabilityDialogAudioEnabled = it }
                        )
                    }
                    if (isGguf) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("推論（Thinking）を有効化", color = MaterialTheme.colorScheme.onSurface)
                            Switch(
                                checked = capabilityDialogThinkingEnabled,
                                onCheckedChange = { capabilityDialogThinkingEnabled = it }
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
                                Text("ツール呼び出しを有効化", color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    text = if (isLiteRt) "LiteRT-LM のツール呼び出しに対応します" else "GGUF / llama.rn のツール呼び出しに対応します",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = capabilityDialogToolCallingEnabled,
                                onCheckedChange = { capabilityDialogToolCallingEnabled = it }
                            )
                        }
                    }
                    Divider()
                    Text(
                        text = "モデル表示名",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = settingsDialogDisplayName,
                        onValueChange = { settingsDialogDisplayName = it },
                        label = { Text("表示名") },
                        singleLine = true
                    )
                    Text(
                        text = "記号 \\ / : * ? \" < > | は使用できません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isGguf) {
                        Divider()
                        Text(
                            text = "プロンプトテンプレート",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "モデルごとのチャットテンプレートを選択します。「自動検出」ではモデル名から ChatML / Gemma を推定します。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val templateOptions = remember {
                            buildList {
                                add(PromptTemplateStore.MODE_AUTO to "自動検出")
                                PromptTemplateStore.BUILTIN_TEMPLATES.forEach { b ->
                                    add(b.id to b.displayName)
                                }
                                add(PromptTemplateStore.MODE_CUSTOM to "カスタム...")
                            }
                        }
                        val currentLabel = templateOptions.firstOrNull { it.first == capabilityDialogTemplateMode }?.second
                            ?: "自動検出"
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
                                label = { Text("チャットテンプレート") },
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
                                            // 初めてカスタムを選んだとき、ビルトインを雛型としてコピー
                                            if (id == PromptTemplateStore.MODE_CUSTOM && capabilityDialogTemplateCustom.isBlank()) {
                                                val seed = PromptTemplateStore.BUILTIN_TEMPLATES.firstOrNull {
                                                    it.id == previousMode
                                                }?.template
                                                    ?: PromptTemplateStore.BUILTIN_TEMPLATES.firstOrNull { it.id == "chatml" }?.template
                                                    ?: ""
                                                capabilityDialogTemplateCustom = seed
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
                                label = { Text("カスタムテンプレート") },
                                placeholder = { Text("{{ if .System }}...{{ end }}{{ range .History }}...{{ end }}") },
                                minLines = 5,
                                isError = capabilityDialogTemplateError != null
                            )
                            Text(
                                text = "利用可能な変数: {{ .System }} / {{ .Prompt }} / {{ .Response }} / {{ .Thinking }} / {{ range .History }} {{ .Role }} {{ .Content }} {{ end }}",
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
                        text = "ストップトークン（カンマ区切り）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = settingsDialogStopTokens,
                        onValueChange = { settingsDialogStopTokens = it },
                        label = { Text("追加ストップトークン") },
                        placeholder = { Text("<|im_end|>,<|im_start|>") },
                        minLines = 2
                    )
                    Text(
                        text = "デフォルトのストップトークンに追加されます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = { modelSettingsDialogModel = null }) { Text("キャンセル") }
                        Button(onClick = {
                            val invalidChars = Regex("[\\\\/:*?\"<>|]")
                            if (invalidChars.containsMatchIn(settingsDialogDisplayName)) {
                                toast("表示名に使用できない記号が含まれています")
                                return@Button
                            }
                            // カスタムテンプレートのバリデーション
                            if (capabilityDialogTemplateMode == PromptTemplateStore.MODE_CUSTOM) {
                                val err = PromptTemplateEngine.validate(capabilityDialogTemplateCustom)
                                if (err != null) {
                                    capabilityDialogTemplateError = err
                                    toast("テンプレートにエラーがあります: $err")
                                    return@Button
                                }
                            }
                            val tokens = settingsDialogStopTokens
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            val newCapabilities = ImportedModelCapabilities(
                                imageEnabled = capabilityDialogImageEnabled,
                                audioEnabled = capabilityDialogAudioEnabled,
                                mmprojPath = capabilityDialogMmprojPath.ifBlank { null },
                                thinkingEnabled = capabilityDialogThinkingEnabled,
                                displayName = settingsDialogDisplayName.trim().ifBlank { null },
                                toolCallingEnabled = capabilityDialogToolCallingEnabled
                            )
                            viewLifecycleOwner.lifecycleScope.launch {
                                val requiresConfirmation = capabilityDialogCurrentCapabilities?.toolCallingEnabled == true &&
                                    !newCapabilities.toolCallingEnabled
                                if (requiresConfirmation) {
                                    val affectedCount = withContext(Dispatchers.IO) {
                                        presetRepository.countPresetsUsingModelWithToolCallingEnabled(model.path)
                                    }
                                    if (affectedCount > 0) {
                                        toolCallingDisableConfirmModel = model
                                        toolCallingDisableConfirmNewCapabilities = newCapabilities
                                        toolCallingDisableConfirmTokens = tokens
                                        toolCallingDisableConflictCount = affectedCount
                                        showToolCallingDisableConfirmDialog = true
                                        return@launch
                                    }
                                }
                                persistModelSettings(model, newCapabilities, isGguf, tokens)
                            }
                        }) { Text("保存") }
                    }
                }
            }
        }

        if (showToolCallingDisableConfirmDialog && toolCallingDisableConfirmModel != null && toolCallingDisableConfirmNewCapabilities != null) {
            AlertDialog(
                onDismissRequest = { showToolCallingDisableConfirmDialog = false },
                title = { Text("モデルツール呼び出しを無効化しますか？") },
                text = {
                    Text("このモデルを使用する $toolCallingDisableConflictCount 件のプリセットのツール呼び出し設定も無効になりますがよろしいですか？")
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
                    }) { Text("はい") }
                },
                dismissButton = {
                    TextButton(onClick = { showToolCallingDisableConfirmDialog = false }) { Text("キャンセル") }
                }
            )
        }
    }

    @Composable
    private fun TabSelector() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabButton(
                    text = "LLM",
                    selected = selectedTab == ModelType.LLM,
                    onClick = { selectedTab = ModelType.LLM },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    text = "画像生成",
                    selected = selectedTab == ModelType.IMAGE_GENERATION,
                    onClick = { selectedTab = ModelType.IMAGE_GENERATION },
                    modifier = Modifier.weight(1f)
                )
                if (com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
                    TabButton(
                        text = "読み上げ",
                        selected = selectedTab == ModelType.TEXT_TO_SPEECH,
                        onClick = { selectedTab = ModelType.TEXT_TO_SPEECH },
                        modifier = Modifier.weight(1f)
                    )
                }
                TabButton(
                    text = "DL",
                    selected = selectedTab == ModelType.DOWNLOAD_QUEUE,
                    onClick = { selectedTab = ModelType.DOWNLOAD_QUEUE },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    
    @Composable
    private fun VoicevoxSummaryCard() {
        // VOICEVOX 無効時はセクション全体を非表示にする
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        Text(
            text = "音声読み上げ",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "VOICEVOX",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.text_primary)
                )
                VoicevoxStatusRow("音声モデル", voicevoxState.modelStatus)
                VoicevoxStatusRow("OpenJTalk辞書", voicevoxState.dictionaryStatus)
                VoicevoxStatusRow("ONNX Runtime", voicevoxState.runtimeStatus)
                VoicevoxStatusRow("話者", voicevoxState.selectedStyleLabel)
                if (voicevoxState.styles.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = voicevoxStyleMenuExpanded,
                        onExpandedChange = { voicevoxStyleMenuExpanded = !voicevoxStyleMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = voicevoxState.selectedStyleLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("読み上げ話者") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = voicevoxStyleMenuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = voicevoxStyleMenuExpanded,
                            onDismissRequest = { voicevoxStyleMenuExpanded = false }
                        ) {
                            voicevoxState.styles.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style.displayName) },
                                    onClick = {
                                        val manager = (requireContext().applicationContext as MyApplication)
                                            .getVoicevoxManager()
                                        manager.setSelectedStyleId(style.styleId)
                                        voicevoxStyleMenuExpanded = false
                                        refreshVoicevoxState()
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (voicevoxState.modelExists) {
                            "話者一覧を読み込めませんでした。初期化後に再表示してください。"
                        } else {
                            ".vvm を追加すると話者を選択できます。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
                Button(
                    onClick = { initializeVoicevoxFromSettings() },
                    enabled = !voicevoxInitializing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (voicevoxInitializing) "初期化中..." else "ダウンロード/初期化")
                }
                voicevoxState.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            }
        }
    }

    @Composable
    private fun VoicevoxFilesCard() {
        Text(
            text = "モデルファイル",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = voicevoxState.modelPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
                Text(
                    text = voicevoxState.modelDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_primary)
                )
                ExposedDropdownMenuBox(
                    expanded = voicevoxModelMenuExpanded,
                    onExpandedChange = { voicevoxModelMenuExpanded = !voicevoxModelMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = voicevoxSelectedCatalogEntry.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("ダウンロードするVVM") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = voicevoxModelMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = voicevoxModelMenuExpanded,
                        onDismissRequest = { voicevoxModelMenuExpanded = false }
                    ) {
                        VoicevoxManager.modelCatalog.forEach { entry ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("${entry.category.label} / ${entry.displayName}")
                                        Text(
                                            text = entry.shortDescription,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorResource(id = R.color.text_secondary)
                                        )
                                    }
                                },
                                onClick = {
                                    voicevoxSelectedCatalogEntry = entry
                                    voicevoxModelMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !voicevoxDownloading && !voicevoxInitializing,
                    onClick = { downloadSelectedVoicevoxModel() }
                ) {
                    Text(if (voicevoxDownloading) "ダウンロード中..." else "選択したVVMをダウンロードして切替")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { voicevoxModelPickerLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text(".vvm を追加")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = voicevoxState.modelExists,
                    onClick = {
                        (requireContext().applicationContext as MyApplication)
                            .getVoicevoxManager()
                            .release()
                        val deleted = voicevoxModelFile().delete()
                        toast(if (deleted) "音声モデルを削除しました" else "音声モデルの削除に失敗しました")
                        voicevoxStyleMenuExpanded = false
                        refreshVoicevoxState()
                    }
                ) {
                    Text("音声モデルを削除")
                }
            }
        }
    }

    @Composable
    private fun VoicevoxRuntimeCard() {
        Text(
            text = "ランタイム",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VoicevoxStatusRow("arm64-v8a", voicevoxState.arm64RuntimeDetail)
                VoicevoxStatusRow("x86_64", voicevoxState.x64RuntimeDetail)
                VoicevoxStatusRow("辞書", voicevoxState.dictionaryDetail)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = voicevoxState.dictionaryExists,
                    onClick = {
                        val deleted = voicevoxDictionaryDir().deleteRecursively()
                        toast(if (deleted) "OpenJTalk辞書を削除しました" else "OpenJTalk辞書の削除に失敗しました")
                        refreshVoicevoxState()
                    }
                ) {
                    Text("辞書を削除")
                }
                Text(
                    text = "16KB非対応のONNX RuntimeではAndroid 15以降の一部端末で読み込みに失敗します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
            }
        }
    }

    @Composable
    private fun VoicevoxStatusRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = value,
                color = colorResource(id = R.color.text_primary),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    @Composable
    private fun TabButton(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Card(
            modifier = modifier
                .height(40.dp)
                .clickable(onClick = onClick),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) {
                    colorResource(id = R.color.primary)
                } else {
                    colorResource(id = R.color.primary_light)
                }
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (selected) 4.dp else 0.dp
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = if (selected) {
                        colorResource(id = R.color.nezumi_on_primary)
                    } else {
                        colorResource(id = R.color.text_secondary)
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
    
    
    @Composable
    private fun DownloadQueueCard() {
        if (hfQueuedDownloads.isNotEmpty()) {
            Text(
                text = "追加モデル ダウンロード中",
                style = MaterialTheme.typography.labelSmall,
                color = colorResource(id = R.color.text_secondary),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hfQueuedDownloads.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = colorResource(id = R.color.surface_card)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = item.modelId, fontWeight = FontWeight.SemiBold)
                            Text(text = item.filePath, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                            if (item.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    progress = { item.progress },
                                    color = colorResource(id = R.color.primary),
                                    trackColor = colorResource(id = R.color.context_meter_track)
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorResource(id = R.color.primary),
                                    trackColor = colorResource(id = R.color.context_meter_track)
                                )
                            }
                            Text(text = item.statusText, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                            if (item.isActive) {
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        ModelDownloadWorker.cancelCustomHf(
                                            requireContext(),
                                            item.modelId,
                                            item.filePath
                                        )
                                    }) { Text("キャンセル") }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (imageModelDownloadStates.isNotEmpty()) {
            Text(
                text = "画像生成モデル ダウンロード中",
                style = MaterialTheme.typography.labelSmall,
                color = colorResource(id = R.color.text_secondary),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = if (hfQueuedDownloads.isNotEmpty()) 16.dp else 0.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                imageModelDownloadStates.forEach { item ->
                    ModelDownloadProgressCard(item) {
                        ModelDownloadWorker.cancelImageModel(requireContext(), item.modelId)
                    }
                }
            }
        }

        safetyModelDownloadState?.let { item ->
            Text(
                text = "セーフティモデル ダウンロード中",
                style = MaterialTheme.typography.labelSmall,
                color = colorResource(id = R.color.text_secondary),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    start = 4.dp,
                    bottom = 8.dp,
                    top = if (hfQueuedDownloads.isNotEmpty() || imageModelDownloadStates.isNotEmpty()) 16.dp else 0.dp
                )
            )
            ModelDownloadProgressCard(item)
        }
        
        if (hfQueuedDownloads.isEmpty() && imageModelDownloadStates.isEmpty() && safetyModelDownloadState == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Text(
                    text = "ダウンロード中のモデルはありません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    @Composable
    private fun HfCard() {
        Text(
            text = "Hugging Face 連携",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "HF:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (hfLinked) "連携済み" else "未連携",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Button(
                        onClick = { if (hfLinked) logoutHf() else startOAuthLogin() },
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(if (hfLinked) "ログアウト" else "ログイン 🤗", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun HfModelSearchCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = hfSearchQuery,
                        onValueChange = { hfSearchQuery = it },
                        placeholder = { Text("キーワード / repo id") },
                        singleLine = true
                    )
                    Button(
                        enabled = !hfSearchLoading,
                        onClick = { searchHfModels() },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text(if (hfSearchLoading) "検索中..." else "検索")
                    }
                }
                if (hfSearchResults.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !hfSearchLoading,
                            onClick = { hfSearchResultsDialogVisible = true }
                        ) {
                            Text("結果を見る (${hfSearchResults.size})")
                        }
                        TextButton(
                            enabled = !hfSearchLoading,
                            onClick = {
                                hfSearchResults = emptyList()
                                hfSearchNextPageUrl = null
                                hfSearchError = null
                                hfSearchResultsDialogVisible = false
                            }
                        ) {
                            Text("クリア")
                        }
                    }
                }
                hfSearchError?.let {
                    Text(text = it, color = colorResource(id = R.color.text_primary), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun SdImageGenFromHfCard() {
        Text(
            text = "リポジトリ",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "xororz/sd-mnn (GPU) | sd-qnn (NPU)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
                Button(
                    onClick = { loadImageModels() },
                    enabled = !imageModelsLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (imageModelsLoading) "読込中..." else "モデル一覧を表示")
                }
                imageModelsError?.let {
                    Text(
                        text = it,
                        color = colorResource(id = R.color.text_primary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    
    
    @Composable
    private fun BuiltInModelsCard() {
        Text(
            text = "組み込みモデル",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (model in ModelFileManager.LocalModel.entries) {
                val state = modelStates[model] ?: continue
                val modelKey = "builtin_${model.name}"
                val isExpanded = expandedModelKey == modelKey
                
                // ストレージ判定
                val sizeBytes = getModelSizeBytes(model)
                val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent)
                
                ModelAccordionItem(
                    title = state.title,
                    status = state.status,
                    isExpanded = isExpanded,
                    onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                    onDownload = { requestNotificationPermissionForDownload(model) },
                    onDelete = {
                        val ok = ModelFileManager.deleteModel(requireContext(), model)
                        toast(if (ok) "削除しました" else "削除に失敗しました")
                        refreshModelStatus(model)
                        expandedModelKey = null
                    },
                    isDownloading = state.isDownloading,
                    isDownloaded = state.isDownloaded,
                    progress = state.progress,
                    progressText = state.progressText,
                    isMemoryLow = state.memoryWarning != null,
                    isStorageLow = resourceCheck.isStorageLow,
                    fileSizeLabel = formatBytes(sizeBytes)
                )
            }
        }
    }
    
    @Composable
    private fun CustomModelsCard() {
        if (importedTasks.isEmpty()) return
        
        Text(
            text = "カスタムモデル",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (model in importedTasks) {
                val modelKey = "imported_${model.path}"
                val isExpanded = expandedModelKey == modelKey
                ImportedModelAccordionItem(
                    model = model,
                    isExpanded = isExpanded,
                    onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                    onDelete = {
                        val result = ModelFileManager.deleteImportedTask(requireContext(), model.path)
                        result.onSuccess {
                            ImportedModelCapabilityStore.clear(requireContext(), model.path)
                                        PromptTemplateStore.clear(requireContext(), model.path)
                            toast("削除しました")
                            refreshImportedTasks()
                            expandedModelKey = null
                        }.onFailure {
                            toast("削除に失敗しました: ${it.message}")
                        }
                    }
                )
            }
        }
    }
    
    @Composable
    private fun EmbeddingModelsCard() {
        val ctx = requireContext()
        val entries = remember { MemoryTextEmbedder.listEmbeddingFileEntries(ctx) }
        val totalSize = entries.sumOf { it.sizeBytes }
        val isReady = MemoryTextEmbedder.hasEmbeddingFiles(ctx)

        Text(
            text = "埋め込みモデル（メモリ検索用）",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "static-embedding-japanese",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isReady) "✓ 利用可能" else "未ダウンロード",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isReady) colorResource(id = R.color.primary)
                        else colorResource(id = R.color.text_secondary)
                    )
                }
                if (entries.isEmpty()) {
                    Text(
                        text = "メモリ機能利用時に自動ダウンロードされます",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                } else {
                    entries.forEach { entry ->
                        Text(
                            text = "${entry.fileName}: ${formatBytes(entry.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                    Text(
                        text = "合計: ${formatBytes(totalSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorResource(id = R.color.text_primary)
                    )
                }
            }
        }
    }

    @Composable
    private fun MmprojFilesCard() {
        Text(
            text = "mmproj ファイル",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
        )
        
        if (importedMmprojTasks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mmproj in importedMmprojTasks) {
                    val mmprojKey = "mmproj_${mmproj.path}"
                    val isExpanded = expandedModelKey == mmprojKey
                    MmprojAccordionItem(
                        model = mmproj,
                        isExpanded = isExpanded,
                        onToggle = { expandedModelKey = if (isExpanded) null else mmprojKey },
                        onDelete = {
                            val result = ModelFileManager.deleteImportedTask(requireContext(), mmproj.path)
                            result.onSuccess {
                                toast("mmproj ファイルを削除しました")
                                refreshImportedTasks()
                                expandedModelKey = null
                            }.onFailure {
                                toast("削除に失敗しました: ${it.message}")
                            }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ビジョン・オーディオ処理用の投影ファイル",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { mmprojPickerLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text("mmproj を追加")
                }
            }
        }
    }
    
    @Composable
    private fun LocalModelAddCard() {
        Text(
            text = "ローカルモデル追加",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = ".task / .litertlm / .gguf 対応",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { importTaskLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text("モデルファイルを追加")
                }
            }
        }
    }
    
    @Composable
    private fun DownloadedImageModelsCard() {
        Text(
            text = "ダウンロード済みモデル",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        
        if (sdModels.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Text(
                    text = "上記の「リポジトリ」カードからダウンロードできます",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (model in sdModels) {
                    val modelKey = "sd_${model.path}"
                    val isExpanded = expandedModelKey == modelKey
                    ImageModelAccordionItem(
                        model = model,
                        isExpanded = isExpanded,
                        onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                        onDelete = {
                            val dir = File(model.path)
                            val deleted = dir.deleteRecursively()
                            if (deleted) {
                                toast("削除しました")
                                refreshSdModels()
                                expandedModelKey = null
                            } else {
                                toast("削除に失敗しました")
                            }
                        },
                        onSetActive = {
                            PreferencesHelper.setSdModelPath(requireContext(), model.path)
                            toast("アクティブに設定しました")
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun ImageModelsDialogContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { imageModelsDialogVisible = false }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "戻る",
                        tint = colorResource(id = R.color.text_primary)
                    )
                }
                Text(
                    text = "画像生成モデル",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorResource(id = R.color.text_primary),
                    fontWeight = FontWeight.Bold
                )
            }
            
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = imageModelSearchQuery,
                onValueChange = { imageModelSearchQuery = it },
                label = { Text("検索") },
                singleLine = true
            )
            
            if (imageModelsLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SvgSpinner(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("モデル一覧を取得中...")
                }
            } else {
                val filtered = availableImageModels.filter {
                    imageModelSearchQuery.isBlank() || 
                    it.displayName.contains(imageModelSearchQuery, ignoreCase = true) ||
                    it.name.contains(imageModelSearchQuery, ignoreCase = true)
                }
                
                Text(
                    text = "${filtered.size}件のモデル",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { model ->
                        ImageModelCard(model)
                    }
                }
            }
        }
    }
    
    @Composable
    private fun ImageModelCard(model: com.nezumi_ai.data.inference.ImageModel) {
        val isDownloading = downloadingImageModelIds.contains(model.id)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = model.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${formatBytes(model.size)} · ${model.backend.uppercase()}",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                if (MemoryObserver.isMemoryLowForFileSize(requireContext(), model.size, preloadMemoryWarningThresholdPercent, useAvailable = false)) {
                    Text(
                        text = "⚠️ メモリ不足",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                model.variant?.let { variant ->
                    com.nezumi_ai.data.inference.ImageModelBrowser.getVariantLabel(variant)?.let { label ->
                        Text(
                            text = label,
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(
                    onClick = { downloadImageModel(model) },
                    enabled = !isDownloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isDownloading) "ダウンロード中..." else "ダウンロード")
                }
            }
        }
    }

    @Composable
    private fun HfSearchResultsContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { hfSearchResultsDialogVisible = false }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "戻る",
                        tint = colorResource(id = R.color.text_primary)
                    )
                }
                Text(
                    text = "検索結果",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorResource(id = R.color.text_primary),
                    fontWeight = FontWeight.Bold
                )
            }
            if (hfSearchResults.isEmpty()) {
                Text(
                    text = "検索結果がありません",
                    color = colorResource(id = R.color.text_secondary)
                )
            } else {
                Text(
                    text = "${hfSearchResults.size}件の結果",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                val listState = rememberLazyListState()
                // ★ 次ページの自動読み込み:
                //   旧: LaunchedEffect(hfSearchResults.size) → trigger item が
                //       LazyColumn に compose された瞬間に発火していたため、
                //       ユーザーがスクロールしていなくても全ページを一気に取得してしまう。
                //   新: リストの末尾付近が実際に表示されたときだけ loadMore を呼ぶ。
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val total = info.totalItemsCount
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        // 末尾に多少余裕を持たせる (2item 手前からプリフェッチ)
                        total > 0 && lastVisible >= total - 2
                    }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            val nextUrl = hfSearchNextPageUrl
                            if (nextUrl != null && !hfSearchLoadingMore) {
                                loadMoreHfResults(nextUrl)
                            }
                        }
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hfSearchResults, key = { it.id }) { result ->
                                Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = colorResource(id = R.color.primary_light)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "\u2B73",
                                        color = colorResource(id = R.color.primary),
                                        fontSize = 18.sp
                                    )
                                    Text(text = result.id, fontWeight = FontWeight.SemiBold)
                                }
                                Text(
                                    text = "DL: ${result.downloads} / Likes: ${result.likes}",
                                    color = colorResource(id = R.color.text_secondary)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !hfFilePickerLoading,
                                        onClick = {
                                            openHfFilePicker(result)
                                        }
                                    ) {
                                        Text("ファイル選択")
                                    }
                                    TextButton(onClick = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://huggingface.co/${result.id}")
                                        )
                                        if (intent.resolveActivity(requireContext().packageManager) != null) {
                                            startActivity(intent)
                                        } else {
                                            toast("ブラウザを起動できませんでした")
                                        }
                                    }) {
                                        Text("ページを開く")
                                    }
                                }
                            }
                        }
                    }
                    // ★ 次ページプレースホルダー: スピナーのみ。loadMore のトリガーは
                    //   上の snapshotFlow 監視で行うので、この item は "現在ロード中に見える" 存在だけ。
                    item {
                        if (hfSearchNextPageUrl != null && hfSearchLoadingMore) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                SvgSpinner()
                            }
                        } else if (hfSearchNextPageUrl != null) {
                            // 候補があるが未ロードのときもプレースホルダーだけ支持しておく（高さは保つ）
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HfFilePickerDialog(model: ModelFileManager.HfModelSearchResult) {
        Dialog(onDismissRequest = {
            if (hfDownloadingFilePath == null) {
                hfFilePickerModel = null
            }
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 640.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "ダウンロードするファイルを選択", fontWeight = FontWeight.Bold)
                    Text(text = model.id, color = colorResource(id = R.color.text_secondary))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { 
                            hfReadmePageTitle = model.id
                            hfReadmePageVisible = true
                            fetchHfReadme(model.id) 
                        }) {
                            Text("README")
                        }
                    }
                    if (hfFilePickerLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SvgSpinner(modifier = Modifier.size(18.dp))
                            Text("ファイル一覧を取得中...")
                        }
                    } else if (hfFilePickerFiles.isEmpty() && hfMmprojCandidates.isEmpty()) {
                        Text("対応ファイル（.gguf / .task / .litertlm / .mmproj）が見つかりません")
                    } else {
                        // メインモデルファイル一覧
                        if (hfFilePickerFiles.isNotEmpty()) {
                            // mmproj がある場合は自動DLの旨を表示
                            if (hfMmprojCandidates.isNotEmpty()) {
                                val autoMmproj = hfMmprojCandidates
                                    .filter { it.sizeBytes != null }
                                    .minByOrNull { it.sizeBytes!! }
                                    ?: hfMmprojCandidates.first()
                                Text(
                                    text = "📎 mmproj が見つかりました。DL時に「${autoMmproj.path}」も自動ダウンロードし、画像認識が有効になります。",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            hfFilePickerFiles.forEach { file ->
                                HfFileRow(model.id, file)
                            }
                        }
                        // mmproj セクション（同リポジトリのみ）
                        if (hfMmprojCandidates.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "mmproj（マルチモーダル用）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colorResource(id = R.color.text_primary)
                            )
                            hfMmprojCandidates.forEach { file ->
                                HfFileRow(model.id, file, isMmproj = true)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            enabled = hfDownloadingFilePath == null,
                            onClick = { hfFilePickerModel = null }
                        ) { Text("閉じる") }
                    }
                }
            }
        }
    }

    @Composable
    private fun HfFileRow(
        modelId: String,
        file: ModelFileManager.HfModelFile,
        isMmproj: Boolean = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = file.path)
                Text(
                    text = file.sizeBytes?.let { formatBytes(it) } ?: "size: unknown",
                    color = colorResource(id = R.color.text_secondary)
                )
                if (file.sizeBytes != null) {
                    val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(requireContext(), file.sizeBytes, preloadMemoryWarningThresholdPercent, useAvailable = false)
                    val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), file.sizeBytes, preloadMemoryWarningThresholdPercent)
                    if (isMemoryLow || resourceCheck.isStorageLow) {
                        Text(
                            text = when {
                                isMemoryLow && resourceCheck.isStorageLow -> "⚠️ メモリ・ストレージ不足"
                                isMemoryLow -> "⚠️ メモリ不足"
                                else -> "⚠️ ストレージ不足"
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            Button(
                enabled = hfDownloadingFilePath == null && (file.sizeBytes == null || !ModelFileManager.checkDownloadResources(requireContext(), file.sizeBytes, preloadMemoryWarningThresholdPercent).isStorageLow),
                onClick = {
                    if (isMmproj) downloadHfMmprojFile(modelId, file.path)
                    else downloadHfModelFile(modelId, file.path)
                }
            ) {
                val isDownloading = hfDownloadingFilePath == file.path
                Text(if (isDownloading) "DL中..." else "DL")
            }
        }
    }

    @Composable
    private fun ModelListCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "モデル", fontWeight = FontWeight.Bold)

                // 追加モデル ダウンロード中
                if (hfQueuedDownloads.isNotEmpty()) {
                    Text(
                        text = "追加モデル ダウンロード中",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                        items(hfQueuedDownloads, key = { "${it.modelId}/${it.filePath}" }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = colorResource(id = R.color.surface_card)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(text = item.modelId, fontWeight = FontWeight.SemiBold)
                                    Text(text = item.filePath, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                                    if (item.totalBytes > 0L) {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth(),
                                            progress = { item.progress },
                                            color = colorResource(id = R.color.primary),
                                            trackColor = colorResource(id = R.color.context_meter_track)
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = colorResource(id = R.color.primary),
                                            trackColor = colorResource(id = R.color.context_meter_track)
                                        )
                                    }
                                    Text(text = item.statusText, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                                    if (item.isActive) {
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            TextButton(onClick = {
                                                ModelDownloadWorker.cancelCustomHf(
                                                    requireContext(),
                                                    item.modelId,
                                                    item.filePath
                                                )
                                            }) { Text("キャンセル") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 画像生成モデル ダウンロード中
                if (imageModelDownloadStates.isNotEmpty()) {
                    Text(
                        text = "画像生成モデル ダウンロード中",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                        items(imageModelDownloadStates, key = { it.modelId }) { item ->
                            ModelDownloadProgressCard(item) {
                                ModelDownloadWorker.cancelImageModel(requireContext(), item.modelId)
                            }
                        }
                    }
                }

                safetyModelDownloadState?.let { item ->
                    Text(
                        text = "セーフティモデル ダウンロード中",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    ModelDownloadProgressCard(item)
                }

                // 組み込みモデル
                Text(
                    text = "組み込みモデル",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (model in ModelFileManager.LocalModel.entries) {
                        val state = modelStates[model] ?: continue
                        val modelKey = "builtin_${model.name}"
                        val isExpanded = expandedModelKey == modelKey
                        val sizeBytes = getModelSizeBytes(model)
                        ModelAccordionItem(
                            title = state.title,
                            status = state.status,
                            isExpanded = isExpanded,
                            onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                            onDownload = { requestNotificationPermissionForDownload(model) },
                            onDelete = {
                                val ok = ModelFileManager.deleteModel(requireContext(), model)
                                toast(if (ok) "削除しました" else "削除に失敗しました")
                                refreshModelStatus(model)
                                expandedModelKey = null
                            },
                            isDownloading = state.isDownloading,
                            isDownloaded = state.isDownloaded,
                            progress = state.progress,
                            progressText = state.progressText,
                            fileSizeLabel = formatBytes(sizeBytes)
                        )
                    }
                }

                // SDモデル
                if (sdModels.isNotEmpty()) {
                    Text(
                        text = "画像生成モデル (MNN/QNN)",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (model in sdModels) {
                            val modelKey = "sd_${model.path}"
                            val isExpanded = expandedModelKey == modelKey
                            ImageModelAccordionItem(
                                model = model,
                                isExpanded = isExpanded,
                                onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                                onDelete = {
                                    val dir = File(model.path)
                                    val deleted = dir.deleteRecursively()
                                    if (deleted) {
                                        toast("削除しました")
                                        refreshSdModels()
                                        expandedModelKey = null
                                    } else {
                                        toast("削除に失敗しました")
                                    }
                                },
                                onSetActive = {
                                    PreferencesHelper.setSdModelPath(requireContext(), model.path)
                                    toast("アクティブに設定しました")
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "画像生成モデル (MNN/QNN)",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = "上記の「画像生成モデル (MNN/QNN)」カードからダウンロードできます",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }

                // インポートされたモデル
                if (importedTasks.isNotEmpty()) {
                    Text(
                        text = "カスタムモデル",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (model in importedTasks) {
                            val modelKey = "imported_${model.path}"
                            val isExpanded = expandedModelKey == modelKey
                            ImportedModelAccordionItem(
                                model = model,
                                isExpanded = isExpanded,
                                onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                                onDelete = {
                                    val result = ModelFileManager.deleteImportedTask(requireContext(), model.path)
                                    result.onSuccess {
                                        ImportedModelCapabilityStore.clear(requireContext(), model.path)
                                        PromptTemplateStore.clear(requireContext(), model.path)
                                        toast("削除しました")
                                        refreshImportedTasks()
                                        expandedModelKey = null
                                    }.onFailure {
                                        toast("削除に失敗しました: ${it.message}")
                                    }
                                }
                            )
                        }
                    }
                }

                // mmprojファイル管理
                Text(
                    text = "mmproj ファイル",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "ビジョン・オーディオ処理用の投影ファイル（マルチモーダル）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (importedMmprojTasks.isEmpty()) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { mmprojPickerLauncher.launch(arrayOf("*/*")) }
                    ) {
                        Text("mmproj ファイルを追加")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (mmproj in importedMmprojTasks) {
                            val mmprojKey = "mmproj_${mmproj.path}"
                            val isExpanded = expandedModelKey == mmprojKey
                            MmprojAccordionItem(
                                model = mmproj,
                                isExpanded = isExpanded,
                                onToggle = { expandedModelKey = if (isExpanded) null else mmprojKey },
                                onDelete = {
                                    val result = ModelFileManager.deleteImportedTask(requireContext(), mmproj.path)
                                    result.onSuccess {
                                        toast("mmproj ファイルを削除しました")
                                        refreshImportedTasks()
                                        expandedModelKey = null
                                    }.onFailure {
                                        toast("削除に失敗しました: ${it.message}")
                                    }
                                }
                            )
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { mmprojPickerLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Text("+ 追加")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModelAccordionItem(
        title: String,
        status: String,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        onDownload: () -> Unit,
        onDelete: () -> Unit,
        isDownloading: Boolean,
        isDownloaded: Boolean,
        progress: Float = 0f,
        progressText: String = "",
        isMemoryLow: Boolean = false,
        isStorageLow: Boolean = false,
        fileSizeLabel: String? = null
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, fontWeight = FontWeight.SemiBold)
                        fileSizeLabel?.let { size ->
                            Text(
                                text = size,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        // メモリ・ストレージ警告ラベルを名前の下に表示
                        if (isMemoryLow || isStorageLow) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                if (isMemoryLow && isStorageLow) {
                                    Text(
                                        text = "⚠️ メモリ・ストレージ不足",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else if (isMemoryLow) {
                                    Text(
                                        text = "⚠️ メモリ不足",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else if (isStorageLow) {
                                    Text(
                                        text = "⚠️ ストレージ不足",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        // 組み込みモデルはすべて LiteRT-LM を使用
                        if (!isExpanded) {
                            Text(
                                text = "🚀 LiteRT-LM",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.primary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (!isExpanded && isDownloaded && !isDownloading) {
                        Text(
                            text = "✓ ダウンロード済み",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else if (!isExpanded && isDownloading) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // 展開時にエンジン情報を表示
                    Text(
                        text = "🚀 LiteRT-LM",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorResource(id = R.color.primary),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = status, style = MaterialTheme.typography.bodySmall, color = colorResource(id = R.color.text_secondary))
                    if (isDownloading && progressText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { progress },
                            color = colorResource(id = R.color.primary),
                            trackColor = colorResource(id = R.color.context_meter_track)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!isDownloaded || isDownloading) {
                            Button(
                                onClick = onDownload,
                                modifier = Modifier.weight(1f),
                                enabled = !isStorageLow
                            ) {
                                Text(if (isStorageLow) "容量不足" else if (isDownloading) "キャンセル" else "ダウンロード")
                            }
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(id = R.string.delete), fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ImportedModelAccordionItem(
        model: ModelFileManager.ImportedTaskModel,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val isGguf = model.path.lowercase().endsWith(".gguf")
        val ggufMetadataState = ggufCardMetadataStates[model.path]
        LaunchedEffect(isExpanded, model.path) {
            if (isExpanded && isGguf && ggufCardMetadataStates[model.path] == null) {
                ggufCardMetadataStates[model.path] = GgufCardMetadataUiState(loading = true)
                val nextState = withContext(Dispatchers.IO) {
                    runCatching {
                        GgufMetadataReader.readSummary(File(model.path))
                    }.fold(
                        onSuccess = {
                            GgufCardMetadataUiState(
                                architecture = it.architecture,
                                parameterCount = it.parameterCount,
                            )
                        },
                        onFailure = {
                            GgufCardMetadataUiState(
                                errorMessage = it.message ?: "GGUF メタデータを読み取れませんでした"
                            )
                        }
                    )
                }
                ggufCardMetadataStates[model.path] = nextState
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ImportedModelCapabilityStore.resolveDisplayName(
                                requireContext(), model.path, model.shortDisplayName
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        model.hfRepoQualifier?.let { repo ->
                            Text(
                                text = "HF: $repo",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        val fileSize = java.io.File(model.path).length()
                        if (fileSize > 0L) {
                            Text(
                                text = formatBytes(fileSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        // エンジン情報を表示
                        if (!isExpanded) {
                            Text(
                                text = "🚀 " + SettingsHelper.inferenceEngineForModel(model.path),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.primary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (!isExpanded) {
                        Text(
                            text = "✓ インポート済み",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // エンジン情報と種別を展開時に表示
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(id = R.color.bg_session_list))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🚀 " + SettingsHelper.inferenceEngineForModel(model.path),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(id = R.color.primary),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "(" + SettingsHelper.importedModelKindLabel(model.path) + ")",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                    if (isGguf) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colorResource(id = R.color.bg_session_list))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when {
                                ggufMetadataState?.loading == true -> {
                                    Text(
                                        text = "GGUF メタデータを読み込み中...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(id = R.color.text_secondary)
                                    )
                                }
                                ggufMetadataState?.errorMessage != null -> {
                                    Text(
                                        text = "GGUF メタデータ: ${ggufMetadataState.errorMessage}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(id = R.color.text_secondary)
                                    )
                                }
                                ggufMetadataState != null -> {
                                    Text(
                                        text = "アーキテクチャ: ${ggufMetadataState.architecture ?: "不明"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(id = R.color.text_primary)
                                    )
                                    Text(
                                        text = "パラメータ数: ${formatParameterCount(ggufMetadataState.parameterCount)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(id = R.color.text_primary)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "追加済みモデル", style = MaterialTheme.typography.bodySmall, color = colorResource(id = R.color.text_secondary))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = { openModelSettingsDialog(model) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("設定", fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.9f)
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(id = R.string.delete), fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.9f)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MmprojAccordionItem(
        model: ModelFileManager.ImportedTaskModel,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = model.shortDisplayName, fontWeight = FontWeight.SemiBold)
                        model.hfRepoQualifier?.let { repo ->
                            Text(
                                text = "HF: $repo",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        val fileSize = File(model.path).length()
                        if (fileSize > 0L) {
                            Text(
                                text = formatBytes(fileSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (!isExpanded) {
                        Text(
                            text = "✓ mmproj",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(id = R.color.bg_session_list))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "mmproj",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(id = R.color.primary),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "(" + SettingsHelper.importedModelKindLabel(model.path) + ")",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "選択済み mmproj ファイルを表示します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDelete) {
                            Text(stringResource(id = R.string.delete))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ImageModelAccordionItem(
        model: ModelFileManager.ImportedTaskModel,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        onDelete: () -> Unit,
        onSetActive: () -> Unit
    ) {
        val currentActive = PreferencesHelper.getSdModelPath(requireContext())
        val isActive = currentActive == model.path
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = model.shortDisplayName, fontWeight = FontWeight.SemiBold)
                        val modelDir = File(model.path)
                        val dirSize = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        if (dirSize > 0L) {
                            Text(
                                text = formatBytes(dirSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (!isExpanded) {
                            val hasMnn = modelDir.listFiles()?.any { it.name.endsWith(".mnn") } == true
                            val hasQnn = modelDir.listFiles()?.any { it.name.endsWith(".bin") } == true
                            val backend = when {
                                hasQnn -> "QNN (NPU)"
                                hasMnn -> "MNN (GPU)"
                                else -> "Unknown"
                            }
                            Text(
                                text = backend,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.primary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (!isExpanded) {
                        Text(
                            text = if (isActive) "✓ アクティブ" else "✓ DL済み",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) colorResource(id = R.color.primary) else colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val modelDir = File(model.path)
                    val hasMnn = modelDir.listFiles()?.any { it.name.endsWith(".mnn") } == true
                    val hasQnn = modelDir.listFiles()?.any { it.name.endsWith(".bin") } == true
                    val backend = when {
                        hasQnn -> "QNN (NPU)"
                        hasMnn -> "MNN (GPU)"
                        else -> "Unknown"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(id = R.color.bg_session_list))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = backend,
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(id = R.color.primary),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isActive) "現在アクティブなモデル" else "ダウンロード済み",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("削除")
                        }
                    }
                }
            }
        }
    }

    private fun renderHfTokenState() {
        val token = HfAuthManager.getToken(requireContext())
        hfLinked = token.isNotBlank()
        // トークン状態変更時は検索結果をクリア
        if (hfLinked && hfSearchResults.isNotEmpty()) {
            hfSearchResults = emptyList()
            hfSearchNextPageUrl = null
            hfSearchResultsDialogVisible = false
        }
    }

    private fun logoutHf() {
        HfAuthManager.clearToken(requireContext())
        renderHfTokenState()
        toast("ログアウトしました")
    }

    private fun refreshImportedTasks() {
        Log.d("ModelSettings", "refreshImportedTasks: called")
        importedTasks = ModelFileManager.listImportedTaskModels(requireContext())
        importedMmprojTasks = ModelFileManager.listImportedMmprojModels(requireContext())
        val validImportedPaths = importedTasks.mapTo(mutableSetOf()) { it.path }
        ggufCardMetadataStates.keys
            .toList()
            .filter { it !in validImportedPaths }
            .forEach { ggufCardMetadataStates.remove(it) }
        refreshSdModels()
        Log.d("ModelSettings", "refreshImportedTasks: completed, sdModels.size=${sdModels.size}")
    }

    private fun loadImageModels() {
        imageModelsLoading = true
        imageModelsError = null
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.nezumi_ai.data.inference.ImageModelBrowser.fetchAvailableModels()
            }
            result.onSuccess { models ->
                availableImageModels = models
                imageModelsDialogVisible = true
            }.onFailure { e ->
                imageModelsError = "取得失敗: ${e.message}"
            }
            imageModelsLoading = false
        }
    }
    
    private fun downloadImageModel(model: com.nezumi_ai.data.inference.ImageModel) {
        val workName = "image_model_download_${model.id}"
        Log.d("ModelSettings", "downloadImageModel: id=${model.id}, name=${model.name}, displayName=${model.displayName}, url=${model.downloadUrl}")
        val enqueued = ModelDownloadWorker.enqueueImageModel(requireContext(), model.id, model.downloadUrl, model.fileName, model.id)
        if (enqueued) {
            downloadingImageModelIds = downloadingImageModelIds + model.id
            ModelDownloadWorker.enqueueSafetyModel(requireContext())
            toast("ダウンロードを開始しました")
        } else {
            ModelDownloadWorker.enqueueSafetyModel(requireContext())
            toast("すでにダウンロード中です")
        }
    }
    
    private fun refreshSdModels() {
        val models = mutableListOf<ModelFileManager.ImportedTaskModel>()
        val sdModelsDir = File(requireContext().filesDir, "sd_models")
        Log.d("ModelSettings", "refreshSdModels: sdModelsDir=${sdModelsDir.absolutePath}, exists=${sdModelsDir.exists()}")
        if (sdModelsDir.exists() && sdModelsDir.isDirectory) {
            val dirs = sdModelsDir.listFiles()
            Log.d("ModelSettings", "refreshSdModels: found ${dirs?.size ?: 0} items")
            dirs?.forEach { modelDir ->
                Log.d("ModelSettings", "refreshSdModels: checking ${modelDir.name}, isDir=${modelDir.isDirectory}")
                if (!modelDir.isDirectory) return@forEach
                
                // Check if files are directly in this directory
                var targetDir = modelDir
                var files = modelDir.listFiles()
                Log.d("ModelSettings", "refreshSdModels: ${modelDir.name} has ${files?.size ?: 0} files")
                files?.forEach { f -> Log.d("ModelSettings", "  - ${f.name} (isDir=${f.isDirectory})") }
                
                // If there's only one subdirectory, use that instead (nested structure)
                if (files?.size == 1 && files[0].isDirectory) {
                    Log.d("ModelSettings", "refreshSdModels: detected nested structure, using ${files[0].absolutePath}")
                    targetDir = files[0]
                    files = files[0].listFiles()
                    Log.d("ModelSettings", "refreshSdModels: nested dir has ${files?.size ?: 0} files")
                    files?.forEach { f -> Log.d("ModelSettings", "  - ${f.name}") }
                }
                
                val hasMnn = files?.any { it.name.endsWith(".mnn") } == true
                val hasQnn = files?.any { it.name.endsWith(".bin") } == true
                Log.d("ModelSettings", "refreshSdModels: ${modelDir.name} hasMnn=$hasMnn, hasQnn=$hasQnn")
                if (hasMnn || hasQnn) {
                    models.add(ModelFileManager.ImportedTaskModel(
                        path = targetDir.absolutePath,
                        fileNameStem = modelDir.name,
                        shortDisplayName = modelDir.name,
                        hfRepoQualifier = null
                    ))
                    Log.d("ModelSettings", "refreshSdModels: added ${modelDir.name} (path=${targetDir.absolutePath})")
                }
            }
        }
        Log.d("ModelSettings", "refreshSdModels: total models found = ${models.size}")
        sdModels = models
    }

    private fun searchHfModels() {
        val query = hfSearchQuery.trim()
        if (query.isBlank()) {
            hfSearchError = "検索ワードを入力してください"
            hfSearchResults = emptyList()
            return
        }
        // 検索前に最新のトークン状態を確認
        renderHfTokenState()
        
        hfSearchLoading = true
        hfSearchError = null
        hfSearchNextPageUrl = null
        viewLifecycleOwner.lifecycleScope.launch {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://huggingface.co/api/models?search=$encodedQuery&sort=downloads&direction=-1&limit=20&full=true"
            val result = withContext(Dispatchers.IO) {
                ModelFileManager.searchHuggingFaceModelsNextPage(requireContext(), url)
            }
            result.onSuccess { (list, nextUrl) ->
                hfSearchResults = list
                hfSearchNextPageUrl = nextUrl
                hfSearchError = if (list.isEmpty()) "検索結果がありませんでした" else null
                hfSearchResultsDialogVisible = list.isNotEmpty()
            }.onFailure {
                hfSearchResults = emptyList()
                hfSearchNextPageUrl = null
                hfSearchError = "検索失敗: ${it.message}"
                hfSearchResultsDialogVisible = false
            }
            hfSearchLoading = false
        }
    }

    private fun loadMoreHfResults(nextUrl: String) {
        if (hfSearchLoadingMore) return
        hfSearchLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelFileManager.searchHuggingFaceModelsNextPage(requireContext(), nextUrl)
            }
            result.onSuccess { (list, newNextUrl) ->
                hfSearchResults = hfSearchResults + list
                hfSearchNextPageUrl = newNextUrl
            }.onFailure {
                // 追加読み込み失敗は静かに無視（次回スクロール時に再試行可能）
                hfSearchNextPageUrl = null
            }
            hfSearchLoadingMore = false
        }
    }

    private fun openHfFilePicker(result: ModelFileManager.HfModelSearchResult) {
        hfFilePickerModel = result
        hfFilePickerLoading = true
        hfFilePickerFiles = emptyList()
        hfMmprojCandidates = emptyList()
        hfReadmeText = null
        hfReadmeError = null
        hfReadmeLoading = false
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                ModelFileManager.listHuggingFaceDownloadableFiles(requireContext(), result.id)
            }
            files.onSuccess { all ->
                // mmproj ファイルとメインモデルを分離
                val mmprojFiles = all.filter { f ->
                    val lower = f.path.lowercase()
                    lower.contains("mmproj") && (lower.endsWith(".gguf") || lower.endsWith(".mmproj"))
                }
                val mainFiles = all.filter { f ->
                    val lower = f.path.lowercase()
                    !lower.contains("mmproj")
                }
                hfFilePickerFiles = mainFiles
                hfMmprojCandidates = mmprojFiles
            }.onFailure {
                hfFilePickerFiles = emptyList()
                hfMmprojCandidates = emptyList()
                toast("ファイル一覧取得に失敗: ${it.message}")
            }
            hfFilePickerLoading = false
        }
        fetchHfReadme(result.id)
    }

    private fun fetchHfReadme(modelId: String) {
        hfReadmeText = null
        hfReadmeError = null
        hfReadmeLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelFileManager.fetchHuggingFaceReadme(requireContext(), modelId)
            }
            result.onSuccess {
                hfReadmeText = it.ifBlank { "README は空です" }
            }.onFailure {
                hfReadmeError = "README 取得失敗: ${it.message}"
            }
            hfReadmeLoading = false
        }
    }

    private fun openModelSettingsDialog(model: ModelFileManager.ImportedTaskModel) {
        val caps = ImportedModelCapabilityStore.get(requireContext(), model.path)
        capabilityDialogImageEnabled = caps.imageEnabled
        capabilityDialogAudioEnabled = caps.audioEnabled
        capabilityDialogThinkingEnabled = caps.thinkingEnabled
        capabilityDialogToolCallingEnabled = caps.toolCallingEnabled
        capabilityDialogMmprojPath = caps.mmprojPath ?: ""
        capabilityDialogCurrentCapabilities = caps
        settingsDialogDisplayName = caps.displayName ?: model.shortDisplayName
        modelSettingsDialogModel = model
        capabilityDialogRepoMmprojCandidates = emptyList()
        // プロンプトテンプレート選択をロード
        val tplSel = PromptTemplateStore.getSelection(requireContext(), model.path)
        capabilityDialogTemplateMode = tplSel.mode
        capabilityDialogTemplateCustom = tplSel.customTemplate
        capabilityDialogTemplateError = null
        capabilityDialogTemplateExpanded = tplSel.mode != PromptTemplateStore.MODE_AUTO
        viewLifecycleOwner.lifecycleScope.launch {
            settingsDialogStopTokens = withContext(Dispatchers.IO) {
                if (model.path.lowercase().endsWith(".gguf")) {
                    settingsRepository.getStopTokensForModel(model.path).joinToString(", ")
                } else {
                    ""
                }
            }
            val repoQualifier = model.hfRepoQualifier
            if (repoQualifier != null && model.path.lowercase().endsWith(".gguf")) {
                capabilityDialogRepoMmprojLoading = true
                val hfModelId = withContext(Dispatchers.IO) {
                    ModelFileManager.hfModelIdFromRepoQualifier(repoQualifier)
                }
                if (hfModelId != null) {
                    val result = withContext(Dispatchers.IO) {
                        ModelFileManager.findMmprojCandidates(requireContext(), hfModelId)
                    }
                    capabilityDialogRepoMmprojCandidates = result.getOrNull() ?: emptyList()
                }
                capabilityDialogRepoMmprojLoading = false
            }
        }
    }

    private suspend fun persistModelSettings(
        model: ModelFileManager.ImportedTaskModel,
        newCapabilities: ImportedModelCapabilities,
        isGguf: Boolean,
        stopTokens: List<String>
    ) {
        val templateSelection = PromptTemplateStore.TemplateSelection(
            mode = capabilityDialogTemplateMode,
            customTemplate = capabilityDialogTemplateCustom
        )
        withContext(Dispatchers.IO) {
            ImportedModelCapabilityStore.set(
                requireContext(),
                model.path,
                newCapabilities
            )
            if (isGguf) {
                settingsRepository.updateStopTokensForModel(model.path, stopTokens)
            }
            // プロンプトテンプレート選択を保存（GGUF 以外でも設定可能）
            PromptTemplateStore.setSelection(
                requireContext(),
                model.path,
                templateSelection
            )
        }
        modelSettingsDialogModel = null
        refreshImportedTasks()
        toast("設定を保存しました")
    }

    private fun downloadHfModelFile(modelId: String, filePath: String) {
        hfDownloadingFilePath = filePath
        val enqueued = ModelDownloadWorker.enqueueCustomHf(requireContext(), modelId, filePath)
        if (enqueued) {
            if (hfQueuedDownloads.none { it.modelId == modelId && it.filePath == filePath }) {
                hfQueuedDownloads = hfQueuedDownloads + HfQueuedDownloadUiState(
                    modelId = modelId,
                    filePath = filePath,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "待機中",
                    isActive = true
                )
            }
            // mmproj 候補があれば最も小さいものを自動でDLキューに追加（ローカル既存はスキップ）
            val mmprojToDownload = hfMmprojCandidates
                .filter { it.sizeBytes != null }
                .minByOrNull { it.sizeBytes!! }
                ?: hfMmprojCandidates.firstOrNull()
            if (mmprojToDownload != null) {
                val mmprojLocal = ModelFileManager.huggingFaceImportedFile(
                    requireContext(), modelId, mmprojToDownload.path
                )
                if (mmprojLocal.isFile && mmprojLocal.canRead() && mmprojLocal.length() > 0L) {
                    toast("ダウンロードキューに追加しました（mmproj は既に存在します）")
                } else {
                    val mmprojEnqueued = ModelDownloadWorker.enqueueCustomHf(
                        requireContext(), modelId, mmprojToDownload.path
                    )
                    if (mmprojEnqueued &&
                        hfQueuedDownloads.none { it.modelId == modelId && it.filePath == mmprojToDownload.path }) {
                        hfQueuedDownloads = hfQueuedDownloads + HfQueuedDownloadUiState(
                            modelId = modelId,
                            filePath = mmprojToDownload.path,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "待機中",
                            isActive = true
                        )
                    }
                    toast("モデルと mmproj をダウンロードキューに追加しました")
                }
            } else {
                toast("ダウンロードキューに追加しました")
            }
            hfFilePickerModel = null
        } else {
            toast("すでにダウンロード中です")
        }
        hfDownloadingFilePath = null
    }

    /** mmproj ファイルを単独でダウンロードする（メインモデルDL時の自動DLとは別に、後から個別追加する用途） */
    private fun downloadHfMmprojFile(modelId: String, filePath: String) {
        hfDownloadingFilePath = filePath
        val enqueued = ModelDownloadWorker.enqueueCustomHf(requireContext(), modelId, filePath)
        if (enqueued) {
            if (hfQueuedDownloads.none { it.modelId == modelId && it.filePath == filePath }) {
                hfQueuedDownloads = hfQueuedDownloads + HfQueuedDownloadUiState(
                    modelId = modelId,
                    filePath = filePath,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "待機中",
                    isActive = true
                )
            }
            toast("mmproj をダウンロードキューに追加しました")
        } else {
            toast("すでにダウンロード中です")
        }
        hfDownloadingFilePath = null
    }

    private fun observeCustomHfDownloadWork() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(ModelDownloadWorker.TAG_HF_CUSTOM_DOWNLOAD)
            .observe(viewLifecycleOwner) { infos ->
                infos.forEach { info ->
                    if (info.state != WorkInfo.State.SUCCEEDED) return@forEach
                    if (!hfSucceededWorkIds.add(info.id)) return@forEach
                    val outPath = info.outputData.getString(ModelDownloadWorker.KEY_HF_OUTPUT_ABS_PATH)
                        ?: return@forEach
                    val lower = outPath.lowercase()
                    if (!lower.endsWith(".gguf") && !lower.endsWith(".safetensors")) return@forEach
                    val modelId = info.outputData.getString(ModelDownloadWorker.KEY_HF_MODEL_ID) ?: return@forEach
                    val filePath = info.outputData.getString(ModelDownloadWorker.KEY_HF_FILE_PATH) ?: return@forEach
                    val ctx = requireContext()

                    // mmproj ファイルのDL完了: 同じリポジトリのメインモデルにのみ自動紐付け
                    if (lower.contains("mmproj")) {
                        val parentDir = File(outPath).parentFile
                        if (parentDir != null) {
                            // ファイル名プレフィックス（__ より左 = repoQualifier）で同リポジトリ判定
                            val mmprojRepoQualifier = File(outPath).nameWithoutExtension.substringBefore("__")
                            val mainModels = parentDir.listFiles { f ->
                                val n = f.name.lowercase()
                                n.endsWith(".gguf") && !n.contains("mmproj") &&
                                    f.nameWithoutExtension.substringBefore("__") == mmprojRepoQualifier
                            } ?: emptyArray()
                            for (mainModel in mainModels) {
                                val existing = ImportedModelCapabilityStore.get(ctx, mainModel.absolutePath)
                                if (existing.mmprojPath == null || !File(existing.mmprojPath).exists()) {
                                    ImportedModelCapabilityStore.set(
                                        ctx, mainModel.absolutePath,
                                        existing.copy(imageEnabled = true, mmprojPath = outPath)
                                    )
                                }
                            }
                        }
                        return@forEach
                    }

                    if (!ModelFileManager.isProbableStableDiffusionWeights(modelId, filePath)) return@forEach
                    if (PreferencesHelper.getSdModelPath(ctx).isBlank()) {
                        PreferencesHelper.setSdModelPath(ctx, outPath)
                        toast(ctx.getString(R.string.model_sd_hf_toast_set_as_image_gen))
                    } else {
                        toast(ctx.getString(R.string.model_sd_hf_toast_downloaded, File(outPath).name))
                    }
                }
                val mapped = infos.mapNotNull { info ->
                    val kind = info.progress.getString(ModelDownloadWorker.KEY_DOWNLOAD_KIND)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_DOWNLOAD_KIND)
                        ?: ModelDownloadWorker.DOWNLOAD_KIND_HF_CUSTOM
                    if (kind != ModelDownloadWorker.DOWNLOAD_KIND_HF_CUSTOM) return@mapNotNull null

                    val modelId =
                        info.progress.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                            ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                            ?: return@mapNotNull null
                    val filePath =
                        info.progress.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                            ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                            ?: return@mapNotNull null
                    val downloaded =
                        info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                            .takeIf { it > 0L }
                            ?: info.outputData.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                    val total =
                        info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                            .takeIf { it > 0L }
                            ?: info.outputData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                    val status = when (info.state) {
                        WorkInfo.State.ENQUEUED -> "待機中"
                        WorkInfo.State.RUNNING -> if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            "ダウンロード中 $percent% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                        } else {
                            "ダウンロード中"
                        }
                        WorkInfo.State.BLOCKED -> "待機中"
                        WorkInfo.State.SUCCEEDED -> "完了"
                        WorkInfo.State.CANCELLED -> "キャンセル"
                        WorkInfo.State.FAILED -> {
                            val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                            if (error.isNullOrBlank()) "失敗" else "失敗: $error"
                        }
                    }
                    // 完了またはキャンセルされたダウンロードは追加しない
                    if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED) {
                        return@mapNotNull null
                    }
                    HfQueuedDownloadUiState(
                        modelId = modelId,
                        filePath = filePath,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        statusText = status,
                        isActive = info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED
                    )
                }

                hfQueuedDownloads = mapped
                    .sortedWith(compareBy<HfQueuedDownloadUiState> { it.modelId }.thenBy { it.filePath })

                if (infos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    refreshImportedTasks()
                }
            }
    }
    
    private fun observeImageModelDownloadWork() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(ModelDownloadWorker.TAG_IMAGE_MODEL_DOWNLOAD)
            .observe(viewLifecycleOwner) { infos ->
                Log.d("ModelSettings", "observeImageModelDownloadWork: ${infos.size} work infos")
                infos.forEach { info ->
                    Log.d("ModelSettings", "  work ${info.id}: state=${info.state}")
                    
                    // Track succeeded works and refresh models
                    if (info.state == WorkInfo.State.SUCCEEDED) {
                        if (imageModelSucceededWorkIds.add(info.id)) {
                            Log.d("ModelSettings", "  work ${info.id}: NEW SUCCESS, calling refreshSdModels()")
                            refreshSdModels()
                        } else {
                            Log.d("ModelSettings", "  work ${info.id}: already processed")
                        }
                    }
                }
                
                val activeIds = infos.filter { info ->
                    info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                }.mapNotNull { info ->
                    info.progress.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                }.toSet()
                
                Log.d("ModelSettings", "observeImageModelDownloadWork: activeIds=$activeIds")
                downloadingImageModelIds = activeIds
                
                // Build download states list
                val downloadStates = infos.mapNotNull { info ->
                    val modelId = info.progress.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                        ?: return@mapNotNull null
                    val modelName = info.progress.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_NAME)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_NAME)
                        ?: modelId
                    val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: info.outputData.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                    val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: info.outputData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                    
                    val status = when (info.state) {
                        WorkInfo.State.ENQUEUED -> "待機中"
                        WorkInfo.State.RUNNING -> if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            "ダウンロード中 $percent% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                        } else {
                            "ダウンロード中"
                        }
                        WorkInfo.State.BLOCKED -> "待機中"
                        WorkInfo.State.SUCCEEDED -> "完了"
                        WorkInfo.State.CANCELLED -> "キャンセル"
                        WorkInfo.State.FAILED -> {
                            val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                            if (error.isNullOrBlank()) "失敗" else "失敗: $error"
                        }
                    }
                    
                    // 完了またはキャンセルされたダウンロードは追加しない
                    if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED) {
                        return@mapNotNull null
                    }
                    
                    ImageModelDownloadUiState(
                        modelId = modelId,
                        modelName = modelName,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        statusText = status,
                        isActive = info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED
                    )
                }
                
                imageModelDownloadStates = downloadStates
            }
    }

    private fun observeSafetyModelDownloadWork() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.SAFETY_MODEL_WORK_NAME)
            .observe(viewLifecycleOwner) { infos ->
                val info = infos.firstOrNull()
                if (info == null) {
                    safetyModelDownloadState = null
                    return@observe
                }
                if (info.state == WorkInfo.State.SUCCEEDED &&
                    ModelDownloadWorker.isSafetyModelReady(requireContext())) {
                    safetyModelDownloadState = null
                    return@observe
                }
                if (info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED) {
                    safetyModelDownloadState = null
                    return@observe
                }
                if (info.state != WorkInfo.State.ENQUEUED &&
                    info.state != WorkInfo.State.RUNNING &&
                    info.state != WorkInfo.State.BLOCKED) {
                    safetyModelDownloadState = null
                    return@observe
                }
                val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                val status = when (info.state) {
                    WorkInfo.State.ENQUEUED -> "待機中"
                    WorkInfo.State.RUNNING -> if (total > 0L) {
                        val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        "ダウンロード中 $percent% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                    } else {
                        "ダウンロード中"
                    }
                    WorkInfo.State.BLOCKED -> "待機中"
                    else -> "ダウンロード中"
                }
                safetyModelDownloadState = ImageModelDownloadUiState(
                    modelId = ModelDownloadWorker.SAFETY_MODEL_WORK_NAME,
                    modelName = "セーフティモデル (NSFW検出)",
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    statusText = status,
                    isActive = true
                )
            }
    }

    private fun initializeVoicevoxFromSettings() {
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        voicevoxInitializing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                (requireContext().applicationContext as MyApplication)
                    .getVoicevoxManager()
                    .initialize()
            }
            voicevoxInitializing = false
            refreshVoicevoxState()
            toast(if (success) "VOICEVOXを初期化しました" else "VOICEVOXの初期化に失敗しました")
        }
    }

    private fun downloadSelectedVoicevoxModel() {
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        val entry = voicevoxSelectedCatalogEntry
        voicevoxDownloading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                (requireContext().applicationContext as MyApplication)
                    .getVoicevoxManager()
                    .downloadSelectedModel(entry)
            }
            voicevoxDownloading = false
            voicevoxStyleMenuExpanded = false
            refreshVoicevoxState()
            toast(if (success) "${entry.fileName} に切り替えました" else "${entry.fileName} のダウンロードに失敗しました")
        }
    }

    private fun refreshVoicevoxState() {
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        if (!isAdded) return
        val model = voicevoxModelFile()
        val dict = voicevoxDictionaryDir()
        val arm64Runtime = File(requireContext().applicationInfo.nativeLibraryDir, "libvoicevox_onnxruntime.so")

        val arm64Status = runtimeAlignmentStatus(arm64Runtime)
        val x64Status = NativeRuntimeStatus("APK対象外", compatible = true)

        val modelExists = model.isFile
        val dictionaryExists = isValidVoicevoxDictionary(dict)
        val runtimeExists = arm64Runtime.isFile
        val runtimeCompatible = arm64Status.compatible
        val manager = (requireContext().applicationContext as MyApplication).getVoicevoxManager()
        val savedStyleId = manager.getSavedStyleId()
        val selectedModelFileName = manager.getSelectedModelFileName()
        voicevoxSelectedCatalogEntry = VoicevoxManager.modelCatalog.firstOrNull { it.fileName == selectedModelFileName }
            ?: voicevoxSelectedCatalogEntry

        voicevoxState = VoicevoxModelUiState(
            modelExists = modelExists,
            dictionaryExists = dictionaryExists,
            selectedModelFileName = selectedModelFileName,
            modelPath = model.absolutePath,
            modelStatus = if (modelExists) "準備済み" else "未追加",
            dictionaryStatus = if (dictionaryExists) "準備済み" else "未取得",
            runtimeStatus = when {
                !runtimeExists -> "未同梱"
                runtimeCompatible -> "16KB対応"
                else -> "16KB非対応"
            },
            modelDetail = if (modelExists) {
                "$selectedModelFileName / ${model.name} / ${formatBytes(model.length())}"
            } else {
                "3.vvm などのVOICEVOX .vvmモデルを追加できます"
            },
            dictionaryDetail = if (dictionaryExists) {
                dict.absolutePath
            } else {
                "初期化時にOpenJTalk辞書を取得します"
            },
            arm64RuntimeDetail = arm64Status.label,
            x64RuntimeDetail = x64Status.label,
            selectedStyleLabel = "styleId: $savedStyleId",
            message = when {
                !runtimeExists -> "libvoicevox_onnxruntime.so がAPKに含まれていません"
                !runtimeCompatible -> "現在のONNX Runtimeは16KBページサイズ端末では読み込めません"
                !modelExists || !dictionaryExists -> "不足ファイルは初期化時に取得されます"
                else -> "読み上げモデルは利用可能です"
            }
        )

        if (modelExists) {
            viewLifecycleOwner.lifecycleScope.launch {
                val styles = withContext(Dispatchers.IO) {
                    manager.getAvailableStyles()
                }
                if (!isAdded) return@launch
                val currentStyleId = manager.getSavedStyleId()
                val selected = styles.firstOrNull { it.styleId == currentStyleId }
                    ?: styles.firstOrNull { it.styleId == VoicevoxManager.DEFAULT_STYLE_ID }
                    ?: styles.firstOrNull()
                if (selected != null && selected.styleId != currentStyleId) {
                    manager.setSelectedStyleId(selected.styleId)
                }
                voicevoxState = voicevoxState.copy(
                    styles = styles,
                    selectedStyleLabel = selected?.displayName ?: "styleId: $currentStyleId"
                )
            }
        }
    }

    private fun voicevoxModelFile(): File {
        return File(requireContext().filesDir, "voicevox_model.vvm")
    }

    private fun voicevoxDictionaryDir(): File {
        return File(requireContext().filesDir, "open_jtalk_dic_utf_8-1.11")
    }

    private fun isValidVoicevoxDictionary(dir: File): Boolean {
        return dir.isDirectory &&
            File(dir, "sys.dic").isFile &&
            File(dir, "unk.dic").isFile &&
            File(dir, "matrix.bin").isFile
    }

    private fun runtimeAlignmentStatus(file: File): NativeRuntimeStatus {
        if (!file.isFile) return NativeRuntimeStatus("未同梱", compatible = false)
        val aligns = readLoadAlignments(file)
        if (aligns.isEmpty()) return NativeRuntimeStatus("確認不可", compatible = false)
        val compatible = aligns.all { it >= 0x4000L && it % 0x4000L == 0L }
        val alignText = aligns.joinToString { "0x${it.toString(16)}" }
        return NativeRuntimeStatus(
            label = if (compatible) "16KB対応 ($alignText)" else "16KB非対応 ($alignText)",
            compatible = compatible
        )
    }

    private fun readLoadAlignments(file: File): List<Long> {
        val bytes = file.readBytes()
        if (bytes.size < 64 ||
            bytes[0] != 0x7f.toByte() ||
            bytes[1] != 0x45.toByte() ||
            bytes[2] != 0x4c.toByte() ||
            bytes[3] != 0x46.toByte() ||
            bytes[4] != 2.toByte() ||
            bytes[5] != 1.toByte()
        ) {
            return emptyList()
        }
        val phoff = readU64Le(bytes, 32)
        val phentsize = readU16Le(bytes, 54)
        val phnum = readU16Le(bytes, 56)
        val aligns = mutableListOf<Long>()
        repeat(phnum) { index ->
            val offset = (phoff + index * phentsize).toInt()
            if (offset + 56 <= bytes.size && readU32Le(bytes, offset) == 1L) {
                aligns += readU64Le(bytes, offset + 48)
            }
        }
        return aligns
    }

    private fun readU16Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24))
    }

    private fun readU64Le(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toLong() and 0xffL) shl (8 * i))
        }
        return result
    }

    private fun requestNotificationPermissionForDownload(model: ModelFileManager.LocalModel) {
        // ダウンロード前にメモリチェックして警告を設定
        val sizeBytes = getModelSizeBytes(model)
        val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(
            requireContext(),
            sizeBytes,
            preloadMemoryWarningThresholdPercent,
            useAvailable = false
        )

        if (isMemoryLow) {
            val sysMemInfo = MemoryObserver.getSystemMemoryInfoSync(requireContext())
            val warning = "このモデルは現在のデバイス総メモリ (${sysMemInfo.totalMemoryMB / 1024}GB) では動作が不安定になる可能性があります。ダウンロード後の使用時にクラッシュやフリーズが発生する場合があります。"
            modelStates[model]?.memoryWarning = warning
        } else {
            modelStates[model]?.memoryWarning = null
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownloadPermissionModel = model
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        runModelDownload(model)
    }

    private fun runModelDownload(model: ModelFileManager.LocalModel) {
        val enqueued = ModelDownloadWorker.enqueue(requireContext(), model)
        if (!enqueued) toast("すでにダウンロード中です")
    }

    private fun refreshModelStatus(model: ModelFileManager.LocalModel? = null) {
        val targets = model?.let { listOf(it) } ?: ModelFileManager.LocalModel.entries
        targets.forEach {
            val downloaded = ModelFileManager.isDownloaded(requireContext(), it)
            val state = modelStates[it] ?: return@forEach
            state.isDownloaded = downloaded
            state.status = if (downloaded) "ダウンロード済み" else "未ダウンロード"
            if (!state.isDownloading) {
                state.progressText = ""
                state.progress = 0f
                state.showAccessButton = false
            }
            
            // メモリチェックを実行して警告を設定
            val sizeBytes = getModelSizeBytes(it)
            val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(
                requireContext(),
                sizeBytes,
                preloadMemoryWarningThresholdPercent,
                useAvailable = false
            )

            if (isMemoryLow) {
                val sysMemInfo = MemoryObserver.getSystemMemoryInfoSync(requireContext())
                state.memoryWarning = "このモデルは現在のデバイス総メモリ (${sysMemInfo.totalMemoryMB / 1024}GB) では動作が不安定になる可能性があります。" + 
                    if (downloaded) "使用時にクラッシュやフリーズが発生する場合があります。" 
                    else "ダウンロード後の使用時にクラッシュやフリーズが発生する場合があります。"
            } else {
                state.memoryWarning = null
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

    private fun renderDownloadState(model: ModelFileManager.LocalModel, workInfo: WorkInfo?) {
        val state = modelStates[model] ?: return
        if (workInfo == null) {
            state.isDownloading = false
            refreshModelStatus(model)
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
                    state.status = "ダウンロード中"
                } else {
                    state.progressText = "準備中..."
                    state.status = "ダウンロード待機中"
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                state.isDownloading = false
                state.progress = 1f
                state.progressText = ""
                state.showAccessButton = false
                state.isDownloaded = true
                
                // ダウンロード完了後にメモリチェック
                val sizeBytes = getModelSizeBytes(model)
                val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(
                    requireContext(),
                    sizeBytes,
                    preloadMemoryWarningThresholdPercent,
                    useAvailable = false
                )

                if (isMemoryLow) {
                    val sysMemInfo = MemoryObserver.getSystemMemoryInfoSync(requireContext())
                    state.memoryWarning = "このモデルは現在のデバイス総メモリ (${sysMemInfo.totalMemoryMB / 1024}GB) では動作が不安定になる可能性があります。使用時にクラッシュやフリーズが発生する場合があります。"
                } else {
                    state.memoryWarning = null
                }
                
                refreshModelStatus(model)
            }
            WorkInfo.State.FAILED -> {
                state.isDownloading = false
                state.progressText = ""
                val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE) ?: "ダウンロード失敗"
                state.status = "失敗: $error"
                state.showAccessButton = error.contains("HTTP 403", ignoreCase = true)
                state.memoryWarning = null
            }
            WorkInfo.State.CANCELLED -> {
                state.isDownloading = false
                state.progressText = ""
                state.memoryWarning = null
                refreshModelStatus(model)
            }
        }
    }

    private fun startOAuthLogin() {
        if (hfLinked) {
            toast("すでに連携済みです。切り替える場合は先にログアウトしてください")
            return
        }
        if (ProjectConfig.HF_CLIENT_ID == "REPLACE_WITH_HF_CLIENT_ID") {
            toast("ProjectConfig.HF_CLIENT_ID を設定してください")
            return
        }
        val request = HfOAuthManager.buildAuthorizationRequest()
        val intent = authService?.getAuthorizationRequestIntent(request) ?: return
        authLauncher.launch(intent)
    }

    private fun exchangeToken(response: AuthorizationResponse) {
        val tokenRequest = HfOAuthManager.buildTokenRequest(response)
        val service = authService ?: return
        HfOAuthManager.performTokenRequest(service, tokenRequest) { accessToken, error ->
            requireActivity().runOnUiThread {
                if (accessToken.isNullOrBlank()) {
                    toast("トークン取得失敗: $error")
                    return@runOnUiThread
                }
                HfAuthManager.setToken(requireContext(), accessToken)
                renderHfTokenState()
                toast("OAuthログイン成功")
            }
        }
    }

    private fun openHfModelAccessPage(model: ModelFileManager.LocalModel) {
        val url = ModelFileManager.previewTreeUrl(model)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            toast("ブラウザを起動できませんでした")
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.2fGB", gb)
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun formatParameterCount(parameterCount: Long?): String {
        val count = parameterCount ?: return "不明"
        if (count <= 0L) return "不明"
        val (divisor, suffix) = when {
            count >= 1_000_000_000_000L -> 1_000_000_000_000.0 to "T"
            count >= 1_000_000_000L -> 1_000_000_000.0 to "B"
            count >= 1_000_000L -> 1_000_000.0 to "M"
            count >= 1_000L -> 1_000.0 to "K"
            else -> 1.0 to ""
        }
        return if (suffix.isEmpty()) {
            String.format(Locale.US, "%,d", count)
        } else {
            String.format(Locale.US, "%.2f%s (%,d)", count / divisor, suffix, count)
        }
    }

    private fun titleFor(model: ModelFileManager.LocalModel): String {
        return when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "Gemma 3n E2B"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "Gemma 3n E4B"
            ModelFileManager.LocalModel.GEMMA4_2B -> "Gemma 4 2B"
            ModelFileManager.LocalModel.GEMMA4_4B -> "Gemma 4 4B"
        }
    }

    private fun getModelSizeBytes(model: ModelFileManager.LocalModel): Long {
        return when (model) {
            ModelFileManager.LocalModel.GEMMA4_2B -> 2_400_000_000L  // 約 2.4GB
            ModelFileManager.LocalModel.GEMMA4_4B -> 8_000_000_000L  // 約 8GB (12GB端末推奨)
            ModelFileManager.LocalModel.GEMMA3N_2B -> 2_000_000_000L  // 約 2GB
            ModelFileManager.LocalModel.GEMMA3N_4B -> 8_000_000_000L  // 約 4GB
        }
    }

    override fun onDestroyView() {
        authService?.dispose()
        authService = null
        super.onDestroyView()
    }

    private data class GgufCardMetadataUiState(
        val loading: Boolean = false,
        val architecture: String? = null,
        val parameterCount: Long? = null,
        val errorMessage: String? = null,
    )

    private class ModelUiState(val title: String) {
        var status by mutableStateOf("未ダウンロード")
        var progress by mutableFloatStateOf(0f)
        var progressText by mutableStateOf("")
        var isDownloading by mutableStateOf(false)
        var showAccessButton by mutableStateOf(false)
        var isDownloaded by mutableStateOf(false)
        var memoryWarning by mutableStateOf<String?>(null)
    }

    private data class HfQueuedDownloadUiState(
        val modelId: String,
        val filePath: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val statusText: String,
        val isActive: Boolean
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }
    
    @Composable
    private fun ModelDownloadProgressCard(
        item: ImageModelDownloadUiState,
        onCancel: (() -> Unit)? = null
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = item.modelName, fontWeight = FontWeight.SemiBold)
                if (item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = { item.progress },
                        color = colorResource(id = R.color.primary),
                        trackColor = colorResource(id = R.color.context_meter_track)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colorResource(id = R.color.primary),
                        trackColor = colorResource(id = R.color.context_meter_track)
                    )
                }
                Text(
                    text = item.statusText,
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                if (item.isActive && onCancel != null) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onCancel) { Text("キャンセル") }
                    }
                }
            }
        }
    }

    private data class ImageModelDownloadUiState(
        val modelId: String,
        val modelName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val statusText: String,
        val isActive: Boolean
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    private data class VoicevoxModelUiState(
        val modelExists: Boolean = false,
        val dictionaryExists: Boolean = false,
        val selectedModelFileName: String = "",
        val modelPath: String = "",
        val modelStatus: String = "未確認",
        val dictionaryStatus: String = "未確認",
        val runtimeStatus: String = "未確認",
        val modelDetail: String = "確認中...",
        val dictionaryDetail: String = "確認中...",
        val arm64RuntimeDetail: String = "未確認",
        val x64RuntimeDetail: String = "未確認",
        val styles: List<VoicevoxManager.VoiceStyle> = emptyList(),
        val selectedStyleLabel: String = "未選択",
        val message: String? = null
    )

    private data class NativeRuntimeStatus(
        val label: String,
        val compatible: Boolean
    )

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

    @Composable
    private fun HfReadmePage() {
        val isDark = isSystemInDarkTheme()
        val textColor = colorResource(id = R.color.text_primary)
        val linkSpan = SpanStyle(color = textColor, textDecoration = TextDecoration.Underline)
        val linkStyle = TextLinkStyles(
            style = linkSpan,
            hoveredStyle = linkSpan,
            pressedStyle = linkSpan,
            focusedStyle = linkSpan
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(colorResource(id = R.color.bg_session_list))
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "README",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (isDark) Color.White else LocalContentColor.current
                    )
                    Text(
                        text = hfReadmePageTitle,
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(
                    onClick = { hfReadmePageVisible = false }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = if (isDark) Color.White else colorResource(id = R.color.text_primary)
                    )
                }
            }

            // Content
            if (hfReadmeLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SvgSpinner()
                    Text("READMEを読み込み中...", modifier = Modifier.padding(top = 8.dp), color = if (isDark) Color.White else LocalContentColor.current)
                }
            } else if (hfReadmeError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("エラー: ${hfReadmeError}", color = if (isDark) Color.White else colorResource(id = R.color.text_primary))
                }
            } else if (hfReadmeText != null) {
                CompositionLocalProvider(
                    LocalContentColor provides (if (isDark) androidx.compose.ui.graphics.Color.White else LocalContentColor.current)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownLatexText(
                            text = hfReadmeText!!,
                            modifier = Modifier.fillMaxWidth(),
                            linkStyle = linkStyle
                        )
                    }
                }
            }
        }
    }

    /**
     * ★ ローカルインポートモデルの「検索＋並び替え」バー。
     *
     * importedTasks が 0 件のときも表示されるように、表示条件不要で常設。
     */
    @Composable
    private fun ImportedModelsFilterBar() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = importedSearchQuery,
                onValueChange = { importedSearchQuery = it },
                label = { Text("カスタムモデルを検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (importedSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { importedSearchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "クリア")
                        }
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        // 名前順 → 更新順 → サイズ順 をトグル循環
                        importedSortKey = when (importedSortKey) {
                            ImportedSortKey.NAME -> ImportedSortKey.UPDATED
                            ImportedSortKey.UPDATED -> ImportedSortKey.SIZE
                            ImportedSortKey.SIZE -> ImportedSortKey.NAME
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("並び替え: ${importedSortKey.label}")
                }
                OutlinedButton(
                    onClick = { importedSortDescending = !importedSortDescending }
                ) {
                    Text(if (importedSortDescending) "降順" else "昇順")
                }
            }
        }
    }

    /**
     * ★ importedTasks に「検索クエリ」と「並び替え」を適用して返す。
     *
     * - 検索: shortDisplayName / fileNameStem / hfRepoQualifier に部分一致（大文字小文字無視）
     * - 並び替え: NAME / UPDATED (lastModified) / SIZE (length) × 昇順 / 降順
     */
    private fun applyImportedModelFilters(
        tasks: List<ModelFileManager.ImportedTaskModel>
    ): List<ModelFileManager.ImportedTaskModel> {
        val q = importedSearchQuery.trim()
        val filtered = if (q.isEmpty()) {
            tasks
        } else {
            tasks.filter { m ->
                m.shortDisplayName.contains(q, ignoreCase = true) ||
                    m.fileNameStem.contains(q, ignoreCase = true) ||
                    (m.hfRepoQualifier?.contains(q, ignoreCase = true) == true)
            }
        }
        val comparator: Comparator<ModelFileManager.ImportedTaskModel> = when (importedSortKey) {
            ImportedSortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.shortDisplayName }
            ImportedSortKey.UPDATED -> compareBy { runCatching { java.io.File(it.path).lastModified() }.getOrDefault(0L) }
            ImportedSortKey.SIZE -> compareBy { runCatching { java.io.File(it.path).length() }.getOrDefault(0L) }
        }
        val sorted = filtered.sortedWith(comparator)
        return if (importedSortDescending) sorted.reversed() else sorted
    }
}
