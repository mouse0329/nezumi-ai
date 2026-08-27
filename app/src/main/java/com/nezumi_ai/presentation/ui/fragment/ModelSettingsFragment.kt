package com.nezumi_ai.presentation.ui.fragment

import com.nezumi_ai.data.inference.cloud.*  // *ForContext 拡張関数 (shared/androidMain) の解決用

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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FloatingActionButton
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import com.nezumi_ai.data.inference.RecommendedModelCatalog
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
import com.nezumi_ai.sd.SdModelLayout
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudModelId
import com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry
import com.nezumi_ai.data.inference.cloud.LocalModelListFetcher
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpFontFamily
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpTypography

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
    // Bug fix: 検索前から「検索結果がありません」と出ていた。
    //   未検索 / 検索中 / 0件ヒットを区別するためのフラグ。
    private var hfHasSearched by mutableStateOf(false)
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
    // ダウンロードカードの「未完なのに消える」対策:
    //   WorkInfo からは progress/outputData しか読めず、ENQUEUED 状態やプロセス再起動直後など
    //   setProgress がまだ走っていないタイミングでは modelId/filePath が取れずにカードが消えていた。
    //   一度表示したカードはキーごとにキャッシュし、WorkInfo から読めない期間は最後の既知状態を保持する。
    //   SUCCEEDED/FAILED/CANCELLED になったら削除。
    private val hfDownloadCardCache = mutableMapOf<String, HfQueuedDownloadUiState>()
    private val imageModelDownloadCardCache = mutableMapOf<String, ImageModelDownloadUiState>()
    private var importedTasks by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())
    private var importedMmprojTasks by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())

 // ローカルインポートモデルの「整理」UI 状態
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
    private var voicevoxDownloadState by mutableStateOf<VoicevoxDownloadUiState?>(null)
    private var voicevoxStyleMenuExpanded by mutableStateOf(false)

    // モデル管理画面の右下 FAB メニュの開閉状態。
    // このメニュに「モデルをインポート / mmproj 追加 / クラウドモデル追加 / HF 検索」を集約し、
    // 旧レイアウトにあった LocalModelAddCard / HfModelSearchCard / mmproj 追加ボタンは廃止した。
    private var addFabMenuExpanded by mutableStateOf(false)

    // --- ダウンロード前ライセンス確認ダイアログ ---
    // 画像モデル: ダウンロードボタン押下時に対象モデルを保持し、ライセンス取得中/取得結果を表示する。
    private var imageLicensePendingModel by mutableStateOf<com.nezumi_ai.data.inference.ImageModel?>(null)
    private var imageLicenseLoading by mutableStateOf(false)
    private var imageLicenseInfo by mutableStateOf<com.nezumi_ai.data.inference.ImageModelLicenseInfo?>(null)
    // VOICEVOX: スタイル選択時に確認を挟むための保留状態。
    private var voicevoxLicensePendingStyleId by mutableStateOf<Int?>(null)

    // SD (画像生成) モデル zip ピッカー。
    // zip 内に unet.mnn / clip*.mnn / vae_decoder*.mnn と、
    // tokenizer.json または pos_emb.bin+token_emb.bin が含まれていればインポート可能。
    // LLM モデル (.task/.litertlm/.gguf) と同様にインポート中はモーダルを表示し、
    // 大きな zip を展開する間の無反応状態でユーザが二重タップしないようにする。
    private val sdZipPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            isImportingModel = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        com.nezumi_ai.sd.SdModelImporter.importFromUri(requireContext(), uri)
                    }
                    result.onSuccess { imported ->
                        toast("画像生成モデルを追加しました: ${imported.displayName}")
                        refreshSdModels()
                        PreferencesHelper.setSdModelPath(requireContext(), imported.dir.absolutePath)
                    }.onFailure {
                        toast("画像生成モデルの追加に失敗しました: ${it.message}")
                    }
                } finally {
                    isImportingModel = false
                }
            }
        }

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
    // .vvm の外部手動追加は仕様上削除されたため、ピッカー Launcher も削除している。
    private var settingsDialogDisplayName by mutableStateOf("")
    private var settingsDialogStopTokens by mutableStateOf("")

    // モデル設定ダイアログの自動保存制御: openModelSettingsDialog() 内で値をセットする間は
    // true にし、LaunchedEffect の snapshotFlow が初回ロード値を保存に回すのを防ぐ。
    // Compose で入力値が変わったときのみデバウンス保存することで
    // 「保存ボタンなしでその場で保存される」を実現する。
    @Volatile private var modelSettingsAutoSaveSuspended: Boolean = true
    private var modelSettingsAutoSaveJob: kotlinx.coroutines.Job? = null

    private var expandedModelKey by mutableStateOf<String?>(null)

    private var sdModels by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())
    private var sdModelProbePath by mutableStateOf<String?>(null)

    
    private var selectedTab by mutableStateOf(ModelType.LLM)

    private val modelStates = mutableStateMapOf<ModelFileManager.LocalModel, ModelUiState>()
    /** おすすめ GGUF（RecommendedModelCatalog）の DL 状態。Gemma の modelStates と同じ役割。 */
    private val recommendedGgufStates = mutableStateMapOf<String, ModelUiState>()
    private val ggufCardMetadataStates = mutableStateMapOf<String, GgufCardMetadataUiState>()

 // 埋め込みモデルダウンロード進捗（DLタブで表示）
    private var embeddingDownloadState by mutableStateOf<EmbeddingDownloadUiState?>(null)

 // ダウンロード中のネットワーク速度表示用（DLタブで表示）
    private var activeDownloadSpeeds by mutableStateOf<Map<String, DownloadSpeedInfo>>(emptyMap())

 // リポジトリ更新通知: ダウンロード済みモデルのリポジトリが更新された場合に表示する
    private var repoUpdateNotifications by mutableStateOf<List<RepoUpdateNotification>>(emptyList())
    private var repoUpdateCheckInProgress by mutableStateOf(false)

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Custom Tabs 経由で result.data が null になるケースがあるため、
            //   戻ってきただけでも SharedPreferences のトークン状態を見直す。
            val data = result.data
            if (data == null) {
                renderHfTokenState()
                return@registerForActivityResult
            }
            val authResponse = AuthorizationResponse.fromIntent(data)
            val authError = AuthorizationException.fromIntent(data)
            if (authError != null) {
                toast("OAuth失敗: ${authError.errorDescription}")
                renderHfTokenState()
                return@registerForActivityResult
            }
            if (authResponse == null) {
                toast("OAuthレスポンスが取得できませんでした")
                renderHfTokenState()
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
        RecommendedModelCatalog.recommended()
            .filter { it.engine == RecommendedModelCatalog.Engine.GGUF }
            .forEach { entry ->
                recommendedGgufStates[entry.id] = ModelUiState(entry.displayName)
            }
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
        refreshRecommendedGgufStatus()
        refreshVoicevoxState()
        observeDownloadWork()
        observeCustomHfDownloadWork()
        observeRecommendedGgufWork()
        observeImageModelDownloadWork()
        observeSafetyModelDownloadWork()
        observeVoicevoxDownloadWork()
        observeVoicevoxReadyState()
        observeEmbeddingDownloadWork()
        observeDownloadSpeeds()
        viewLifecycleOwner.lifecycleScope.launch {
            preloadMemoryWarningThresholdPercent = settingsRepository.getPreloadMemoryWarningThresholdPercent()
        }
    }

    override fun onResume() {
        super.onResume()
        renderHfTokenState()
        refreshImportedTasks()
        refreshVoicevoxState()
 // モデルのアップデート通知は削除（ユーザー要望）
        // checkRepositoryUpdates()
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
        if (imageLicensePendingModel != null) {
            ImageModelLicenseConfirmDialog()
        }
        voicevoxLicensePendingStyleId?.let {
            VoicevoxLicenseConfirmDialog()
        }
        CloudModelDialogHost()
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
        val cloudModels = remember(cloudModelsRevision) { registeredCloudModels }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(colorResource(id = R.color.bg_session_list))
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
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
                        text = stringResource(id = R.string.model_settings),
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
                    item {
                        Text(
                            text = stringResource(id = R.string.model_settings_builtin_models),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                    }
                    // Gemma 3n 2B / 4B は組み込みモデルリストから除外する。
                    // enum は既存ダウンロード済みファイルの削除/ミグレーションのために残すが、
                    // ダウンロードカードは新規には提供しない。
                    items(
                        ModelFileManager.LocalModel.entries.filter { m ->
                            m != ModelFileManager.LocalModel.GEMMA3N_2B &&
                                m != ModelFileManager.LocalModel.GEMMA3N_4B
                        }
                    ) { model ->
                        val state = modelStates[model]
                        if (state != null) {
                            val modelKey = "builtin_${model.name}"
                            val isExpanded = expandedModelKey == modelKey
                            val sizeBytes = getModelSizeBytes(model)
                            val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent, modelIdentifier = ModelFileManager.modelFileName(model))
                            ModelAccordionItem(
                                title = state.title,
                                status = state.status,
                                isExpanded = isExpanded,
                                onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                                onDownload = { onBuiltinDownloadButtonClicked(model) },
                                onDelete = {
                                    val ok = ModelFileManager.deleteModel(requireContext(), model)
                                    toast(if (ok) getString(R.string.common_deleted) else getString(R.string.common_delete_failed))
                                    refreshModelStatus(model)
                                    expandedModelKey = null
                                },
                                isDownloading = state.isDownloading,
                                isDownloaded = state.isDownloaded,
                                progress = state.progress,
                                progressText = state.progressText,
                                isMemoryLow = state.memoryWarning != null,
                                isStorageLow = resourceCheck.isStorageLow,
                                fileSizeLabel = formatBytes(sizeBytes),
                                speedInfo = activeDownloadSpeeds[model.name],
                                isPaused = state.isPaused,
                                onPause = {
                                    ModelDownloadWorker.pause(requireContext(), model)
                                    toast("一時停止しました。再開時は続きからダウンロードします")
                                }
                            )
                        }
                    }
                    // おすすめ GGUF（Qwen3.5 / LFM2.5 など）。Gemma と同じ ModelAccordionItem + WorkManager 経路。
                    items(
                        RecommendedModelCatalog.recommended().filter {
                            it.engine == RecommendedModelCatalog.Engine.GGUF
                        }
                    ) { entry ->
                        val state = recommendedGgufStates[entry.id] ?: ModelUiState(entry.displayName)
                        val modelKey = "rec_${entry.id}"
                        val isExpanded = expandedModelKey == modelKey
                        val sizeBytes = entry.estimatedSizeBytes
                        val resourceCheck = ModelFileManager.checkDownloadResources(
                            requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent,
                            modelIdentifier = entry.id
                        )
                        val speedKey = "${entry.hfRepo}/${entry.hfFile?.substringAfterLast('/')}"
                        ModelAccordionItem(
                            title = state.title,
                            status = state.status,
                            isExpanded = isExpanded,
                            onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                            onDownload = { onRecommendedGgufDownloadClicked(entry) },
                            onDelete = {
                                val repo = entry.hfRepo ?: return@ModelAccordionItem
                                val file = entry.hfFile ?: return@ModelAccordionItem
                                val target = ModelFileManager.huggingFaceImportedFile(requireContext(), repo, file)
                                if (target.isFile) target.delete()
                                ModelFileManager.invalidateImportedListCache()
                                refreshRecommendedGgufStatus(entry)
                                refreshImportedTasks()
                                toast(getString(R.string.common_deleted))
                                expandedModelKey = null
                            },
                            isDownloading = state.isDownloading,
                            isDownloaded = state.isDownloaded,
                            progress = state.progress,
                            progressText = state.progressText,
                            isMemoryLow = resourceCheck.isMemoryLow,
                            isStorageLow = resourceCheck.isStorageLow,
                            fileSizeLabel = formatBytes(sizeBytes),
                            speedInfo = activeDownloadSpeeds[speedKey],
                            isPaused = state.isPaused,
                            engineLabel = "llama.cpp",
                            onPause = {
                                val repo = entry.hfRepo ?: return@ModelAccordionItem
                                val file = entry.hfFile ?: return@ModelAccordionItem
                                ModelDownloadWorker.cancelCustomHf(requireContext(), repo, file)
                                toast(getString(R.string.model_download_paused_toast))
                            }
                        )
                    }
                    item { EmbeddingModelsCard() }
                    // 「追加済みモデル」見出しと整理 UI（検索 / 並び替え）
                    item {
                        Text(
                            text = stringResource(id = R.string.model_settings_custom_models),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 12.dp)
                        )
                    }
                    item { ImportedModelsFilterBar() }

                    // ダウンロード済みのおすすめ LiteRT（Gemma 等）も追加済みリストに出す
                    val downloadedBuiltins = ModelFileManager.LocalModel.entries.filter { m ->
                        m != ModelFileManager.LocalModel.GEMMA3N_2B &&
                            m != ModelFileManager.LocalModel.GEMMA3N_4B &&
                            (modelStates[m]?.isDownloaded == true ||
                                ModelFileManager.isDownloaded(requireContext(), m))
                    }
                    items(downloadedBuiltins) { model ->
                        val state = modelStates[model] ?: return@items
                        val modelKey = "added_builtin_${model.name}"
                        val isExpanded = expandedModelKey == modelKey
                        val sizeBytes = getModelSizeBytes(model)
                        ModelAccordionItem(
                            title = state.title,
                            status = state.status,
                            isExpanded = isExpanded,
                            onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                            onDownload = { onBuiltinDownloadButtonClicked(model) },
                            onDelete = {
                                val ok = ModelFileManager.deleteModel(requireContext(), model)
                                toast(if (ok) getString(R.string.common_deleted) else getString(R.string.common_delete_failed))
                                refreshModelStatus(model)
                                expandedModelKey = null
                            },
                            isDownloading = state.isDownloading,
                            isDownloaded = state.isDownloaded,
                            progress = state.progress,
                            progressText = state.progressText,
                            fileSizeLabel = formatBytes(sizeBytes),
                            speedInfo = activeDownloadSpeeds[model.name],
                            isPaused = state.isPaused,
                            engineLabel = "LiteRT-LM",
                            onPause = {
                                ModelDownloadWorker.pause(requireContext(), model)
                                toast(getString(R.string.model_download_paused_toast))
                            }
                        )
                    }
                    
                    // 追加済みローカルモデル + クラウドモデルを同じリストに並べる
                    if (importedTasks.isEmpty() && downloadedBuiltins.isEmpty() && cloudModels.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.model_settings_custom_models_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    } else if (displayedImportedTasks.isEmpty() && importedTasks.isNotEmpty() && cloudModels.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(id = R.string.model_settings_custom_models_empty_search),
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
                                        toast(getString(R.string.common_deleted))
                                        refreshImportedTasks()
                                        expandedModelKey = null
                                    }.onFailure {
                                        toast(getString(R.string.common_delete_failed) + ": ${it.message}")
                                    }
                                }
                            )
                        }
                    }
                    // クラウドモデルを追加済みモデルと同じ場所に並べる
                    items(cloudModels) { modelId ->
                        CloudModelListItem(modelId = modelId)
                    }
                    item { MmprojFilesCard() }
                    // 追加ボタン群は右下の FAB メニュ (ModelAddFabMenu) に集約したためカードは廃止。
                    // 画面末尾に FAB との重なりを避ける余白だけ確保する。
                    item { Spacer(modifier = Modifier.height(88.dp)) }
                }
                ModelType.IMAGE_GENERATION -> {
                    // zip 一括インポートは画像生成モデル (Stable Diffusion / MNN)
                    // 用途に限定した機能なので、このタブ内でのみ表示する。
                    item { SdZipImportCard() }
                    item { SdImageGenFromHfCard() }
                    item { DownloadedImageModelsCard() }
                }
                ModelType.TEXT_TO_SPEECH -> {
                    item { VoicevoxCard() }
                }
                ModelType.DOWNLOAD_QUEUE -> {
                    item { DownloadQueueCard() }
                    item { EmbeddingDownloadCard() }
                    // 各カードに通信速度を集約したため、独立した NetworkSpeedCard は削除
 // モデルのアップデート通知は削除（ユーザー要望）
                    // item { RepoUpdateNotificationCard() }
                }
            }
        }
        // 右下に固定された「＋」ボタン。LazyColumn をスクロールしても常に最上層に浮かぶ。
        // LLM タブのときのみ表示（他タブは専用カードがあるため）。
        if (selectedTab == ModelType.LLM) {
            ModelAddFabMenu(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp)
            )
        }
        }
    }

    /**
     * 右下のフローティングアクションボタンと、そこから展開される追加メニュ。
     * 旧カード (LocalModelAddCard / HfModelSearchCard / MmprojFilesCard の追加ボタン) の
     * 入口をここに全て集約した。
     */
    @Composable
    private fun ModelAddFabMenu(modifier: Modifier = Modifier) {
        Box(modifier = modifier) {
            FloatingActionButton(
                onClick = { addFabMenuExpanded = true },
                containerColor = colorResource(id = R.color.primary),
                contentColor = Color.White
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(id = R.string.model_settings_add_models_title)
                )
            }
            DropdownMenu(
                expanded = addFabMenuExpanded,
                onDismissRequest = { addFabMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.model_settings_local_import_button)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.FileUpload,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        addFabMenuExpanded = false
                        importTaskLauncher.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text("mmproj を追加") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Attachment,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        addFabMenuExpanded = false
                        mmprojPickerLauncher.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.cloud_models_entry_button)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.CloudQueue,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        addFabMenuExpanded = false
                        cloudDialogState = CloudDialogState()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Hugging Face で検索") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        addFabMenuExpanded = false
                        // 検索結果ページを直接開く。検索入力欄はそのページの上部にある。
                        hfSearchResultsDialogVisible = true
                    }
                )
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
    private fun SdZipImportCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "SD モデル zip をインポート",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            sdZipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }.onFailure { toast(getString(R.string.model_settings_sd_zip_select_failed, it.message ?: "")) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.model_settings_sd_zip_select))
                }
                Text(
                    text = stringResource(id = R.string.model_settings_sd_zip_formats),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(top = 6.dp)
                )
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
                            text = "設定",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
                            onCheckedChange = { capabilityDialogImageEnabled = it }
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
                            onCheckedChange = { capabilityDialogAudioEnabled = it }
                        )
                    }
                    // Thinking トグルは GGUF/LiteRT-LM 両方（外部インポート .task / .litertlm 含む）で表示する。
                    // LiteRT-LM 側でも SamplerConfig の enable_thinking を通してモデルに伝達されるため、
                    // 対応モデル (Gemma3n / Qwen3 系 など) を外部から取り込んだ場合にも Thinking を有効化できる。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(id = R.string.model_settings_enable_thinking), color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = capabilityDialogThinkingEnabled,
                            onCheckedChange = { capabilityDialogThinkingEnabled = it }
                        )
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
                                onCheckedChange = { capabilityDialogToolCallingEnabled = it }
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
                        val autoDetectLabel = stringResource(id = R.string.model_settings_template_auto_detect)
                        val customLabel = stringResource(id = R.string.model_settings_template_custom)
                        val templateOptions = remember(autoDetectLabel, customLabel) {
                            buildList {
                                add(PromptTemplateStore.MODE_AUTO to autoDetectLabel)
                                PromptTemplateStore.BUILTIN_TEMPLATES.forEach { b ->
                                    add(b.id to b.displayName)
                                }
                                add(PromptTemplateStore.MODE_CUSTOM to customLabel)
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
                                label = { Text(stringResource(id = R.string.model_settings_custom_template_label)) },
                                placeholder = { Text("{{ if .System }}...{{ end }}{{ range .History }}...{{ end }}") },
                                minLines = 5,
                                isError = capabilityDialogTemplateError != null
                            )
                            Text(
                                text = stringResource(id = R.string.model_settings_template_variables),
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

 // TabSelector は ModelSidebarSelector に置き換えられたため削除。
    //   古い横並びタブはスクロール可能なことに気づきにくかったため、縦型サイドバーに変更した。

 // 埋め込みモデルダウンロード進捗カード（DLタブに表示）
    @Composable
    private fun EmbeddingDownloadCard() {
        val state = embeddingDownloadState ?: return
        // 埋め込みモデルの速度を activeDownloadSpeeds から拾う（キーはファイル名）
        val speedInfo = activeDownloadSpeeds[state.fileName]
            ?: activeDownloadSpeeds.entries.firstOrNull { it.key.contains("embedding", ignoreCase = true) }?.value
        Text(
            text = "埋め込みモデル（メモリ検索用）ダウンロード",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 16.dp)
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
                Text(text = "static-embedding-japanese: ${state.fileName}", fontWeight = FontWeight.SemiBold)
                if (state.totalBytes > 0L) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = { state.progress },
                        color = colorResource(id = R.color.primary),
                        trackColor = colorResource(id = R.color.context_meter_track)
                    )
                    val percent = (state.progress * 100).toInt()
                    Text(
                        text = "$percent% (${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)})",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colorResource(id = R.color.primary),
                        trackColor = colorResource(id = R.color.context_meter_track)
                    )
                    Text(
                        text = "準備中...",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // 各カード内に通信速度と残り時間を表示
                speedInfo?.let { info ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format("%.1f MB/s", info.speedMbps),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.primary),
                            fontWeight = FontWeight.Bold
                        )
                        if (info.estimatedRemainingSec > 0) {
                            val remainMin = (info.estimatedRemainingSec / 60).toInt()
                            val remainSec = (info.estimatedRemainingSec % 60).toInt()
                            Text(
                                text = "残り ${remainMin}分${remainSec}秒",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary)
                            )
                        }
                    }
                }
            }
        }
    }

 // リポジトリ更新通知カード（DLタブに表示）
    @Composable
    private fun RepoUpdateNotificationCard() {
        if (repoUpdateNotifications.isEmpty()) return
        Text(
            text = "リポジトリ更新通知",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 16.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repoUpdateNotifications.forEach { notif ->
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
                            Text(text = notif.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "更新あり",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.primary),
                                fontWeight = FontWeight.Bold
                            )
                        }
 // SHA256 ハッシュ比較による更新検知のため、
                        //   ローカルファイルサイズを表示する（リモートサイズは取得していない）
                        Text(
                            text = "ローカルファイル: ${formatBytes(notif.localFileSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                        Text(
                            text = "リモートリポジトリのハッシュが変更されています。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
 // 更新ボタン: モデルを削除して再ダウンロード
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    val model = ModelFileManager.LocalModel.entries.firstOrNull {
                                        it.name == notif.modelKey
                                    }
                                    if (model != null) {
                                        ModelFileManager.deleteModel(requireContext(), model)
                                        withContext(Dispatchers.Main) {
                                            refreshModelStatus(model)
                                            requestNotificationPermissionForDownload(model)
                                            toast("${notif.displayName} を更新しています...")
                                        }
                                    }
                                }
                            }) {
                                Text("更新")
                            }
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    private fun VoicevoxCard() {
        // VOICEVOX 無効時はセクション全体を非表示にする
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return

        val state = voicevoxState
        val progress = voicevoxDownloadState
        val busy = voicevoxInitializing || progress?.isActive == true

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "VOICEVOX 音声読み上げ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.text_primary)
                )
                Text(
                    text = state.summaryLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )

                // ── 話者選択（全モデルの話者を平坦化して1つのメニューから選ぶ） ─────────────
                // 話者を選んだ瞬間に MyApplication.selectVoicevoxStyle() が呼ばれ、
                //   ・目的の .vvm が未ダウンロードなら自動でダウンロードし、完了後に自動初期化
                //   ・ダウンロード済みならその場で自動初期化のみ実行
                // という 1 アクション制御に統合されている。
                val allStyles = VoicevoxManager.allStyles
                if (allStyles.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = voicevoxStyleMenuExpanded,
                        onExpandedChange = {
                            if (!busy) voicevoxStyleMenuExpanded = !voicevoxStyleMenuExpanded
                        }
                    ) {
                        OutlinedTextField(
                            value = state.selectedStyleLabel,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !busy,
                            label = { Text("読み上げに使う声") },
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
                            allStyles.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style.detailName) },
                                    onClick = {
                                        voicevoxStyleMenuExpanded = false
                                        val app = requireContext().applicationContext as MyApplication
                                        if (app.willDownloadForVoicevoxStyle(style.styleId)) {
                                            // 新規ダウンロードが発生する場合のみ、ライセンス確認を挟む。
                                            voicevoxLicensePendingStyleId = style.styleId
                                        } else {
                                            // ダウンロード不要（既に取得済みの声への切替）はそのまま実行。
                                            app.selectVoicevoxStyle(style.styleId)
                                            refreshVoicevoxState()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // ── 進行ステータス行（自動DL / 初期化中 / 待機） ─────────────
                // このブロックは常に同じ位置（カード内の同じスロット）に描画することで、
                // ボタンを押した瞬間に下のボタンの入れ替わり順がパチパチ変わる以前のバグを防ぐ。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when {
                        progress != null && progress.isActive -> {
                            Text(
                                text = progress.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_primary)
                            )
                            if (progress.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = progress.ratio,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                text = progress.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary)
                            )
                        }
                        voicevoxInitializing -> {
                            Text(
                                text = "ステップ 2/2: 音声エンジンを初期化しています...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_primary)
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "初回ロードは数十秒かかることがあります",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary)
                            )
                        }
                    }
                }

                // ── ボタン行（順番は常に固定：中止 → 削除） ─────────────
                // 以前は「進捗中のときだけ中止ボタンを描く」構造だったため、
                // 削除ボタンが上に繰り上がり、その位置にあった中止ボタンを押した瞬間に
                // タップが削除ボタンに乗り移る「ボタン順が変わる」バグが発生していた。
                // 常に両方描画し、enabled で制御することで座標を固定する。
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = progress != null && progress.isActive,
                    onClick = {
                        ModelDownloadWorker.cancelVoicevoxModel(requireContext())
                        voicevoxDownloadState = null
                        toast("ダウンロードを中止しました")
                    }
                ) {
                    Text("ダウンロードを中止")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.modelExists && !busy,
                    onClick = {
                        val app = requireContext().applicationContext as MyApplication
                        val deleted = app.getVoicevoxManager().deleteInstalledModel()
                        toast(if (deleted) "音声モデルを削除しました" else "削除に失敗しました")
                        voicevoxStyleMenuExpanded = false
                        refreshVoicevoxState()
                    }
                ) {
                    Text("選択中の音声モデルを削除")
                }

                // ── クレジット表記 ─────────────────────────────
                Divider(color = colorResource(id = R.color.border))
                Text(
                    text = "クレジット表記",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colorResource(id = R.color.text_secondary)
                )
                Text(
                    text = state.creditLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_primary)
                )
                Text(
                    text = "生成した音声を公開・配布する場合は上記クレジットの表記が必要です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
                state.licenseNote?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_primary)
                    )
                }
                TextButton(
                    onClick = {
                        runCatching {
                            startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(state.licenseUrl)
                                )
                            )
                        }.onFailure { toast("利用規約を開けませんでした") }
                    }
                ) {
                    Text("利用規約を開く")
                }
            }
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
        // Gemma (LocalModel) のみここで表示。おすすめ llama.cpp は hfQueuedDownloads 側に出るため二重表示しない。
        val builtinDownloading = ModelFileManager.LocalModel.entries.mapNotNull { m ->
            val s = modelStates[m] ?: return@mapNotNull null
            if (s.isDownloading) m to s else null
        }
        if (builtinDownloading.isNotEmpty()) {
            Text(
                text = stringResource(id = R.string.model_download_queue_builtin_header),
                style = MaterialTheme.typography.labelSmall,
                color = colorResource(id = R.color.text_secondary),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                builtinDownloading.forEach { (model, state) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.surface_card))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = state.title, fontWeight = FontWeight.SemiBold)
                            if (state.progress > 0f) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    progress = { state.progress },
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
                                text = state.progressText.ifBlank { state.status },
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    ModelDownloadWorker.pause(requireContext(), model)
                                    toast(getString(R.string.model_download_paused_toast))
                                }) { Text(stringResource(id = R.string.model_download_pause)) }
                            }
                        }
                    }
                }
            }
        }
        if (hfQueuedDownloads.isNotEmpty()) {
            Text(
                text = stringResource(id = R.string.model_download_queue_hf_header),
                style = MaterialTheme.typography.labelSmall,
                color = colorResource(id = R.color.text_secondary),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(
                    start = 4.dp,
                    bottom = 8.dp,
                    top = if (builtinDownloading.isNotEmpty()) 16.dp else 0.dp
                )
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hfQueuedDownloads.forEach { item ->
                    // HF カスタム DL の速度キーは observeDownloadSpeeds() で
                    //   "{modelId}/{fileName}" 形式で登録される。
                    val speedKey = "${item.modelId}/${item.filePath.substringAfterLast('/')}"
                    val speedInfo = activeDownloadSpeeds[speedKey]
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
                            // 各カードに通信速度と残り時間を表示
                            speedInfo?.let { info ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = String.format("%.1f MB/s", info.speedMbps),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(id = R.color.primary),
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (info.estimatedRemainingSec > 0) {
                                        val remainMin = (info.estimatedRemainingSec / 60).toInt()
                                        val remainSec = (info.estimatedRemainingSec % 60).toInt()
                                        Text(
                                            text = "残り ${remainMin}分${remainSec}秒",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorResource(id = R.color.text_secondary)
                                        )
                                    }
                                }
                            }
                            if (item.isActive) {
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        ModelDownloadWorker.pauseCustomHf(
                                            requireContext(),
                                            item.modelId,
                                            item.filePath
                                        )
                                        toast("一時停止しました。再開時は続きからダウンロードします")
                                    }) { Text("一時停止") }
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
                    ModelDownloadProgressCard(
                        item,
                        onPause = {
                            ModelDownloadWorker.pauseImageModel(requireContext(), item.modelId)
                            toast("一時停止しました。再開時は続きからダウンロードします")
                        },
                        onCancel = {
                            ModelDownloadWorker.cancelImageModel(requireContext(), item.modelId)
                        }
                    )
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
        
        val anyRecommendedDownloading = recommendedGgufStates.values.any { it.isDownloading }
        val anyBuiltinDownloading = modelStates.values.any { it.isDownloading }
        if (hfQueuedDownloads.isEmpty() && imageModelDownloadStates.isEmpty() &&
            safetyModelDownloadState == null && !anyRecommendedDownloading && !anyBuiltinDownloading
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Text(
                    text = stringResource(id = R.string.model_download_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    // ─── クラウドモデル管理 (追加/編集/削除をモーダルで行う) ────────────────

    /** 追加/編集モーダルの状態。null のときは非表示。 */
    private var cloudDialogState by mutableStateOf<CloudDialogState?>(null)

    private data class CloudDialogState(
        /** 編集対象の modelId。null のときは新規追加。 */
        val editingModelId: String? = null,
        val provider: CloudApiKeyStore.Provider = CloudApiKeyStore.Provider.LM_STUDIO,
        val modelName: String = "",
        val apiKey: String = "",
        val baseUrl: String = "",
        /** ローカル系: サーバーから取得したモデル一覧。 */
        val fetchedModels: List<String> = emptyList(),
        val fetchingModels: Boolean = false,
        val providerDropdownExpanded: Boolean = false,
        val modelDropdownExpanded: Boolean = false,
        val errorMessage: String? = null
    )

    /** 登録済みクラウドモデルの一覧。再読み込みは revision をインクリメントして行う。 */
    private var cloudModelsRevision by mutableStateOf(0)
    private val registeredCloudModels: List<String>
        get() = CloudUserModelRegistry.listForContext(requireContext())

    /**
     * 追加済みモデル一覧に並べるクラウドモデル 1 件分の行。
     * タップで編集モーダル、削除ボタンで登録解除。
     */
    @Composable
    private fun CloudModelListItem(modelId: String) {
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
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = CloudModelId.displayLabel(modelId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Text(
                        text = stringResource(
                            id = if (configured) R.string.cloud_models_status_configured
                            else R.string.cloud_models_status_missing
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(
                            id = if (configured) R.color.primary else R.color.text_secondary
                        )
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

    /** クラウドモデル追加/編集ダイアログを常にコンポジションに載せる（ModelScreen 先頭から呼ぶ）。 */
    @Composable
    private fun CloudModelDialogHost() {
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
    private fun CloudModelDialog(
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

    private fun providerLabel(provider: CloudApiKeyStore.Provider): String = when (provider) {
        CloudApiKeyStore.Provider.CLAUDE -> getString(R.string.cloud_models_provider_claude)
        CloudApiKeyStore.Provider.GEMINI -> getString(R.string.cloud_models_provider_gemini)
        CloudApiKeyStore.Provider.OPENAI -> getString(R.string.cloud_models_provider_openai)
        CloudApiKeyStore.Provider.OLLAMA_LOCAL -> getString(R.string.cloud_models_provider_ollama_local)
        CloudApiKeyStore.Provider.OLLAMA_REMOTE -> getString(R.string.cloud_models_provider_ollama_remote)
        CloudApiKeyStore.Provider.LM_STUDIO -> getString(R.string.cloud_models_provider_lmstudio)
    }

    private fun modelNameHint(provider: CloudApiKeyStore.Provider): String = when (provider) {
        CloudApiKeyStore.Provider.CLAUDE -> getString(R.string.cloud_models_model_name_hint_claude)
        CloudApiKeyStore.Provider.GEMINI -> getString(R.string.cloud_models_model_name_hint_gemini)
        CloudApiKeyStore.Provider.OPENAI -> getString(R.string.cloud_models_model_name_hint_openai)
        CloudApiKeyStore.Provider.OLLAMA_LOCAL,
        CloudApiKeyStore.Provider.OLLAMA_REMOTE -> getString(R.string.cloud_models_model_name_hint_ollama)
        CloudApiKeyStore.Provider.LM_STUDIO -> getString(R.string.cloud_models_model_name_hint_lmstudio)
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
                                hfHasSearched = false
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
                    text = "sd-mnn (MNN)",

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
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!hfLinked || it.contains("認証")) {
                        TextButton(onClick = { startOAuthLogin() }) {
                            Text("HuggingFaceに再ログイン")
                        }
                    }
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
                if (model == ModelFileManager.LocalModel.GEMMA3N_2B ||
                    model == ModelFileManager.LocalModel.GEMMA3N_4B
                ) continue // Gemma 3n は UI 一覧から除外
                val state = modelStates[model] ?: continue
                val modelKey = "builtin_${model.name}"
                val isExpanded = expandedModelKey == modelKey
                
                // ストレージ判定
                val sizeBytes = getModelSizeBytes(model)
                val resourceCheck = ModelFileManager.checkDownloadResources(requireContext(), sizeBytes, preloadMemoryWarningThresholdPercent, modelIdentifier = ModelFileManager.modelFileName(model))
                
                ModelAccordionItem(
                    title = state.title,
                    status = state.status,
                    isExpanded = isExpanded,
                    onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                    onDownload = { onBuiltinDownloadButtonClicked(model) },
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
                    fileSizeLabel = formatBytes(sizeBytes),
                    speedInfo = activeDownloadSpeeds[model.name],
                    isPaused = state.isPaused,
                    onPause = {
                        ModelDownloadWorker.pause(requireContext(), model)
                        toast("一時停止しました。再開時は続きからダウンロードします")
                    }
                )
            }
        }
    }
    
    @Composable
    private fun CustomModelsCard() {
        if (importedTasks.isEmpty()) return
        
        Text(
            text = stringResource(id = R.string.model_settings_custom_models),
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
 text = if (isReady) "利用可能"else "未ダウンロード",
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
        // mmproj の追加ボタンは右下 FAB メニュ (ModelAddFabMenu) に集約したため、
        // ここでは既に追加されている mmproj ファイルの一覧のみ表示する。
        // 一覧が 0 件のときは見出しもカードも出さず、モデル一覧を余計に伸ばさないようにする。
        if (importedMmprojTasks.isEmpty()) return

        Text(
            text = "mmproj ファイル",
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(id = R.color.text_secondary),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp)
        )

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
    }
    
    @Composable
    private fun LocalModelAddCard() {
        Text(
            text = stringResource(id = R.string.model_settings_add_models_title),
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
                    text = stringResource(id = R.string.model_settings_local_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { importTaskLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text(stringResource(id = R.string.model_settings_local_import_button))
                }
                // GGUF インポートと同じカード内にクラウドモデル追加ボタンを配置
                Text(
                    text = stringResource(id = R.string.cloud_models_screen_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(id = R.color.text_secondary)
                )
                Button(
                    onClick = { cloudDialogState = CloudDialogState() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    Text(stringResource(id = R.string.cloud_models_entry_button), fontSize = 12.sp)
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
 text = "メモリ不足",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                model.variant?.let { variant ->
                    Text(
                        text = variant,
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )

                }
                Button(
                    onClick = { requestImageModelDownload(model) },
                    enabled = !isDownloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isDownloading) "ダウンロード中..." else "ダウンロード")
                }
            }
        }
    }

    /** ダウンロードボタン押下時のエントリポイント。即ダウンロードせず、まずライセンス確認ダイアログを開く。 */
    private fun requestImageModelDownload(model: com.nezumi_ai.data.inference.ImageModel) {
        imageLicensePendingModel = model
        imageLicenseInfo = null
        imageLicenseLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                com.nezumi_ai.data.inference.ImageModelBrowser.fetchLicenseInfo(requireContext(), model.repo)
            }
            // ダイアログを閉じた後に結果が返ってきた場合は無視する（別モデルに切り替わっている可能性があるため）
            if (imageLicensePendingModel?.id == model.id) {
                imageLicenseInfo = info
                imageLicenseLoading = false
            }
        }
    }

    /** ライセンス確認ダイアログで「同意してダウンロード」が押された時。 */
    private fun confirmImageModelDownload() {
        val model = imageLicensePendingModel ?: return
        imageLicensePendingModel = null
        imageLicenseInfo = null
        imageLicenseLoading = false
        downloadImageModel(model)
    }

    /** ライセンス確認ダイアログで「キャンセル」が押された時、または閉じた時。 */
    private fun dismissImageLicenseDialog() {
        imageLicensePendingModel = null
        imageLicenseInfo = null
        imageLicenseLoading = false
    }

    @Composable
    private fun ImageModelLicenseConfirmDialog() {
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
    private fun VoicevoxLicenseConfirmDialog() {
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

    @Composable
    private fun HfSearchResultsContent() {
        // 旧 HfModelSearchCard を廃止したので、検索入力欄をこのページの上部に移した。
        // さらにリストを一定量スクロールしたときだけ右下に「上にジャンプ」ボタンを出す。
        val listState = rememberLazyListState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val showJumpTop by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset > 200
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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

                // 検索入力欄（旧 HfModelSearchCard から移行）。
                //   - 旧実装と同じく hfSearchQuery / searchHfModels() にバインドし、
                //     検索実行後は現ページに結果リストが差し替わる。
                //   - クリア・結果を見るボタンはこのページ自体が結果ビューなので不要。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = colorResource(id = R.color.primary_light)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                        hfSearchError?.let {
                            Text(
                                text = it,
                                color = colorResource(id = R.color.text_primary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (hfSearchResults.isEmpty()) {
                    Text(
                        text = if (hfSearchLoading) "検索中..." else if (!hfHasSearched) "キーワードを入力して検索してください" else "検索結果がありません",
                        color = colorResource(id = R.color.text_secondary)
                    )
                } else {
                    Text(
                        text = "${hfSearchResults.size}件の結果",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
     // 次ページの自動読み込み:
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
         // 次ページプレースホルダー: スピナーのみ。loadMore のトリガーは
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

            // スクロールして先頭から離れたときだけ右下に「上にジャンプ」 FAB を表示する。
            // ModelScreen の「＋」FAB と同じ位置だが、他タブと並びではない検索ビュー上のボタンなので衝突はない。
            if (showJumpTop && hfSearchResults.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    containerColor = colorResource(id = R.color.primary),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "上までジャンプ"
                    )
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
 text = "mmproj が見つかりました。DL時に「${autoMmproj.path}」も自動ダウンロードし、画像認識が有効になります。",
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
 isMemoryLow && resourceCheck.isStorageLow -> "メモリ・ストレージ不足"
 isMemoryLow -> "メモリ不足"
 else -> "ストレージ不足"
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
                            val speedKey = "${item.modelId}/${item.filePath.substringAfterLast('/')}"
                            val speedInfo = activeDownloadSpeeds[speedKey]
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
                                    // 各カードに通信速度と残り時間を表示
                                    speedInfo?.let { info ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = String.format("%.1f MB/s", info.speedMbps),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colorResource(id = R.color.primary),
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (info.estimatedRemainingSec > 0) {
                                                val remainMin = (info.estimatedRemainingSec / 60).toInt()
                                                val remainSec = (info.estimatedRemainingSec % 60).toInt()
                                                Text(
                                                    text = "残り ${remainMin}分${remainSec}秒",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colorResource(id = R.color.text_secondary)
                                                )
                                            }
                                        }
                                    }
                                    if (item.isActive) {
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            TextButton(onClick = {
                                                ModelDownloadWorker.pauseCustomHf(
                                                    requireContext(),
                                                    item.modelId,
                                                    item.filePath
                                                )
                                                toast("一時停止しました。再開時は続きからダウンロードします")
                                            }) { Text("一時停止") }
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
                            ModelDownloadProgressCard(
                                item,
                                onPause = {
                                    ModelDownloadWorker.pauseImageModel(requireContext(), item.modelId)
                                    toast("一時停止しました。再開時は続きからダウンロードします")
                                },
                                onCancel = {
                                    ModelDownloadWorker.cancelImageModel(requireContext(), item.modelId)
                                }
                            )
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
                        if (model == ModelFileManager.LocalModel.GEMMA3N_2B ||
                            model == ModelFileManager.LocalModel.GEMMA3N_4B
                        ) continue // Gemma 3n は UI 一覧から除外
                        val state = modelStates[model] ?: continue
                        val modelKey = "builtin_${model.name}"
                        val isExpanded = expandedModelKey == modelKey
                        val sizeBytes = getModelSizeBytes(model)
                        ModelAccordionItem(
                            title = state.title,
                            status = state.status,
                            isExpanded = isExpanded,
                            onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                            onDownload = { onBuiltinDownloadButtonClicked(model) },
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
                            fileSizeLabel = formatBytes(sizeBytes),
                            speedInfo = activeDownloadSpeeds[model.name],
                            isPaused = state.isPaused,
                            onPause = {
                                ModelDownloadWorker.pause(requireContext(), model)
                                toast("一時停止しました。再開時は続きからダウンロードします")
                            }
                        )
                    }
                }

                // SDモデル
                if (sdModels.isNotEmpty()) {
                    Text(
                        text = "画像生成モデル (MNN)",
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
                                onProbe = if (com.nezumi_ai.BuildConfig.DEBUG) {
                                    { probeMnnSdIo(model.path) }
                                } else {
                                    null
                                },
                                probeRunning = sdModelProbePath == model.path
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(id = R.string.model_settings_image_generation_section),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.model_settings_image_generation_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }

                // インポートされたモデル
                if (importedTasks.isNotEmpty()) {
                    Text(
                        text = stringResource(id = R.string.model_settings_imported_models_section),
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
                    text = stringResource(id = R.string.model_settings_mmproj_file_management),
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

    /** おすすめ GGUF を Gemma と同じ enqueueCustomHf + DLタブ経路で開始する。 */
    private fun onRecommendedGgufDownloadClicked(entry: RecommendedModelCatalog.Entry) {
        val repo = entry.hfRepo ?: return
        val file = entry.hfFile ?: return
        val state = recommendedGgufStates[entry.id] ?: return
        if (state.isDownloading) {
            ModelDownloadWorker.cancelCustomHf(requireContext(), repo, file)
            toast(getString(R.string.model_download_paused_toast))
            return
        }
        val enqueued = ModelDownloadWorker.enqueueCustomHf(requireContext(), repo, file)
        if (enqueued) {
            if (hfQueuedDownloads.none { it.modelId == repo && it.filePath == file }) {
                hfQueuedDownloads = hfQueuedDownloads + HfQueuedDownloadUiState(
                    modelId = repo,
                    filePath = file,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = getString(R.string.model_download_queued),
                    isActive = true
                )
            }
            state.isDownloading = true
            state.isDownloaded = false
            state.status = getString(R.string.setup_downloading_status)
            state.progress = 0f
            toast(getString(R.string.model_download_queued_named, entry.displayName))
        } else {
            toast(getString(R.string.model_download_already_running))
        }
    }

    private fun refreshRecommendedGgufStatus(entry: RecommendedModelCatalog.Entry? = null) {
        val targets = entry?.let { listOf(it) }
            ?: RecommendedModelCatalog.recommended().filter { it.engine == RecommendedModelCatalog.Engine.GGUF }
        targets.forEach { e ->
            val repo = e.hfRepo ?: return@forEach
            val file = e.hfFile ?: return@forEach
            val state = recommendedGgufStates[e.id] ?: return@forEach
            val local = ModelFileManager.huggingFaceImportedFile(requireContext(), repo, file)
            val downloaded = local.isFile && local.canRead() && local.length() > 0L
            state.isDownloaded = downloaded
            if (!state.isDownloading) {
                state.status = if (downloaded) {
                    getString(R.string.setup_ready_status)
                } else {
                    getString(R.string.setup_not_acquired_status)
                }
                state.progress = 0f
                state.progressText = ""
            }
            val partial = java.io.File("${local.absolutePath}.download")
            state.isPaused = !downloaded && !state.isDownloading && partial.exists() && partial.length() > 0L
        }
    }

    /** おすすめ GGUF の WorkManager 進捗をカード状態に反映（DLタブは既存 observeCustomHf が担当）。 */
    private fun observeRecommendedGgufWork() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(ModelDownloadWorker.TAG_HF_CUSTOM_DOWNLOAD)
            .observe(viewLifecycleOwner) { infos ->
                RecommendedModelCatalog.recommended()
                    .filter { it.engine == RecommendedModelCatalog.Engine.GGUF }
                    .forEach { entry ->
                        val repo = entry.hfRepo ?: return@forEach
                        val file = entry.hfFile ?: return@forEach
                        val state = recommendedGgufStates[entry.id] ?: return@forEach
                        val workName = ModelDownloadWorker.customWorkName(repo, file)
                        val info = infos.firstOrNull { wi ->
                            // unique work name は直接取れないので progress/output の modelId+path で突合
                            val mid = wi.progress.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                                ?: wi.outputData.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                            val fp = wi.progress.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                                ?: wi.outputData.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                            mid == repo && fp == file
                        }
                        if (info == null) {
                            // アクティブな work が無ければファイル有無で再判定
                            if (state.isDownloading) {
                                val local = ModelFileManager.huggingFaceImportedFile(requireContext(), repo, file)
                                if (local.isFile && local.length() > 0L) {
                                    state.isDownloading = false
                                    state.isDownloaded = true
                                    state.status = getString(R.string.setup_ready_status)
                                    state.progress = 1f
                                }
                            }
                            return@forEach
                        }
                        when (info.state) {
                            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                                state.isDownloading = true
                                state.isDownloaded = false
                                val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                                val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                                if (total > 0L) {
                                    state.progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                    state.progressText = "${(state.progress * 100).toInt()}% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                                    state.status = getString(R.string.setup_downloading_status)
                                } else {
                                    state.status = getString(R.string.setup_downloading_status)
                                }
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                state.isDownloading = false
                                state.isDownloaded = true
                                state.progress = 1f
                                state.progressText = ""
                                state.status = getString(R.string.setup_ready_status)
                                refreshImportedTasks()
                            }
                            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                                state.isDownloading = false
                                refreshRecommendedGgufStatus(entry)
                            }
                            else -> Unit
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
        fileSizeLabel: String? = null,
        speedInfo: DownloadSpeedInfo? = null,
        isPaused: Boolean = false,
        engineLabel: String = "LiteRT-LM",
        onPause: (() -> Unit)? = null
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
                                        text = "${stringResource(id = R.string.setup_memory_low)} / ${stringResource(id = R.string.setup_storage_low)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else if (isMemoryLow) {
                                    Text(
                                        text = stringResource(id = R.string.setup_memory_low),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else if (isStorageLow) {
                                    Text(
                                        text = stringResource(id = R.string.setup_storage_low),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        if (!isExpanded) {
                            Text(
                                text = engineLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.primary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (!isExpanded && isDownloaded && !isDownloading) {
                        Text(
                            text = stringResource(id = R.string.setup_ready_status),
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
                    } else if (!isExpanded && !isDownloaded) {
                        Text(
                            text = stringResource(id = R.string.setup_not_acquired_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = engineLabel,
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
                        // 各カード内に通信速度と残り時間を表示
                        speedInfo?.let { info ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = String.format("%.1f MB/s", info.speedMbps),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorResource(id = R.color.primary),
                                    fontWeight = FontWeight.Bold
                                )
                                if (info.estimatedRemainingSec > 0) {
                                    val remainMin = (info.estimatedRemainingSec / 60).toInt()
                                    val remainSec = (info.estimatedRemainingSec % 60).toInt()
                                    Text(
                                        text = "残り ${remainMin}分${remainSec}秒",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorResource(id = R.color.text_secondary)
                                    )
                                }
                            }
                        }
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
                                Text(
                                    if (isStorageLow) "容量不足"
                                    else if (isDownloading) "一時停止"
                                    else if (isPaused) "再開 (続きから)"
                                    else "ダウンロード"
                                )
                            }
                        }
                        // Bug fix: 未ダウンロードのモデルにも削除ボタンが表示されていた。
                        //   削除はダウンロード済み (かつダウンロード中でない) ときだけ出す。
                        if (isDownloaded && !isDownloading) {
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
 text = " "+ SettingsHelper.inferenceEngineForModel(model.path),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorResource(id = R.color.primary),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (!isExpanded) {
                        Text(
 text = "インポート済み",
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
 text = " "+ SettingsHelper.inferenceEngineForModel(model.path),
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
 text = "mmproj",
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
        onProbe: (() -> Unit)? = null,
        probeRunning: Boolean = false
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
                            val backend = if (hasMnn) "MNN" else "Unknown"
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
 text = "DL済み",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val modelDir = File(model.path)
                    val hasMnn = modelDir.listFiles()?.any { it.name.endsWith(".mnn") } == true
                    val backend = if (hasMnn) "MNN" else "Unknown"
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
                        text = "ダウンロード済み",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (onProbe != null && hasMnn) {
                            TextButton(
                                onClick = onProbe,
                                enabled = !probeRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (probeRunning) "プローブ中…" else "MNN I/O プローブ")
                            }
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f)
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
        // Bug fix (検索すると検索画面が閉じる):
        //   ここで検索結果のクリアや hfSearchResultsDialogVisible = false を
        //   行っていたが、searchHfModels() から検索のたびに呼ばれるため、
        //   ログイン済みで前回結果が残っていると再検索の瞬間に画面ごと閉じていた。
        //   トークン状態 (hfLinked) の更新だけに留め、表示フラグと結果リストは
        //   FAB メニュー・戻るボタン・クリアボタン・検索処理本体だけが制御する。
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
                com.nezumi_ai.data.inference.ImageModelBrowser.fetchAvailableModels(requireContext())
            }
            result.onSuccess { models ->
                if (models.isEmpty() && com.nezumi_ai.data.inference.ImageModelBrowser.hasAuthError()) {
                    // トークンが失効/無効な場合、リポジトリ取得が全滅して 0 件になり得る。
                    // 「0件のモデル」という紛らわしい表示ではなく、再ログインを促す。
                    imageModelsError = "HuggingFaceの認証が切れているため、モデル一覧を取得できませんでした。設定からHuggingFaceに再ログインしてください。"
                    renderHfTokenState()
                } else {
                    availableImageModels = models
                    imageModelsDialogVisible = true
                }
            }.onFailure { e ->
                imageModelsError = "取得失敗: ${e.message}"
            }
            imageModelsLoading = false
        }
    }

    private fun probeMnnSdIo(modelPath: String) {
        sdModelProbePath = modelPath
        viewLifecycleOwner.lifecycleScope.launch {
            val module = com.nezumi_ai.sd.MnnSdModule(requireContext())
            try {
                if (!module.isNativeAvailable()) {
                    toast("mnn_sd_jni が未ロードです。scripts/build_mnn_android.ps1 を実行してください")
                    return@launch
                }
                val result = module.probeModelDirectory(modelPath)
                Log.i("MnnSdModule", result.summary())
                val reportFile = File(requireContext().filesDir, "mnn_sd_probe_last.txt")
                withContext(Dispatchers.IO) {
                    reportFile.writeText(result.summary())
                }
                if (result.ok) {
                    toast("プローブ完了 (${result.logs.size} files)。logcat / ${reportFile.name} を確認")
                } else {
                    toast("プローブ失敗: ${result.errors.firstOrNull() ?: "unknown"}")
                }
            } finally {
                sdModelProbePath = null
                module.cleanup()
            }
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

                val resolvedDir = SdModelLayout.findUsableModelDir(modelDir)
                val validation = SdModelLayout.validate(modelDir)
                Log.d(
                    "ModelSettings",
                    "refreshSdModels: ${modelDir.name} usable=${validation.isUsable}, reason=${validation.reason}, resolved=${resolvedDir?.absolutePath}"
                )
                if (resolvedDir != null) {
                    models.add(ModelFileManager.ImportedTaskModel(
                        path = resolvedDir.absolutePath,
                        fileNameStem = modelDir.name,
                        shortDisplayName = modelDir.name,
                        hfRepoQualifier = null
                    ))
                    Log.d("ModelSettings", "refreshSdModels: added ${modelDir.name} (path=${resolvedDir.absolutePath})")
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
        hfHasSearched = true
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
                // Bug fix (検索ボタンを押すと検索画面が閉じる):
                //   旧実装はカード内検索だったため結果が出た瞬間に結果ページへ
                //   遷移 (hfSearchResultsDialogVisible = true) させていたが、
                //   現在はこのページ自体が検索画面なので、0件ヒットや失敗時に
                //   false を代入すると画面ごと閉じてしまう。表示フラグは
                //   FAB メニューと戻るボタンだけが制御し、ここでは触らない。
            }.onFailure {
                hfSearchResults = emptyList()
                hfSearchNextPageUrl = null
                hfSearchError = "検索失敗: ${it.message}"
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
        // 初期値ロード中は自動保存を流さない (state の初期化自体で保存されるのを防ぐ)。
        modelSettingsAutoSaveSuspended = true
        modelSettingsAutoSaveJob?.cancel()
        modelSettingsAutoSaveJob = null

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
            // 初期値ロード完了後にのみ自動保存を解除する。以降のユーザ入力に対して
            // のみ Composable 内の LaunchedEffect が保存をトリガする。
            modelSettingsAutoSaveSuspended = false
        }
    }

    /**
     * 自動保存用。バリデーション失敗時は trace レベルのログのみでサイレントにスキップ。
     * 保存ボタン介しと違い UI フィードバックを出さない (Toast もなし)。
     */
    private suspend fun autoPersistModelSettingsFromDialog() {
        val model = modelSettingsDialogModel ?: return
        val displayName = settingsDialogDisplayName
        val invalidChars = Regex("[\\\\/:*?\"<>|]")
        if (invalidChars.containsMatchIn(displayName)) return
        if (capabilityDialogTemplateMode == PromptTemplateStore.MODE_CUSTOM) {
            val err = PromptTemplateEngine.validate(capabilityDialogTemplateCustom)
            if (err != null) {
                capabilityDialogTemplateError = err
                return
            }
        }
        val isGguf = model.path.lowercase().endsWith(".gguf")
        val tokens = settingsDialogStopTokens
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val newCapabilities = ImportedModelCapabilities(
            imageEnabled = capabilityDialogImageEnabled,
            audioEnabled = capabilityDialogAudioEnabled,
            mmprojPath = capabilityDialogMmprojPath.ifBlank { null },
            thinkingEnabled = capabilityDialogThinkingEnabled,
            displayName = displayName.trim().ifBlank { null },
            toolCallingEnabled = capabilityDialogToolCallingEnabled
        )
        // ツール呼び出しの OFF は影響範囲が広いため、確認ダイアログを経由する。
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
                return
            }
        }
        try {
            withContext(Dispatchers.IO) {
                ImportedModelCapabilityStore.set(
                    requireContext(),
                    model.path,
                    newCapabilities
                )
                if (isGguf) {
                    settingsRepository.updateStopTokensForModel(model.path, tokens)
                }
                PromptTemplateStore.setSelection(
                    requireContext(),
                    model.path,
                    PromptTemplateStore.TemplateSelection(
                        mode = capabilityDialogTemplateMode,
                        customTemplate = capabilityDialogTemplateCustom
                    )
                )
            }
            capabilityDialogCurrentCapabilities = newCapabilities
        } catch (t: Throwable) {
            Log.w("ModelSettings", "autoPersistModelSettingsFromDialog failed: ${t.message}")
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
                // 終端状態に入ったカードはキャッシュから削除
                val terminalKeys = infos.filter {
                    it.state == WorkInfo.State.SUCCEEDED ||
                        it.state == WorkInfo.State.FAILED ||
                        it.state == WorkInfo.State.CANCELLED
                }.mapNotNull { info ->
                    val mid = info.progress.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                    val fp = info.progress.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                    if (mid != null && fp != null) "$mid|$fp" else null
                }.toSet()
                terminalKeys.forEach { hfDownloadCardCache.remove(it) }

                // WorkInfo から読めた情報でキャッシュを更新 (読めない期間は既存カードを温存)
                infos.forEach { info ->
                    if (info.state == WorkInfo.State.SUCCEEDED ||
                        info.state == WorkInfo.State.FAILED ||
                        info.state == WorkInfo.State.CANCELLED
                    ) return@forEach
                    val kind = info.progress.getString(ModelDownloadWorker.KEY_DOWNLOAD_KIND)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_DOWNLOAD_KIND)
                        ?: ModelDownloadWorker.DOWNLOAD_KIND_HF_CUSTOM
                    if (kind != ModelDownloadWorker.DOWNLOAD_KIND_HF_CUSTOM) return@forEach
                    val modelId = info.progress.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                    val filePath = info.progress.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                    if (modelId == null || filePath == null) return@forEach
                    val key = "$modelId|$filePath"
                    val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: info.outputData.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: hfDownloadCardCache[key]?.downloadedBytes ?: 0L
                    val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: info.outputData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: hfDownloadCardCache[key]?.totalBytes ?: 0L
                    val status = when (info.state) {
                        WorkInfo.State.ENQUEUED -> "待機中"
                        WorkInfo.State.RUNNING -> if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            "ダウンロード中 $percent% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                        } else "ダウンロード中"
                        WorkInfo.State.BLOCKED -> "待機中"
                        else -> hfDownloadCardCache[key]?.statusText ?: "待機中"
                    }
                    hfDownloadCardCache[key] = HfQueuedDownloadUiState(
                        modelId = modelId, filePath = filePath,
                        downloadedBytes = downloaded, totalBytes = total,
                        statusText = status,
                        isActive = info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.BLOCKED
                    )
                }

                hfQueuedDownloads = hfDownloadCardCache.values
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
                
                // Build download states list (キャッシュ方式: WorkInfo から読めない期間は既存カードを温存)
                // 終端状態に入ったカードはキャッシュから削除
                val terminalImageKeys = infos.filter {
                    it.state == WorkInfo.State.SUCCEEDED ||
                        it.state == WorkInfo.State.FAILED ||
                        it.state == WorkInfo.State.CANCELLED
                }.mapNotNull { info ->
                    info.progress.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                }.toSet()
                terminalImageKeys.forEach { imageModelDownloadCardCache.remove(it) }

                infos.forEach { info ->
                    if (info.state == WorkInfo.State.SUCCEEDED ||
                        info.state == WorkInfo.State.FAILED ||
                        info.state == WorkInfo.State.CANCELLED
                    ) return@forEach
                    val modelId = info.progress.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_ID)
                        ?: return@forEach
                    val modelName = info.progress.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_NAME)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_IMAGE_MODEL_NAME)
                        ?: modelId
                    val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: info.outputData.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: imageModelDownloadCardCache[modelId]?.downloadedBytes ?: 0L
                    val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: info.outputData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        .takeIf { it > 0L }
                        ?: imageModelDownloadCardCache[modelId]?.totalBytes ?: 0L

                    val status = when (info.state) {
                        WorkInfo.State.ENQUEUED -> "待機中"
                        WorkInfo.State.RUNNING -> if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            "ダウンロード中 $percent% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                        } else "ダウンロード中"
                        WorkInfo.State.BLOCKED -> "待機中"
                        else -> imageModelDownloadCardCache[modelId]?.statusText ?: "待機中"
                    }

                    imageModelDownloadCardCache[modelId] = ImageModelDownloadUiState(
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

                imageModelDownloadStates = imageModelDownloadCardCache.values.sortedBy { it.modelId }
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

 // 埋め込みモデルダウンロード進捗を観測（MemoryTextEmbedder の StateFlow を直接使用）
    //   ChatViewModel に依存せず、MemoryTextEmbedder が公開する StateFlow を直接 observe する。
    private fun observeEmbeddingDownloadWork() {
        viewLifecycleOwner.lifecycleScope.launch {
            MemoryTextEmbedder.downloadInProgress.collect { downloading ->
                if (!downloading) {
                    embeddingDownloadState = null
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            MemoryTextEmbedder.downloadProgress.collect { progress ->
                if (progress != null) {
                    embeddingDownloadState = EmbeddingDownloadUiState(
                        fileName = progress.fileName,
                        downloadedBytes = progress.downloaded,
                        totalBytes = progress.total,
                        isActive = true
                    )
                }
            }
        }
    }

 // 組み込みモデル + HFカスタム + 画像モデルのダウンロード速度を観測
    private fun observeDownloadSpeeds() {
        val speedMap = mutableMapOf<String, DownloadSpeedInfo>()
        // 組み込みモデル
        ModelFileManager.LocalModel.entries.forEach { model ->
            WorkManager.getInstance(requireContext())
                .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.modelWorkName(model))
                .observe(viewLifecycleOwner) { infos ->
                    val info = infos.maxByOrNull { it.runAttemptCount } ?: return@observe
                    if (info.state != WorkInfo.State.RUNNING) {
                        speedMap.remove(model.name)
                    } else {
                        val speed = info.progress.getDouble(ModelDownloadWorker.KEY_SPEED_MBPS, 0.0)
                        val eta = info.progress.getDouble(ModelDownloadWorker.KEY_ESTIMATED_REMAINING_SEC, 0.0)
                        val dl = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                        val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        speedMap[model.name] = DownloadSpeedInfo(speed, eta, dl, total)
                    }
                    activeDownloadSpeeds = speedMap.toMap()
                }
        }
        // HFカスタムモデル
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(ModelDownloadWorker.TAG_HF_CUSTOM_DOWNLOAD)
            .observe(viewLifecycleOwner) { infos ->
                infos.forEach { info ->
                    if (info.state != WorkInfo.State.RUNNING) return@forEach
                    val modelId = info.progress.getString(ModelDownloadWorker.KEY_HF_MODEL_ID) ?: return@forEach
                    val filePath = info.progress.getString(ModelDownloadWorker.KEY_HF_FILE_PATH) ?: return@forEach
                    val label = "${modelId}/${filePath.substringAfterLast("/")}"
                    val speed = info.progress.getDouble(ModelDownloadWorker.KEY_SPEED_MBPS, 0.0)
                    val eta = info.progress.getDouble(ModelDownloadWorker.KEY_ESTIMATED_REMAINING_SEC, 0.0)
                    val dl = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                    val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                    speedMap[label] = DownloadSpeedInfo(speed, eta, dl, total)
                }
                // 完了したものをクリーンアップ
                val activeKeys = infos.filter { it.state == WorkInfo.State.RUNNING }
                    .mapNotNull { it.progress.getString(ModelDownloadWorker.KEY_HF_MODEL_ID) + "/" + (it.progress.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)?.substringAfterLast("/")) }
                    .toSet()
                speedMap.keys.filter { it.contains("/") }.forEach { key ->
                    if (key !in activeKeys) speedMap.remove(key)
                }
                activeDownloadSpeeds = speedMap.toMap()
            }
    }

 // リポジトリ更新チェック: SHA256 ハッシュでローカルとリモートを比較
    //   HuggingFace の HEAD レスポンスの ETag / X-Linked-Etag に SHA256 が入っているため、
    //   ローカルファイルの SHA256 と比較することで確実に更新を検知できる。
    //   ファイルサイズ比較は HuggingFace とローカルで必ず差が出るため使えない。
    //   SHA256 計算はコストが高いため、ModelFileManager.getCachedLocalSha256() で
    //   ファイルの lastModified + length が変わらなければキャッシュを再利用する。
    private fun checkRepositoryUpdates() {
        if (repoUpdateCheckInProgress) return
        repoUpdateCheckInProgress = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val notifications = mutableListOf<RepoUpdateNotification>()
            val token = HfAuthManager.getToken(requireContext())
            // 組み込みモデルでダウンロード済みのものをチェック
            ModelFileManager.LocalModel.entries.forEach { model ->
                if (!ModelFileManager.isDownloaded(requireContext(), model)) return@forEach
                if (model == ModelFileManager.LocalModel.GEMMA3N_2B ||
                    model == ModelFileManager.LocalModel.GEMMA3N_4B) return@forEach
                val localFile = ModelFileManager.modelFile(requireContext(), model)
                if (localFile == null || !localFile.exists()) return@forEach
                val localSize = localFile.length()
                val displayName = titleFor(model)
                val url = ModelFileManager.remoteUrlForModel(model) ?: return@forEach
                // 1. リモートの SHA256 を HEAD リクエストで取得（ETag から抽出）
                val remoteSha256 = ModelFileManager.getRemoteSha256(url, token)
                if (remoteSha256 == null) {
                    Log.w("ModelSettings", "Could not get remote SHA256 for ${model.name}")
                    return@forEach
                }
                // 2. ローカルファイルの SHA256 を計算（キャッシュ付き）
                val localSha256 = ModelFileManager.getCachedLocalSha256(localFile)
                if (localSha256 == null) {
                    Log.w("ModelSettings", "Could not compute local SHA256 for ${model.name}")
                    return@forEach
                }
                // 3. ハッシュが異なれば更新あり
                if (localSha256 != remoteSha256) {
                    notifications.add(RepoUpdateNotification(
                        modelKey = model.name,
                        displayName = displayName,
                        lastModifiedRemote = null,
                        localFileSize = localSize,
                        remoteFileSize = null
                    ))
                    Log.i("ModelSettings", "Repository update detected for ${model.name}: local=$localSha256 remote=$remoteSha256")
                }
            }
            withContext(Dispatchers.Main) {
                repoUpdateNotifications = notifications
                repoUpdateCheckInProgress = false
            }
        }
    }

    /**
     * VoicevoxManager.isReady を購読して、自動初期化の進行中・完了を
     * UI の voicevoxInitializing に反映させる。
     * これにより、ダウンロード完了 → 自動初期化 の間も
     * 「busy」としてドロップダウンをロックできる。
     */
    private fun observeVoicevoxReadyState() {
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        val manager = (requireContext().applicationContext as MyApplication).getVoicevoxManager()
        viewLifecycleOwner.lifecycleScope.launch {
            manager.isReady.collect { ready ->
                if (!isAdded) return@collect
                // ダウンロード中は progress 側で例外なく busy になるので、
                // ここでは「初期化は未完了だがファイルは揃っている」間を拾う。
                voicevoxInitializing = !ready && manager.isModelFileReady() && manager.isDictionaryReady()
                refreshVoicevoxState()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            manager.installedModelFileName.collect {
                if (!isAdded) return@collect
                refreshVoicevoxState()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            manager.selectedStyleIdFlow.collect {
                if (!isAdded) return@collect
                refreshVoicevoxState()
            }
        }
    }

    /** VOICEVOX ダウンロードワーカーの進捗を購読する。 */
    private fun observeVoicevoxDownloadWork() {
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(ModelDownloadWorker.TAG_VOICEVOX_DOWNLOAD)
            .observe(viewLifecycleOwner) { infos ->
                val info = infos?.maxByOrNull { it.state.ordinal }
                    ?: infos?.firstOrNull()
                    ?: return@observe
                val app = requireContext().applicationContext as MyApplication
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val data = info.progress
                        voicevoxDownloadState = VoicevoxDownloadUiState(
                            fileName = data.getString(ModelDownloadWorker.KEY_VOICEVOX_FILE_NAME)
                                ?: app.getVoicevoxManager().getSelectedModelFileName(),
                            phase = data.getString(ModelDownloadWorker.KEY_VOICEVOX_PHASE)
                                ?: ModelDownloadWorker.VOICEVOX_PHASE_MODEL,
                            downloadedBytes = data.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                            totalBytes = data.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, -1L),
                            isActive = true
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        if (voicevoxDownloadState != null) {
                            voicevoxDownloadState = null
                            voicevoxStyleMenuExpanded = false
                            refreshVoicevoxState()
                            toast("音声モデルの準備が完了しました")
                        } else {
                            voicevoxDownloadState = null
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val message = info.outputData
                            .getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                            ?: "音声モデルのダウンロードに失敗しました"
                        if (voicevoxDownloadState != null) toast(message)
                        voicevoxDownloadState = null
                        refreshVoicevoxState()
                    }
                    WorkInfo.State.CANCELLED -> {
                        voicevoxDownloadState = null
                        refreshVoicevoxState()
                    }
                    else -> Unit
                }
            }
    }

    private fun refreshVoicevoxState() {
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        if (!isAdded) return
        val manager = (requireContext().applicationContext as MyApplication).getVoicevoxManager()
        val model = manager.modelFilePath()

        val modelExists: Boolean = manager.isModelFileReady()
        val dictionaryExists: Boolean = manager.isDictionaryReady()
        val savedStyleId: Int = manager.getSavedStyleId()
        val selectedModelFileName: String = manager.getSelectedModelFileName()
        val entry: VoicevoxManager.VoiceModelCatalogEntry? =
            VoicevoxManager.catalogEntryFor(selectedModelFileName)

        val catalogStyle: VoicevoxManager.VoiceStyle? = VoicevoxManager.modelCatalog
            .flatMap { it.styles }
            .firstOrNull { it.styleId == savedStyleId }
        val license = catalogStyle?.license

        val summary: String = when {
            modelExists && dictionaryExists ->
                "準備完了 · $selectedModelFileName (${formatBytes(model.length())})"
            modelExists -> "OpenJTalk辞書を準備しています..."
            else -> "声を選ぶと自動でダウンロードし初期化します"
        }

        val entryCreditLine: String? = entry?.creditLine
            ?.takeIf { it.isNotBlank() }
        val creditLineText: String = catalogStyle?.credit
            ?: entryCreditLine
            ?: com.nezumi_ai.voicevox.VoicevoxLicense.VOICEVOX_CREDIT

        val licenseTermsUrl: String? = license?.termsUrl?.takeIf { it.isNotBlank() }
        val licenseUrlText: String = licenseTermsUrl
            ?: com.nezumi_ai.voicevox.VoicevoxLicense.VOICEVOX_TERMS_URL

        val licenseNoteText: String? = license?.let { lic ->
            val parts: List<String> = listOfNotNull(lic.commercialLabel, lic.note)
            if (parts.isEmpty()) null else parts.joinToString(separator = " / ")
        }

        voicevoxState = VoicevoxModelUiState(
            modelExists = modelExists,
            dictionaryExists = dictionaryExists,
            selectedModelFileName = selectedModelFileName,
            modelPath = model.absolutePath,
            summaryLine = summary,
            selectedStyleLabel = catalogStyle?.detailName ?: "styleId: $savedStyleId",
            creditLine = creditLineText,
            licenseUrl = licenseUrlText,
            licenseNote = licenseNoteText
        )
    }

    // 16KB ページサイズ互換の判定・表示は削除。
    // VOICEVOX ランタイム (libvoicevox_onnxruntime.so) は 16KB アライン対応済みで、
    // Android 15+ の 16KB ページサイズ端末でも読み込めるため、UI で警告する必要がなくなった。

    private fun requestNotificationPermissionForDownload(model: ModelFileManager.LocalModel) {
        // ダウンロード前にメモリチェックして警告を設定
        val sizeBytes = getModelSizeBytes(model)
        val modelId = ModelFileManager.modelFileName(model)
        val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(
            requireContext(),
            sizeBytes,
            preloadMemoryWarningThresholdPercent,
            useAvailable = false,
            modelIdentifier = modelId
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

    /**
     * 組み込みモデルカードの DL ボタンの状態遷移:
     *   未ダウンロード → [ダウンロード] → ダウンロード中 → [一時停止] → 一時停止中 → [再開]
     */
    private fun onBuiltinDownloadButtonClicked(model: ModelFileManager.LocalModel) {
        val state = modelStates[model]
        when {
            state?.isDownloading == true -> {
                ModelDownloadWorker.pause(requireContext(), model)
                toast("一時停止しました。再開時は続きからダウンロードします")
            }
            else -> requestNotificationPermissionForDownload(model)
        }
    }

    private fun updatePausedState(model: ModelFileManager.LocalModel) {
        val state = modelStates[model] ?: return
        if (state.isDownloading || state.isDownloaded) {
            state.isPaused = false
            return
        }
        val partial = java.io.File("${ModelFileManager.modelFile(requireContext(), model).absolutePath}.download")
        state.isPaused = partial.exists() && partial.length() > 0L
    }

    private fun refreshModelStatus(model: ModelFileManager.LocalModel? = null) {
        val targets = model?.let { listOf(it) } ?: ModelFileManager.LocalModel.entries
        targets.forEach {
            val downloaded = ModelFileManager.isDownloaded(requireContext(), it)
            val state = modelStates[it] ?: return@forEach
            state.isDownloaded = downloaded
            val tmpFile = java.io.File("${ModelFileManager.modelFile(requireContext(), it).absolutePath}.download")
            state.isPaused = !downloaded && !state.isDownloading && tmpFile.exists() && tmpFile.length() > 0L
            state.status = when {
                downloaded -> getString(R.string.setup_ready_status)
                state.isPaused -> getString(R.string.model_download_paused_toast)
                else -> getString(R.string.setup_not_acquired_status)
            }
            if (!state.isDownloading) {
                state.progressText = ""
                state.progress = 0f
                state.showAccessButton = false
            }
            
            // メモリチェックを実行して警告を設定
            val sizeBytes = getModelSizeBytes(it)
            val modelId = ModelFileManager.modelFileName(it)
            val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(
                requireContext(),
                sizeBytes,
                preloadMemoryWarningThresholdPercent,
                useAvailable = false,
                modelIdentifier = modelId
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
                state.isPaused = false
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
                val modelId = ModelFileManager.modelFileName(model)
                val isMemoryLow = MemoryObserver.isMemoryLowForFileSize(
                    requireContext(),
                    sizeBytes,
                    preloadMemoryWarningThresholdPercent,
                    useAvailable = false,
                    modelIdentifier = modelId
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
                if (error.contains("中断") || error.contains("paused", ignoreCase = true)) {
                    state.status = "一時停止中"
                    state.isPaused = true
                } else {
                    state.status = "失敗: $error"
                }
                state.showAccessButton = error.contains("HTTP 403", ignoreCase = true)
                state.memoryWarning = null
            }
            WorkInfo.State.CANCELLED -> {
                state.isDownloading = false
                state.progressText = ""
                state.memoryWarning = null
                refreshModelStatus(model)
                updatePausedState(model)
                if (state.isPaused) {
                    state.status = "一時停止中 (続きから再開できます)"
                }
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
            // Fragment が detach 済みの場合もトークンは必ず保存する。
            //   UI 反映は isAdded チェック後に行うことでクラッシュを避ける。
            val ctx = context?.applicationContext
            if (!accessToken.isNullOrBlank() && ctx != null) {
                HfAuthManager.setToken(ctx, accessToken)
            }
            if (!isAdded) return@performTokenRequest
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (accessToken.isNullOrBlank()) {
                    toast("トークン取得失敗: $error")
                    renderHfTokenState()
                    return@runOnUiThread
                }
                // トークンはすでに保存済み。UI 状態を強制的に更新。
                hfLinked = true
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
 // 実際のダウンロード済みファイルサイズを優先して返す。
        //   未ダウンロードの場合のみフォールバック値（概算）を使用する。
        //   従来はハードコード値を返していたため、Gemma4 2B/4B の表示サイズが
        //   実際のファイルサイズと一致しない問題があった。
        val localFile = ModelFileManager.modelFile(requireContext(), model)
        if (localFile.exists() && localFile.length() > 0L) {
            return localFile.length()
        }
        // フォールバック: 未ダウンロード時の概算値
        return when (model) {
            ModelFileManager.LocalModel.GEMMA4_2B -> 2_400_000_000L  // 約 2.4GB
            ModelFileManager.LocalModel.GEMMA4_4B -> 3_410_000_000L  // 配布ファイルは約 3.41GB
            ModelFileManager.LocalModel.GEMMA3N_2B -> 2_000_000_000L  // 約 2GB
            ModelFileManager.LocalModel.GEMMA3N_4B -> 4_000_000_000L  // 約 4GB
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
        /** 一時停止中 (部分ファイルが残っていて続きから再開可能) */
        var isPaused by mutableStateOf(false)
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
        onPause: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        // 画像モデルの速度キーは modelName または modelId で登録される可能性があるため内包フォールバックする。
        val speedInfo = activeDownloadSpeeds[item.modelName]
            ?: activeDownloadSpeeds[item.modelId]
            ?: activeDownloadSpeeds.entries.firstOrNull { it.key.contains(item.modelId) }?.value
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
                // 各カードに通信速度と残り時間を表示
                speedInfo?.let { info ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format("%.1f MB/s", info.speedMbps),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.primary),
                            fontWeight = FontWeight.Bold
                        )
                        if (info.estimatedRemainingSec > 0) {
                            val remainMin = (info.estimatedRemainingSec / 60).toInt()
                            val remainSec = (info.estimatedRemainingSec % 60).toInt()
                            Text(
                                text = "残り ${remainMin}分${remainSec}秒",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary)
                            )
                        }
                    }
                }
                if (item.isActive && (onPause != null || onCancel != null)) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        onPause?.let { pauseAction ->
                            TextButton(onClick = pauseAction) { Text("一時停止") }
                        }
                        onCancel?.let { cancelAction ->
                            TextButton(onClick = cancelAction) { Text("キャンセル") }
                        }
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
        val summaryLine: String = "確認中...",
        val styles: List<VoicevoxManager.VoiceStyle> = emptyList(),
        val selectedStyleLabel: String = "未選択",
        val creditLine: String = com.nezumi_ai.voicevox.VoicevoxLicense.VOICEVOX_CREDIT,
        val licenseUrl: String = com.nezumi_ai.voicevox.VoicevoxLicense.VOICEVOX_TERMS_URL,
        val licenseNote: String? = null
    )

    /** VOICEVOX ダウンロードの進捗 UI 状態。 */
    private data class VoicevoxDownloadUiState(
        val fileName: String,
        val phase: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isActive: Boolean
    ) {
        val ratio: Float
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

        val label: String
            get() = when (phase) {
                ModelDownloadWorker.VOICEVOX_PHASE_DICTIONARY ->
                    "ステップ 2/2: OpenJTalk 辞書を取得中"
                else ->
                    "ステップ 1/2: $fileName をダウンロード中"
            }

        /**
         * 進捗下部の詳細行。
         * サイズ未判明のときも、現在の受信バイト数を必ず見せる。
         * 以前は「サイズ計測中...」だけだったため進んでいるのかどうか分からなかった。
         */
        val detail: String
            get() {
                val downloadedText = formatBytesStandalone(downloadedBytes)
                return if (totalBytes > 0L) {
                    val percent = (ratio * 100).toInt()
                    val totalText = formatBytesStandalone(totalBytes)
                    "$percent% · $downloadedText / $totalText"
                } else if (downloadedBytes > 0L) {
                    "$downloadedText 受信中 · サイズ計測中"
                } else {
                    "接続中..."
                }
            }

        companion object {
            private fun formatBytesStandalone(bytes: Long): String {
                if (bytes <= 0L) return "0 B"
                val kb = 1024.0
                return when {
                    bytes < kb -> "$bytes B"
                    bytes < kb * kb -> String.format("%.1f KB", bytes / kb)
                    bytes < kb * kb * kb -> String.format("%.1f MB", bytes / (kb * kb))
                    else -> String.format("%.2f GB", bytes / (kb * kb * kb))
                }
            }
        }
    }

 // 埋め込みモデルダウンロード進捗用 UI 状態
    private data class EmbeddingDownloadUiState(
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isActive: Boolean
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }

 // ネットワーク速度表示用
    private data class DownloadSpeedInfo(
        val speedMbps: Double,
        val estimatedRemainingSec: Double,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

 // リポジトリ更新通知用
    private data class RepoUpdateNotification(
        val modelKey: String,
        val displayName: String,
        val lastModifiedRemote: String?,
        val localFileSize: Long,
        val remoteFileSize: Long?
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

        val assetContext = LocalContext.current

        val notoFamily = remember(assetContext.assets) {

            createNotoSansJpFontFamily(assetContext.assets)

        }

        val notoTypography = remember(notoFamily) {

            createNotoSansJpTypography(notoFamily)

        }

        MaterialTheme(

            colorScheme = colorScheme,

            typography = notoTypography,

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
 * ローカルインポートモデルの「検索＋並び替え」バー。
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
                label = { Text(stringResource(id = R.string.model_settings_search_added_models)) },
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
 * importedTasks に「検索クエリ」と「並び替え」を適用して返す。
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
