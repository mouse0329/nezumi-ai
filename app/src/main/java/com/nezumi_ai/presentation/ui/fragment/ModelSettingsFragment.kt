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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.HfAuthManager
import com.nezumi_ai.data.inference.HfOAuthManager
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ProjectConfig
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.presentation.ui.helper.SettingsHelper
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
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
    private var pendingDownloadPermissionModel: ModelFileManager.LocalModel? = null

    private var hfLinked by mutableStateOf(false)
    private var hfSearchQuery by mutableStateOf("")
    private var hfSearchLoading by mutableStateOf(false)
    private var hfSearchError by mutableStateOf<String?>(null)
    private var hfSearchResults by mutableStateOf<List<ModelFileManager.HfModelSearchResult>>(emptyList())
    private var hfSearchResultsDialogVisible by mutableStateOf(false)
    private var hfFilePickerModel by mutableStateOf<ModelFileManager.HfModelSearchResult?>(null)
    private var hfFilePickerLoading by mutableStateOf(false)
    private var hfFilePickerFiles by mutableStateOf<List<ModelFileManager.HfModelFile>>(emptyList())
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
    private var isImportingModel by mutableStateOf(false)
    private var capabilityDialogModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var capabilityDialogImageEnabled by mutableStateOf(false)
    private var capabilityDialogAudioEnabled by mutableStateOf(false)
    private var capabilityDialogThinkingEnabled by mutableStateOf(false)
    private var capabilityDialogMmprojPath by mutableStateOf("")
    private var capabilityDialogCurrentCapabilities by mutableStateOf<ImportedModelCapabilities?>(null)
    private var capabilityDialogModelType by mutableStateOf<ModelType>(ModelType.LLM)
    private var mmprojDropdownExpanded by mutableStateOf(false)
    
    private var imageModelsLoading by mutableStateOf(false)
    private var imageModelsError by mutableStateOf<String?>(null)
    private var availableImageModels by mutableStateOf<List<com.nezumi_ai.data.inference.ImageModel>>(emptyList())
    private var imageModelsDialogVisible by mutableStateOf(false)
    private var imageModelSearchQuery by mutableStateOf("")
    private var downloadingImageModelIds by mutableStateOf<Set<String>>(emptySet())
    private var imageModelDownloadStates by mutableStateOf<List<ImageModelDownloadUiState>>(emptyList())
    private var voicevoxState by mutableStateOf(VoicevoxModelUiState())
    private var voicevoxInitializing by mutableStateOf(false)
    private var voicevoxDownloading by mutableStateOf(false)
    private var voicevoxStyleMenuExpanded by mutableStateOf(false)
    private var voicevoxModelMenuExpanded by mutableStateOf(false)
    private var voicevoxSelectedCatalogEntry by mutableStateOf(
        VoicevoxManager.modelCatalog.firstOrNull { it.fileName == "3.vvm" }
            ?: VoicevoxManager.modelCatalog.first()
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
    private var stopTokensDialogModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var stopTokensDialogText by mutableStateOf("")
    private var renameDialogModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var renameDialogText by mutableStateOf("")
    private var expandedModelKey by mutableStateOf<String?>(null)

    private var sdModels by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())
    
    private var selectedTab by mutableStateOf(ModelType.LLM)

    private val modelStates = mutableStateMapOf<ModelFileManager.LocalModel, ModelUiState>()

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
                        ModelFileManager.importTaskFromUri(requireContext(), uri).getOrThrow().absolutePath
                    }
                    toast("モデルを追加しました: ${File(modelPath).name}")
                    refreshImportedTasks()
                    val imported = ModelFileManager.ImportedTaskModel(
                        path = modelPath,
                        fileNameStem = File(modelPath).nameWithoutExtension,
                        shortDisplayName = File(modelPath).nameWithoutExtension,
                        hfRepoQualifier = null
                    )
                    capabilityDialogModel = imported
                    capabilityDialogImageEnabled = false
                    capabilityDialogAudioEnabled = false
                    capabilityDialogThinkingEnabled = false
                    capabilityDialogMmprojPath = ""
                    capabilityDialogModelType = if (modelPath.lowercase().endsWith(".gguf")) {
                        ModelType.LLM
                    } else {
                        ModelType.LLM
                    }
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
        settingsRepository = SettingsRepository(db.settingsDao(), db.chatSessionDao(), requireContext().applicationContext)
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
        capabilityDialogModel?.let { model ->
            ImportedCapabilityDialog(model)
        }
        stopTokensDialogModel?.let { model ->
            StopTokensDialog(model)
        }
        renameDialogModel?.let { model ->
            RenameImportedDialog(model)
        }
        if (hfSearchResultsDialogVisible) {
            HfSearchResultsContent()
            return
        }
        if (imageModelsDialogVisible) {
            ImageModelsDialogContent()
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
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
                                progressText = state.progressText
                            )
                        }
                    }
                    if (importedTasks.isNotEmpty()) {
                        item {
                            Text(
                                text = "カスタムモデル",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.text_secondary),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
                            )
                        }
                        items(importedTasks) { model ->
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colorResource(id = R.color.primary)
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
    private fun ImportedCapabilityDialog(model: ModelFileManager.ImportedTaskModel) {
        Dialog(onDismissRequest = {}) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "追加モデルの機能設定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = model.shortDisplayName,
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
                    // thinking（GGUFのみ）
                    if (capabilityDialogModel?.path?.lowercase()?.endsWith(".gguf") == true) {
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
                    Text(
                        text = "標準は画像・音声とも無効です",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    // mmproj設定（GGUFのみ）
                    if (capabilityDialogModel?.path?.lowercase()?.endsWith(".gguf") == true) {
                        Text(
                            text = "mmproj（マルチモーダル）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (capabilityDialogMmprojPath.isNotBlank()) {
                            Text(
                                text = java.io.File(capabilityDialogMmprojPath).name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
                                importedMmprojTasks.forEach { mmprojModel ->
                                    DropdownMenuItem(
                                        text = { Text(mmprojModel.shortDisplayName) },
                                        onClick = {
                                            capabilityDialogMmprojPath = mmprojModel.path
                                            mmprojDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            text = "統合型（単一 GGUF）では未指定時に同じファイルからビジョンを初期化します。LLaVA 等は公式ペアの別 mmproj .gguf を指定してください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = {
                            ImportedModelCapabilityStore.set(
                                requireContext(),
                                model.path,
                                ImportedModelCapabilities(
                                    imageEnabled = capabilityDialogImageEnabled,
                                    audioEnabled = capabilityDialogAudioEnabled,
                                    mmprojPath = capabilityDialogMmprojPath.ifBlank { null },
                                    thinkingEnabled = capabilityDialogThinkingEnabled
                                )
                            )
                            capabilityDialogModel = null
                            refreshImportedTasks()
                            toast("モデル機能設定を保存しました")
                        }) {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StopTokensDialog(model: ModelFileManager.ImportedTaskModel) {
        Dialog(onDismissRequest = { stopTokensDialogModel = null }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ストップトークン",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = model.shortDisplayName,
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
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = stopTokensDialogText,
                        onValueChange = { stopTokensDialogText = it },
                        label = { Text("トークン（カンマ区切り）") },
                        placeholder = { Text("<|im_end|>,<|im_start|>") },
                        minLines = 2
                    )
                    Text(
                        text = "カンマ区切りで複数指定できます。デフォルトのストップトークンに追加されます。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = { stopTokensDialogModel = null }) { Text("キャンセル") }
                        Button(onClick = {
                            val tokens = stopTokensDialogText
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    settingsRepository.updateStopTokensForModel(model.path, tokens)
                                }
                                toast("ストップトークンを保存しました")
                                stopTokensDialogModel = null
                            }
                        }) { Text("保存") }
                    }
                }
            }
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
                TabButton(
                    text = "読み上げ",
                    selected = selectedTab == ModelType.TEXT_TO_SPEECH,
                    onClick = { selectedTab = ModelType.TEXT_TO_SPEECH },
                    modifier = Modifier.weight(1f)
                )
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
                            Text(text = item.statusText, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                            if (item.isActive) {
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        ModelDownloadWorker.cancelImageModel(
                                            requireContext(),
                                            item.modelId
                                        )
                                    }) { Text("キャンセル") }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (hfQueuedDownloads.isEmpty() && imageModelDownloadStates.isEmpty()) {
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
                val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), sizeBytes)
                
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
                    isStorageLow = resourceCheck.isStorageLow
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
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
                if (MemoryObserver.isMemoryLowForFileSize(requireContext(), model.size)) {
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
                LazyColumn(
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
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("ファイル一覧を取得中...")
                        }
                    } else if (hfFilePickerFiles.isEmpty()) {
                        Text("対応ファイル（.gguf / .task / .litertlm / .mmproj）が見つかりません")
                    } else {
                        hfFilePickerFiles.forEach { file ->
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
                                        val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(requireContext(), file.sizeBytes)
                                        val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), file.sizeBytes)
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
                                    enabled = hfDownloadingFilePath == null && (file.sizeBytes == null || !ModelFileManager.checkDownloadResources(requireContext(), file.sizeBytes).isStorageLow),
                                    onClick = { downloadHfModelFile(model.id, file.path) }
                                ) {
                                    val isDownloading = hfDownloadingFilePath == file.path
                                    Text(if (isDownloading) "DL中..." else "DL")
                                }
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
                                    Text(text = item.statusText, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                                    if (item.isActive) {
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            TextButton(onClick = {
                                                ModelDownloadWorker.cancelImageModel(
                                                    requireContext(),
                                                    item.modelId
                                                )
                                            }) { Text("キャンセル") }
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                            progressText = state.progressText
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
    private fun RenameImportedDialog(model: ModelFileManager.ImportedTaskModel) {
        Dialog(onDismissRequest = { renameDialogModel = null }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "モデルファイル名の変更",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "拡張子は変わりません。記号 \\ / : * ? \" < > | は使えません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = model.shortDisplayName,
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
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = renameDialogText,
                        onValueChange = { renameDialogText = it },
                        label = { Text("新しい名前（拡張子なし）") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = { renameDialogModel = null }) { Text("キャンセル") }
                        Button(onClick = {
                            val ctx = requireContext()
                            val oldPath = model.path
                            val stem = renameDialogText
                            viewLifecycleOwner.lifecycleScope.launch {
                                val renamed = withContext(Dispatchers.IO) {
                                    ModelFileManager.renameImportedTask(ctx, oldPath, stem)
                                }
                                renamed.onSuccess { newFile ->
                                    withContext(Dispatchers.IO) {
                                        ImportedModelCapabilityStore.migrateModelPath(ctx, oldPath, newFile.absolutePath)
                                        settingsRepository.remapImportedModelPath(oldPath, newFile.absolutePath)
                                    }
                                    renameDialogModel = null
                                    expandedModelKey = null
                                    refreshImportedTasks()
                                    toast("名前を変更しました")
                                }.onFailure {
                                    toast("名前変更に失敗: ${it.message}")
                                }
                            }
                        }) { Text("保存") }
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
        isStorageLow: Boolean = false
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
                                        color = colorResource(id = R.color.nezumi_primary_container),
                                        fontWeight = FontWeight.Bold
                                    )
                                } else if (isMemoryLow) {
                                    Text(
                                        text = "⚠️ メモリ不足",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorResource(id = R.color.nezumi_primary_container),
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "追加済みモデル", style = MaterialTheme.typography.bodySmall, color = colorResource(id = R.color.text_secondary))
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    val caps = ImportedModelCapabilityStore.get(requireContext(), model.path)
                                    capabilityDialogImageEnabled = caps.imageEnabled
                                    capabilityDialogAudioEnabled = caps.audioEnabled
                                    capabilityDialogThinkingEnabled = caps.thinkingEnabled
                                    capabilityDialogMmprojPath = caps.mmprojPath ?: ""
                                    capabilityDialogCurrentCapabilities = caps
                                    capabilityDialogModel = model
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("機能設定", fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
                            }
                            TextButton(
                                onClick = {
                                    renameDialogText = model.fileNameStem
                                    renameDialogModel = model
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("名前変更", fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val tokens = withContext(Dispatchers.IO) {
                                            settingsRepository.getStopTokensForModel(model.path)
                                        }
                                        stopTokensDialogText = tokens.joinToString(", ")
                                        stopTokensDialogModel = model
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("ストップトークン", fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
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
                        if (!isExpanded) {
                            val modelDir = File(model.path)
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
            toast("ダウンロードを開始しました")
        } else {
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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelFileManager.searchHuggingFaceModels(requireContext(), query)
            }
            result.onSuccess { list ->
                hfSearchResults = list
                hfSearchError = if (list.isEmpty()) "検索結果がありませんでした" else null
                hfSearchResultsDialogVisible = list.isNotEmpty()
            }.onFailure {
                hfSearchResults = emptyList()
                hfSearchError = "検索失敗: ${it.message}"
                hfSearchResultsDialogVisible = false
            }
            hfSearchLoading = false
        }
    }

    private fun openHfFilePicker(result: ModelFileManager.HfModelSearchResult) {
        hfFilePickerModel = result
        hfFilePickerLoading = true
        hfFilePickerFiles = emptyList()
        hfReadmeText = null
        hfReadmeError = null
        hfReadmeLoading = false
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                ModelFileManager.listHuggingFaceDownloadableFiles(requireContext(), result.id)
            }
            files.onSuccess {
                hfFilePickerFiles = it
            }.onFailure {
                hfFilePickerFiles = emptyList()
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
            toast("ダウンロードキューに追加しました")
            hfFilePickerModel = null
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
                    if (!ModelFileManager.isProbableStableDiffusionWeights(modelId, filePath)) return@forEach
                    val ctx = requireContext()
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

    private fun initializeVoicevoxFromSettings() {
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
        val modelName = when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "GEMMA3-2B"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "GEMMA3-4B"
            ModelFileManager.LocalModel.GEMMA4_2B -> "GEMMA4-2B"
            ModelFileManager.LocalModel.GEMMA4_4B -> "GEMMA4-4B"
        }
        
        if (MemoryObserver.isMemoryLow(requireContext(), modelName)) {
            val sysMemInfo = MemoryObserver.getSystemMemoryInfo(requireContext())
            val warning = "このモデルは現在のデバイスメモリ (${sysMemInfo.totalMemoryMB / 1024}GB) では動作が不安定になる可能性があります。ダウンロード後の使用時にクラッシュやフリーズが発生する場合があります。"
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
            val modelName = when (it) {
                ModelFileManager.LocalModel.GEMMA3N_2B -> "GEMMA3-2B"
                ModelFileManager.LocalModel.GEMMA3N_4B -> "GEMMA3-4B"
                ModelFileManager.LocalModel.GEMMA4_2B -> "GEMMA4-2B"
                ModelFileManager.LocalModel.GEMMA4_4B -> "GEMMA4-4B"
            }
            
            if (MemoryObserver.isMemoryLow(requireContext(), modelName)) {
                val sysMemInfo = MemoryObserver.getSystemMemoryInfo(requireContext())
                state.memoryWarning = "このモデルは現在のデバイスメモリ (${sysMemInfo.totalMemoryMB / 1024}GB) では動作が不安定になる可能性があります。" + 
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
                val modelName = when (model) {
                    ModelFileManager.LocalModel.GEMMA3N_2B -> "GEMMA3-2B"
                    ModelFileManager.LocalModel.GEMMA3N_4B -> "GEMMA3-4B"
                    ModelFileManager.LocalModel.GEMMA4_2B -> "GEMMA4-2B"
                    ModelFileManager.LocalModel.GEMMA4_4B -> "GEMMA4-4B"
                }
                
                if (MemoryObserver.isMemoryLow(requireContext(), modelName)) {
                    val sysMemInfo = MemoryObserver.getSystemMemoryInfo(requireContext())
                    state.memoryWarning = "このモデルは現在のデバイスメモリ (${sysMemInfo.totalMemoryMB / 1024}GB) では動作が不安定になる可能性があります。使用時にクラッシュやフリーズが発生する場合があります。"
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
            ModelFileManager.LocalModel.GEMMA4_4B -> 4_800_000_000L  // 約 4.8GB
            ModelFileManager.LocalModel.GEMMA3N_2B -> 2_000_000_000L  // 約 2GB
            ModelFileManager.LocalModel.GEMMA3N_4B -> 4_000_000_000L  // 約 4GB
        }
    }

    override fun onDestroyView() {
        authService?.dispose()
        authService = null
        super.onDestroyView()
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    CircularProgressIndicator()
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
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
