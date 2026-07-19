package com.nezumi_ai.presentation.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.fragment.app.Fragment
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.sd.safety.ImageSafetyChecker
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.memory.MemorySaveMode
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.repository.SettingsRepository

import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.presentation.ui.composable.ErrorModalDialog
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsComposeFragment : Fragment() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var memoryRepository: MemoryRepository

    private var contextWindowInput by mutableStateOf("4096")
    private var temperatureInput by mutableStateOf("0.7")
    private var topPInput by mutableStateOf("0.95")
    private var topkInput by mutableStateOf("40")
    private var maxTokensInput by mutableStateOf("1024")
    private var contextCompressionEnabled by mutableStateOf(false)
    private var contextCompressionThresholdPercent by mutableStateOf(70)
    private val contextCompressionUiEnabled = BuildConfig.CONTEXT_COMPRESSION_ENABLED
    private var speculativeDecodingEnabled by mutableStateOf(false)
    private var requireMultimodal by mutableStateOf(false)
    private var preloadMemoryWarningThresholdPercent by mutableStateOf(MemoryObserver.DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT)
    private var selectedModel by mutableStateOf("E2B")
    private var backendType by mutableStateOf("CPU")
    private var themeMode by mutableStateOf(PreferencesHelper.THEME_SYSTEM)
    private var errorDialogMessage by mutableStateOf<String?>(null)
    private var versionDialogVisible by mutableStateOf(false)
    private var aboutDialogVisible by mutableStateOf(false)
    private var llamaCppThreads by mutableStateOf(InferenceConfig.getDefaultThreadCount())
    private var maxThreads by mutableStateOf(InferenceConfig.MAX_THREADS)
    private var llamaCppGpuLayers by mutableStateOf(0)
    private var llamaCppBatchSize by mutableStateOf(512)
    private var llamaCppUBatchSize by mutableStateOf(512)
    private var llamaCppKvUnified by mutableStateOf(true)
    private var llamaCppNKeep by mutableStateOf(0)
    private var llamaCppRopeFreqBase by mutableStateOf(0.0f)
    private var llamaCppRopeFreqScale by mutableStateOf(1.0f)
    private var ropeFreqBaseInput by mutableStateOf("0.0")
    private var memorySaveMode by mutableStateOf(MemorySaveMode.LLM.name)
    private var chatHistoryLimit by mutableStateOf(30)
    private var sdSteps by mutableStateOf(8)
    private var sdCfg by mutableStateOf(7.0f)
    // Feature (設定画面への集約):
    //   メインの画像生成ページにあった「スケジューラ設定」「シード値設定」の
    //   デフォルト値をここで管理する。メインページの入力 UI は引き続き使えるが、
    //   初期値とリセット先はここで定める。画面の縦長化を防ぐため、
    //   元ページの大きな UI をそのままコピーしないことを方針とする。
    private var sdSchedulerId by mutableStateOf(com.nezumi_ai.sd.SdScheduler.DEFAULT.id)
    private var sdDefaultSeedInput by mutableStateOf("")
    private var braveSearchApiKeyInput by mutableStateOf("")
    private var selectedSection by mutableStateOf(0)
    private var debugTextAInput by mutableStateOf("")
    private var debugTextBInput by mutableStateOf("")
    private var debugTextSimilarityResult by mutableStateOf<String?>(null)
    private var modelErrorDialogMessage by mutableStateOf<String?>(null)
    private var mtpEnabled by mutableStateOf(false)
    private var mtpDraftTokens by mutableStateOf(5)
    private var flashAttentionEnabled by mutableStateOf(true)
    private var dynamicBatchSizeEnabled by mutableStateOf(true)
    private var promptBatchSize by mutableStateOf(512)
    private var generationBatchSize by mutableStateOf(128)
    private var kvCacheOptimizationEnabled by mutableStateOf(true)
    private var contextShiftEnabled by mutableStateOf(true)

    // NSFW チェッカー用のデバッグ UI 状態。ノン UI スレッドに入らないよう collectAsState でバインドする。
    private var nsfwDebugBitmap by mutableStateOf<Bitmap?>(null)
    private var nsfwDebugStatus by mutableStateOf<String?>(null)
    private var nsfwDebugSafeProb by mutableStateOf<Float?>(null)
    private var nsfwDebugNsfwProb by mutableStateOf<Float?>(null)
    private var nsfwDebugRunning by mutableStateOf(false)
    private lateinit var nsfwDebugPickLauncher: ActivityResultLauncher<String>

    // 自動保存制御フラグ。loadInferenceSettings() の初期値適用中は true にして
    // 初期化の emit で保存が回らないようにする。loadInferenceSettings() 完了後に false。
    @Volatile private var settingsAutoSaveSuspended: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository.fromDatabase(db)
        memoryRepository = MemoryRepository(db.memoryDao())

        // Feature: 他の Fragment (例: ImageGenFragment) から arguments で
        //   startSection を伸ばしてもらえれば、そのタブを初期選択とする。
        //   指示書: 「設定リンクをクリックしたら画像タブに自動切り替えして」に対応。
        //   sectionTitles = [全般, 推論, 画像, メモリ, チャット, デバッグ] なので
        //   「画像」 = index 2。
        val startSection = arguments?.getInt("startSection", -1) ?: -1
        // ★ デバッグタブは BuildConfig.DEBUG 時のみ存在するので、リリースビルドでは上限を 4 に制限する。
        val maxAllowedSection = if (BuildConfig.DEBUG) 5 else 4
        if (startSection in 0..maxAllowedSection) {
            selectedSection = startSection
        }

        // Fragment.registerForActivityResult() は onCreate までに登録する必要がある。
        nsfwDebugPickLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            runNsfwDebugCheck(uri)
        }
    }

    /**
     * 選択された画像 URI に対して ImageSafetyChecker を走らせ、スコアを UI に反映する。
     * ImageSafetyChecker は open_nsfw.onnx (Yahoo Open NSFW, ResNet-50) を
     * assets からロードし [0: Safe, 1: NSFW] の 2 クラス確率を返す。
     */
    private fun runNsfwDebugCheck(uri: Uri) {
        nsfwDebugRunning = true
        nsfwDebugStatus = "画像を読み込み中…"
        nsfwDebugSafeProb = null
        nsfwDebugNsfwProb = null
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    } ?: error("画像のデコードに失敗しました")
                    val checker = ImageSafetyChecker(requireContext())
                    val probs = checker.check(bmp)
                        ?: error("NSFW チェッカーの推論に失敗しました (open_nsfw.onnx 未展開?)")
                    Triple(bmp, probs.getOrNull(0) ?: 0f, probs.getOrNull(1) ?: 0f)
                }
            }
            result.onSuccess { (bmp, safe, nsfw) ->
                nsfwDebugBitmap = bmp
                nsfwDebugSafeProb = safe
                nsfwDebugNsfwProb = nsfw
                nsfwDebugStatus = null
            }.onFailure { e ->
                nsfwDebugBitmap = null
                nsfwDebugStatus = "失敗: ${e.message}"
            }
            nsfwDebugRunning = false
        }
    }

    // この Fragment がバックグラウンドに行く際にも未保存の値を確実に flush する。
    // 自動保存のデバウンス・window 中にバックグラウンド化したときのデータロスを防ぐ。
    override fun onPause() {
        super.onPause()
        val error = validateSettings()
        if (error != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { persistSettings() }
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
                SettingsScreen()
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadInferenceSettings()
        // Note: modelErrorDialogMessage is handled by Compose UI in SettingsScreen()
    }

    override fun onResume() {
        super.onResume()
        loadInferenceSettings()
    }

    @Composable
    private fun SettingsScreen() {
        val chatViewModel = ViewModelProvider(requireActivity()).get(com.nezumi_ai.presentation.viewmodel.ChatViewModel::class.java)
        val sharedModelErrorMessage by chatViewModel.modelErrorDialogMessage.collectAsState()

        // 自動保存レイヤー: 入力フィールドを snapshotFlow で監視し、全項目を
        // すべてハッシュして単一の String キーにして 400ms デバウンスして
        // persistSettings() を回す。validate 失敗はサイレントスキップ。
        @OptIn(FlowPreview::class)
        LaunchedEffect(Unit) {
            snapshotFlow {
                buildString {
                    append(contextWindowInput); append('|')
                    append(temperatureInput); append('|')
                    append(topPInput); append('|')
                    append(topkInput); append('|')
                    append(maxTokensInput); append('|')
                    append(contextCompressionEnabled); append('|')
                    append(contextCompressionThresholdPercent); append('|')
                    append(speculativeDecodingEnabled); append('|')
                    append(requireMultimodal); append('|')
                    append(preloadMemoryWarningThresholdPercent); append('|')
                    append(backendType); append('|')
                    append(llamaCppThreads); append('|')
                    append(llamaCppGpuLayers); append('|')
                    append(llamaCppBatchSize); append('|')
                    append(llamaCppUBatchSize); append('|')
                    append(llamaCppKvUnified); append('|')
                    append(llamaCppNKeep); append('|')
                    append(llamaCppRopeFreqBase); append('|')
                    append(llamaCppRopeFreqScale); append('|')
                    append(memorySaveMode); append('|')
                    append(chatHistoryLimit); append('|')
                    append(sdSteps); append('|')
                    append(sdCfg); append('|')
                    append(braveSearchApiKeyInput); append('|')
                    append(mtpEnabled); append('|')
                    append(mtpDraftTokens); append('|')
                    append(flashAttentionEnabled); append('|')
                    append(dynamicBatchSizeEnabled); append('|')
                    append(promptBatchSize); append('|')
                    append(generationBatchSize); append('|')
                    append(kvCacheOptimizationEnabled); append('|')
                    append(contextShiftEnabled)
                }
            }
                .filter { !settingsAutoSaveSuspended }
                .distinctUntilChanged()
                .debounce(400)
                .collect {
                    // 入力不正の間はスキップするが、エラーダイアログは出さない。
                    if (validateSettings() != null) return@collect
                    runCatching { persistSettings() }
                }
        }

        errorDialogMessage?.let { message ->
            ErrorModalDialog(
                title = "設定エラー",
                message = message,
                onDismiss = { errorDialogMessage = null }
            )
        }

        sharedModelErrorMessage?.let { message ->
            // Parse message to extract title, body, and details
            val lines = message.split("\n\n")
            val title = lines.getOrNull(0) ?: "エラー"
            val body = lines.getOrNull(1) ?: lines.getOrNull(0) ?: message
            val detail = lines.getOrNull(2)
            ErrorModalDialog(
                title = title,
                message = body,
                detail = detail,
                onDismiss = { chatViewModel.dismissModelErrorDialogMessage() }
            )
        }

        if (versionDialogVisible) {
            VersionInfoDialog(
                onDismiss = { versionDialogVisible = false }
            )
        }
        if (aboutDialogVisible) {
            AboutDialog(
                onDismiss = { aboutDialogVisible = false },
                onOpenLicenses = {
                    aboutDialogVisible = false
                    findNavController().navigate(R.id.action_settingsFragment_to_licenseFragment)
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.statusBarsPadding()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onBackButtonPressed() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = stringResource(id = R.string.back),
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                    Text(
                        text = "設定",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorResource(id = R.color.text_primary),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item {
                // ★ デバッグタブはデバッグビルド時のみ表示する。
                //   リリースビルドでは BuildConfig.DEBUG=false なので自動的に非表示になる。
                val sectionTitles = buildList {
                    add("全般"); add("推論"); add("画像"); add("メモリ"); add("チャット")
                    if (BuildConfig.DEBUG) add("デバッグ")
                }
                ScrollableTabRow(
                    selectedTabIndex = selectedSection,
                    edgePadding = 0.dp,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedSection]),
                            color = colorResource(id = R.color.primary)
                        )
                    },
                    containerColor = colorResource(id = R.color.primary_light)
                ) {
                    val isDark = isSystemInDarkTheme()
                    sectionTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedSection == index,
                            onClick = { selectedSection = index },
                            selectedContentColor = if (isDark) Color.White else Color.Black,
                            unselectedContentColor = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Gray,
                            text = { Text(text = title) }
                        )
                    }
                }
            }

            item {
                when (selectedSection) {
                    0 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 強制的にUIをここに展開
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = colorResource(id = R.color.primary_light)
                            )
                        ) {
                            val context = LocalContext.current
                            var pinDialogVisible by remember { mutableStateOf(false) }
                            var pinConfirmDialogVisible by remember { mutableStateOf(false) }
                            var tempPin by remember { mutableStateOf("") }
                            var isSecretModeEnabled by remember { mutableStateOf(PreferencesHelper.isSecretModeEnabled(context)) }
                            var hasSecretModePin by remember { mutableStateOf(PreferencesHelper.hasSecretModePin(context)) }
                            var isAlwaysLockEnabled by remember { mutableStateOf(PreferencesHelper.isAlwaysLockEnabled(context)) }
                            var isStopKeyboardLearning by remember { mutableStateOf(PreferencesHelper.isStopKeyboardLearningEnabled(context)) }
                            var pendingAlwaysLockEnable by remember { mutableStateOf(false) }

                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = "全般設定", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                                // テーマ設定セクション
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "テーマ (現在: ${when(themeMode) {
                                            PreferencesHelper.THEME_LIGHT -> "ライト"
                                            PreferencesHelper.THEME_DARK -> "ダーク"
                                            else -> "システム"
                                        }})",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        FilterChip(
                                            selected = themeMode == PreferencesHelper.THEME_SYSTEM,
                                            onClick = {
                                                themeMode = PreferencesHelper.THEME_SYSTEM
                                                PreferencesHelper.setThemeMode(context, PreferencesHelper.THEME_SYSTEM)
                                                PreferencesHelper.applyThemeMode(context)
                                            },
                                            label = { Text("システム") },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = themeMode == PreferencesHelper.THEME_LIGHT,
                                            onClick = {
                                                themeMode = PreferencesHelper.THEME_LIGHT
                                                PreferencesHelper.setThemeMode(context, PreferencesHelper.THEME_LIGHT)
                                                PreferencesHelper.applyThemeMode(context)
                                            },
                                            label = { Text("ライト") },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = themeMode == PreferencesHelper.THEME_DARK,
                                            onClick = {
                                                themeMode = PreferencesHelper.THEME_DARK
                                                PreferencesHelper.setThemeMode(context, PreferencesHelper.THEME_DARK)
                                                PreferencesHelper.applyThemeMode(context)
                                            },
                                            label = { Text("ダーク") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

                                // アプリロック設定
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "アプリロックを常に有効化",
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "アプリ再開時に常に認証を求めます",
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isAlwaysLockEnabled || (pinDialogVisible && pendingAlwaysLockEnable),
                                        onCheckedChange = { checked ->
                                            if (checked && !hasSecretModePin) {
                                                pendingAlwaysLockEnable = true
                                                pinDialogVisible = true
                                            } else {
                                                isAlwaysLockEnabled = checked
                                                PreferencesHelper.setAlwaysLockEnabled(context, checked)
                                                if (!checked) pendingAlwaysLockEnable = false
                                            }
                                        }
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

                                // キーボード学習停止設定
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "キーボードの学習を停止",
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "ネズミAIでの入力をキーボードに学習させないようにします",
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isStopKeyboardLearning,
                                        onCheckedChange = { checked ->
                                            isStopKeyboardLearning = checked
                                            PreferencesHelper.setStopKeyboardLearningEnabled(context, checked)
                                        }
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

                                // シークレットモード設定
                                Text(
                                    text = "シークレットモード PIN",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isSecretModeEnabled) "有効（PIN 設定済み）" else "無効",
                                    color = if (isSecretModeEnabled) colorResource(id = R.color.success) else colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = { pinDialogVisible = true },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(text = if (hasSecretModePin) "PIN 変更" else "PIN 設定")
                                    }
                                    if (isSecretModeEnabled) {
                                        Button(
                                            onClick = {
                                                PreferencesHelper.clearSecretModePin(context)
                                                PreferencesHelper.setSecretModeEnabled(context, false)
                                                hasSecretModePin = false
                                                isSecretModeEnabled = false
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = colorResource(id = R.color.error)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(text = "リセット")
                                        }
                                    }
                                }

                                if (pinDialogVisible) {
                                    PinSetupDialog(
                                        hasExistingPin = hasSecretModePin,
                                        onPinSet = { pin ->
                                            tempPin = pin
                                            pinDialogVisible = false
                                            pinConfirmDialogVisible = true
                                        },
                                        onDismiss = {
                                            pinDialogVisible = false
                                            pendingAlwaysLockEnable = false
                                        }
                                    )
                                }

                                if (pinConfirmDialogVisible) {
                                    PinConfirmDialog(
                                        expectedPin = tempPin,
                                        onConfirmed = {
                                            PreferencesHelper.setSecretModePin(context, tempPin)
                                            if (pendingAlwaysLockEnable) {
                                                isAlwaysLockEnabled = true
                                                PreferencesHelper.setAlwaysLockEnabled(context, true)
                                                pendingAlwaysLockEnable = false
                                            } else {
                                                isSecretModeEnabled = true
                                                PreferencesHelper.setSecretModeEnabled(context, true)
                                            }
                                            hasSecretModePin = true
                                            pinConfirmDialogVisible = false
                                        },
                                        onMismatch = {
                                            pinConfirmDialogVisible = false
                                            pinDialogVisible = true
                                        },
                                        onDismiss = {
                                            pinConfirmDialogVisible = false
                                            pendingAlwaysLockEnable = false
                                        }
                                    )
                                }
                            }
                        }
                        WebSearchApiKeyCard()
                    }
                    1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InferenceParamsCard()
                        GgufLlamaCppSettingsCard()
                        LiteRtSettingsCard()
                    }
                    2 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ImageGenSettingsCard()
                    }
                    3 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MemoryManagementCard()
                    }
                    4 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatHistoryCard()
                    }
                    // ★ デバッグタブは BuildConfig.DEBUG 時のみ表示されるため、index 5 はデバッグビルドでのみ存在する。
                    //   リリースビルドで sectionTitles に含まれていないので selectedSection == 5 にならないが、
                    //   防御的に BuildConfig.DEBUG ガードも入れる。
                    5 -> if (BuildConfig.DEBUG) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DebugSettingsCard()
                        }
                    } else Unit
                    else -> {}
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { aboutDialogVisible = true }) {
                        Text(text = "このアプリについて")
                    }
                    TextButton(onClick = {
                        PreferencesHelper.resetInitialSetupCompleted(requireContext())
                        findNavController().navigate(R.id.setupWizardFragment)
                    }) {
                        Text(text = "セットアップを開く")
                    }
                    TextButton(onClick = { findNavController().navigate(R.id.action_settingsFragment_to_helpFragment) }) {
                        Text(text = stringResource(id = R.string.open_help_page))
                    }
                    TextButton(onClick = { findNavController().navigate(R.id.action_settingsFragment_to_licenseFragment) }) {
                        Text(text = stringResource(id = R.string.open_license_page))
                    }
                }
            }
        }
    }



    @Composable
    private fun WebSearchApiKeyCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Brave Search API", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                Text(
                    text = "Brave Search の API キーを設定します。ツール呼び出し時にこのキーが使用されます。",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = braveSearchApiKeyInput,
                    onValueChange = { braveSearchApiKeyInput = it },
                    label = { Text("APIキー") },
                    placeholder = { Text("brave_api_key を入力") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Text(
                    text = if (braveSearchApiKeyInput.isBlank()) "未設定の場合、ウェブ検索ツールは動作しません。" else "現在設定済みの API キーが保存されています。",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun BackendCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "バックエンド", fontWeight = FontWeight.Bold)
                Text(
                    text = "現在のバックエンド: $backendType",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { versionDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("llama.cpp / LiteRT-LM バージョンを確認")
                }
            }
        }
    }

    @Composable
    private fun InferenceParamsCard() {
        // モデル別のコンテキスト最大値
        val maxContextWindow = if (selectedModel.equals("Gemma4-2B", ignoreCase = true) ||
                                    selectedModel.equals("Gemma4-4B", ignoreCase = true)) {
            8192
        } else {
            4096
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "推論パラメータ", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                // コンテキストサイズと最大トークン数を2列グリッド
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = contextWindowInput,
                        onValueChange = { contextWindowInput = it },
                        label = { Text("コンテキストサイズ") },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = maxTokensInput,
                        onValueChange = { maxTokensInput = it },
                        label = { Text("最大トークン数") },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        singleLine = true
                    )
                }

                // Temperature Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "温度 (Temperature)",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = temperatureInput,
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = temperatureInput.toFloatOrNull() ?: 0.7f,
                        onValueChange = { temperatureInput = String.format("%.1f", it) },
                        valueRange = 0f..1.5f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Top-K Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Top-K",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = topkInput,
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = topkInput.toIntOrNull()?.toFloat() ?: 40f,
                        onValueChange = { topkInput = it.toInt().toString() },
                        valueRange = 1f..100f,
                        steps = 98,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Top-P Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Top-P",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = topPInput,
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = topPInput.toFloatOrNull() ?: 0.95f,
                        onValueChange = { topPInput = String.format("%.2f", it) },
                        valueRange = 0f..1f,
                        steps = 100,
                        modifier = Modifier.fillMaxWidth()
                    )
                }



                if (contextCompressionUiEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 自動圧縮トグル
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "自動圧縮",
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = contextCompressionEnabled,
                                onCheckedChange = { contextCompressionEnabled = it }
                            )
                        }
                        Text(
                            text = "有効にすると指定した割合を超えたときに自動的に圧縮します",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )

                        // 圧縮しきい値（animatedAlphaとかで無効時は薄くしてもいい）
                        if (contextCompressionEnabled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "圧縮しきい値",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${contextCompressionThresholdPercent}%",
                                    color = colorResource(id = R.color.primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Slider(
                                value = contextCompressionThresholdPercent.toFloat(),
                                onValueChange = { value ->
                                    contextCompressionThresholdPercent = value.roundToInt()
                                        .coerceIn(
                                            InferenceConfig.MIN_COMPRESSION_THRESHOLD,
                                            InferenceConfig.MAX_COMPRESSION_THRESHOLD
                                        )
                                },
                                valueRange = InferenceConfig.MIN_COMPRESSION_THRESHOLD.toFloat()..
                                    InferenceConfig.MAX_COMPRESSION_THRESHOLD.toFloat(),
                                steps = InferenceConfig.MAX_COMPRESSION_THRESHOLD -
                                    InferenceConfig.MIN_COMPRESSION_THRESHOLD - 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "メモリ使用量がこの割合を超えると自動圧縮します",
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "プリロードメモリ警告閾値",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${preloadMemoryWarningThresholdPercent}%",
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = preloadMemoryWarningThresholdPercent.toFloat(),
                        onValueChange = { value ->
                            preloadMemoryWarningThresholdPercent = value.roundToInt().coerceIn(0, 100)
                        },
                        valueRange = 0f..100f,
                        steps = 100,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "UI では 0-100、内部では 3 倍の 0-300 の値になります",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // GGUF / llama.cpp 固有設定は別のカードに移動しました
            }
        }
    }

    @Composable
    private fun GgufLlamaCppSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "GGUF / llama.rn 設定", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                Text(
                    text = "インポートした GGUF モデル専用の設定です。llama.rn エンジンに適用されます。",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )

                var basicExpanded by remember { mutableStateOf(true) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { basicExpanded = !basicExpanded }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "基本設定",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (basicExpanded) "▼" else "▶",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    if (basicExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "CPU スレッド数",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = llamaCppThreads.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppThreads.toFloat(),
                                    onValueChange = { llamaCppThreads = it.roundToInt() },
                                    valueRange = 1f..maxThreads.toFloat(),
                                    steps = maxOf(0, maxThreads - 2),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "GPU レイヤー数 (Offload)",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = llamaCppGpuLayers.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppGpuLayers.toFloat(),
                                    onValueChange = { llamaCppGpuLayers = it.roundToInt() },
                                    valueRange = 0f..128f,
                                    steps = 127,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "バッチサイズ",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = llamaCppBatchSize.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppBatchSize.toFloat(),
                                    onValueChange = { llamaCppBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                    valueRange = 32f..2048f,
                                    steps = 2016/32 - 1,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "内部バッチサイズ (n_ubatch)",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = llamaCppUBatchSize.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppUBatchSize.toFloat(),
                                    onValueChange = { llamaCppUBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                    valueRange = 32f..2048f,
                                    steps = 2016/32 - 1,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "kvUnified",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "KV キャッシュの統合モードを有効化します。",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = llamaCppKvUnified,
                                    onCheckedChange = { llamaCppKvUnified = it }
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "RoPE周波数基数",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = ropeFreqBaseInput,
                                    onValueChange = { newValue ->
                                        ropeFreqBaseInput = newValue
                                        newValue.toFloatOrNull()?.let { llamaCppRopeFreqBase = it }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                Text(
                                    text = "0 = 自動設定",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "RoPE周波数スケール",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = String.format("%.2f", llamaCppRopeFreqScale),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppRopeFreqScale,
                                    onValueChange = { llamaCppRopeFreqScale = it },
                                    valueRange = 0.5f..5.0f,
                                    steps = 44,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "1.0 = デフォルト",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                var perfExpanded by remember { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { perfExpanded = !perfExpanded }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "パフォーマンス最適化",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (perfExpanded) "▼" else "▶",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    if (perfExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "MTP (投機的デコーディング)",
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "複数トークンを並列生成して高速化（2-3倍）",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = mtpEnabled,
                                    onCheckedChange = { mtpEnabled = it }
                                )
                            }

                            if (mtpEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "MTP Draft トークン数",
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = mtpDraftTokens.toString(),
                                            color = colorResource(id = R.color.primary),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                    Slider(
                                        value = mtpDraftTokens.toFloat(),
                                        onValueChange = { mtpDraftTokens = it.roundToInt() },
                                        valueRange = 1f..16f,
                                        steps = 14,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = "推奨: 5-8 (多いほど高速だがメモリ消費増)",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Flash Attention",
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "メモリ効率的な Attention 計算",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = flashAttentionEnabled,
                                    onCheckedChange = { flashAttentionEnabled = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "動的バッチサイズ調整",
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "プロンプト処理と生成で異なるバッチサイズを使用",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = dynamicBatchSizeEnabled,
                                    onCheckedChange = { dynamicBatchSizeEnabled = it }
                                )
                            }

                            if (dynamicBatchSizeEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "プロンプト用バッチサイズ",
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = promptBatchSize.toString(),
                                            color = colorResource(id = R.color.primary),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                    Slider(
                                        value = promptBatchSize.toFloat(),
                                        onValueChange = { promptBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                        valueRange = 32f..2048f,
                                        steps = 2016/32 - 1,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "生成用バッチサイズ",
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = generationBatchSize.toString(),
                                            color = colorResource(id = R.color.primary),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                    Slider(
                                        value = generationBatchSize.toFloat(),
                                        onValueChange = { generationBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                        valueRange = 32f..2048f,
                                        steps = 2016/32 - 1,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = "推奨: プロンプト=512, 生成=128",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "KVキャッシュ最適化",
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "ハイブリッドモデル対応の賢いキャッシュ管理",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = kvCacheOptimizationEnabled,
                                    onCheckedChange = { kvCacheOptimizationEnabled = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "コンテキストシフト",
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "コンテキスト満杯時に古い部分を自動削除",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = contextShiftEnabled,
                                    onCheckedChange = { contextShiftEnabled = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    @Composable
    private fun LiteRtSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "LiteRT-LM 設定", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "バックエンド（LiteRT-LM 専用）",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "CPU / GPU / NPU の選択は LiteRT-LM モデルの推論にのみ適用されます。",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = backendType == "CPU",
                            onClick = { backendType = "CPU" },
                            label = { Text("CPU") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = backendType == "GPU",
                            onClick = { backendType = "GPU" },
                            label = { Text("GPU") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = backendType == "NPU",
                            onClick = { backendType = "NPU" },
                            label = { Text("NPU") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                TextButton(
                    onClick = { versionDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("llama.cpp / LiteRT-LM バージョンを確認")
                }
                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "投機的デコーディング",
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "LiteRT 推論の高速化を有効化します。",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = speculativeDecodingEnabled,
                        onCheckedChange = { speculativeDecodingEnabled = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "requireMultimodal",
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "vision/audio executor を必須化します。",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = requireMultimodal,
                        onCheckedChange = { requireMultimodal = it }
                    )
                }
            }
        }
    }


    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun ImageGenSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "画像生成設定", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                Text(
                    text = "ステップ数・CFG スケールに加え、メインの生成ページで使う「スケジューラ」「シード」の初期値をここで管理します。",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )

                // ステップ数 Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ステップ数",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$sdSteps / 50",
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = sdSteps.toFloat(),
                        onValueChange = { sdSteps = it.toInt() },
                        valueRange = 1f..50f,
                        steps = 48,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // CFG Scale Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CFG スケール",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format("%.1f", sdCfg),
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = sdCfg,
                        onValueChange = { sdCfg = it },
                        valueRange = 1f..20f,
                        steps = 38,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ---- Scheduler (コンパクトなドロップダウン) ----
                //   メインページ側の Chip を 8 個並べる UI をそのままコピーすると
                //   設定画面も縦に弸むため、ここでは 1 行の ExposedDropdownMenu に集約する。
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "スケジューラ（初期値）",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    var schedulerExpanded by remember { mutableStateOf(false) }
                    val schedulerOptions = remember { com.nezumi_ai.sd.SdScheduler.values().toList() }
                    val currentScheduler = com.nezumi_ai.sd.SdScheduler.fromId(sdSchedulerId)
                    ExposedDropdownMenuBox(
                        expanded = schedulerExpanded,
                        onExpandedChange = { schedulerExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentScheduler.displayName,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = schedulerExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = schedulerExpanded,
                            onDismissRequest = { schedulerExpanded = false }
                        ) {
                            schedulerOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.displayName) },
                                    onClick = {
                                        sdSchedulerId = option.id
                                        schedulerExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // ---- Seed (デフォルト値) ----
                //   -1 (空欄) = ランダム。ここでは保存には Preferences を使わず、
                //   入力値のバリデーションとデフォルト値提示に役割を限定。
                //   (SD の実際の seed は生成タブ側のフィールドで逐回指定するフローを維持)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "シード値（デフォルト、空欄でランダム）",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = sdDefaultSeedInput,
                        onValueChange = { raw ->
                            // 数字のみ受け付け (先頭のマイナスも許容)
                            val cleaned = raw.filterIndexed { idx, c ->
                                c.isDigit() || (idx == 0 && c == '-')
                            }
                            sdDefaultSeedInput = cleaned
                        },
                        singleLine = true,
                        placeholder = { Text("-1") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "※ 実際の生成に使うシードはメインの「画像生成」ページ上で逐回確定されます。",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    @Composable
    private fun MemoryManagementCard() {
        val memories by memoryRepository.observeMemories().collectAsState(initial = emptyList())
        var showMemoryListModal by remember { mutableStateOf(false) }
        var confirmDeleteAll by remember { mutableStateOf(false) }

        if (confirmDeleteAll) {
            AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                title = { Text("メモリを全削除") },
                text = { Text("保存済みメモリをすべて削除します。") },
                confirmButton = {
                    Button(onClick = {
                        viewLifecycleOwner.lifecycleScope.launch {
                            memoryRepository.softDeleteAll()
                            confirmDeleteAll = false
                            toast("メモリを削除しました")
                        }
                    }) {
                        Text("削除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteAll = false }) {
                        Text("キャンセル")
                    }
                }
            )
        }

        if (showMemoryListModal) {
            MemoryListModal(
                memories = memories,
                onDismiss = { showMemoryListModal = false },
                onDeleteMemory = { memoryId ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        memoryRepository.softDelete(memoryId)
                    }
                }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "メモリ管理", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                        Text(
                            text = "${memories.size}件のメモリ",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row {
                        TextButton(
                            enabled = memories.isNotEmpty(),
                            onClick = { showMemoryListModal = true }
                        ) {
                            Text("一覧表示")
                        }
                        TextButton(
                            enabled = memories.isNotEmpty(),
                            onClick = { confirmDeleteAll = true }
                        ) {
                            Text("全削除")
                        }
                    }
                }

                Text(
                    text = "メモリ保存方式",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = memorySaveMode == MemorySaveMode.LLM.name,
                        onClick = { memorySaveMode = MemorySaveMode.LLM.name },
                        label = { Text("LLM抽出") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = memorySaveMode == MemorySaveMode.RULE_BASED.name,
                        onClick = { memorySaveMode = MemorySaveMode.RULE_BASED.name },
                        label = { Text("ルールベース") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    @Composable
    private fun MemoryListModal(
        memories: List<com.nezumi_ai.data.database.entity.MemoryEntity>,
        onDismiss: () -> Unit,
        onDeleteMemory: (Long) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("保存済みメモリ一覧") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(memories) { memory ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = memory.content,
                                    color = colorResource(id = R.color.text_primary),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "重要度 ${String.format("%.2f", memory.importance)} / 参照 ${memory.accessCount}回",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = { onDeleteMemory(memory.id) }) {
                                Text("削除")
                            }
                        }
                        HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.14f), thickness = 1.dp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        )
    }

    @Composable
    private fun DebugSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "デバッグ", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                    SvgSpinner(modifier = Modifier.size(32.dp))
                }
                Text(
                    text = "モデル埋め込みによる類似度",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "単語やフレーズを入力して、埋め込みモデルを使った類似度を計算します。",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                val localContext = LocalContext.current
                val memoryInfoFlow = remember(localContext) {
                    MemoryObserver.observeSystemMemoryInfo(localContext)
                }
                val systemMemoryInfo by memoryInfoFlow.collectAsState(
                    initial = MemoryObserver.SystemMemoryInfo(0, 0, 0, 0, 0, false)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "システムメモリ状況 (1秒更新)", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "使用率: ${systemMemoryInfo.usedPercent}% / 空き率: ${systemMemoryInfo.availablePercent}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "合計: ${systemMemoryInfo.totalMemoryMB}MB / 使用: ${systemMemoryInfo.usedMemoryMB}MB / 空き: ${systemMemoryInfo.availableMemoryMB}MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                        Text(
                            text = if (systemMemoryInfo.lowMemoryFlag) "低メモリ状態です。" else "メモリ状態は安定しています。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (systemMemoryInfo.lowMemoryFlag) MaterialTheme.colorScheme.error else colorResource(id = R.color.text_secondary)
                        )
                        Text(
    text = "source: ${systemMemoryInfo.source}",
    style = MaterialTheme.typography.bodySmall,
    color = colorResource(id = R.color.text_secondary)
)
                    }
                }

                OutlinedTextField(
                    value = debugTextAInput,
                    onValueChange = { debugTextAInput = it },
                    label = { Text("テキストA") },
                    placeholder = { Text("犬") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                    maxLines = 4
                )
                OutlinedTextField(
                    value = debugTextBInput,
                    onValueChange = { debugTextBInput = it },
                    label = { Text("テキストB") },
                    placeholder = { Text("猫") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                    maxLines = 4
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        errorDialogMessage = null
                        debugTextSimilarityResult = null
                        if (debugTextAInput.isBlank()) {
                            errorDialogMessage = "テキストAを入力してください。"
                            return@Button
                        }
                        if (debugTextBInput.isBlank()) {
                            errorDialogMessage = "テキストBを入力してください。"
                            return@Button
                        }
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            MemoryTextEmbedder.initializeAsync(localContext)
                            val embeddingA = MemoryTextEmbedder.embed(debugTextAInput)
                            val embeddingB = MemoryTextEmbedder.embed(debugTextBInput)
                            val normA = MemoryRepository.l2norm(embeddingA)
                            val normB = MemoryRepository.l2norm(embeddingB)
                            val similarity = runCatching {
                                MemoryRepository.cosineSimilarity(embeddingA, normA, embeddingB, normB)
                            }.getOrNull()
                            withContext(Dispatchers.Main) {
                                if (embeddingA.isEmpty() || embeddingB.isEmpty()) {
                                    errorDialogMessage = "埋め込みの計算に失敗しました。"
                                    return@withContext
                                }
                                if (embeddingA.size != embeddingB.size) {
                                    errorDialogMessage = "埋め込み次元が一致しません。"
                                    return@withContext
                                }
                                if (normA == 0f || normB == 0f) {
                                    errorDialogMessage = "埋め込みがゼロベクトルになりました。"
                                    return@withContext
                                }
                                debugTextSimilarityResult = String.format("モデル埋め込み類似度: %.6f", similarity ?: 0.0)
                            }
                        }
                    }) {
                        Text("モデルで計算する")
                    }
                    Button(onClick = {
                        debugTextAInput = ""
                        debugTextBInput = ""
                        debugTextSimilarityResult = null
                        errorDialogMessage = null
                    }) {
                        Text("クリア")
                    }
                }

                debugTextSimilarityResult?.let {
                    Text(text = it, color = colorResource(id = R.color.primary))
                }

                // ---- NSFW チェッカー (open_nsfw.onnx / Yahoo Open NSFW) ----
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "選択した画像の NSFW チェック",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "ギャラリーから選んだ画像を、内蔵の open_nsfw モデルで判定し、\n" +
                        "safe / nsfw の確率を表示します。実際の生成フローと別に確認できます。",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { nsfwDebugPickLauncher.launch("image/*") },
                        enabled = !nsfwDebugRunning
                    ) {
                        Text(if (nsfwDebugRunning) "判定中…" else "画像を選択して NSFW チェック")
                    }
                    Button(onClick = {
                        nsfwDebugBitmap = null
                        nsfwDebugStatus = null
                        nsfwDebugSafeProb = null
                        nsfwDebugNsfwProb = null
                    }, enabled = !nsfwDebugRunning) {
                        Text("クリア")
                    }
                }
                nsfwDebugStatus?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                nsfwDebugBitmap?.let { bmp ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "NSFW チェック対象画像",
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val safe = nsfwDebugSafeProb
                            val nsfw = nsfwDebugNsfwProb
                            if (safe != null && nsfw != null) {
                                val verdict = if (nsfw >= 0.8f) "BLOCK (本番ではブロック)" else "ALLOW"
                                val verdictColor = if (nsfw >= 0.8f)
                                    MaterialTheme.colorScheme.error else colorResource(id = R.color.primary)
                                Text(
                                    text = "判定: $verdict",
                                    color = verdictColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = String.format("safe: %.4f", safe),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = String.format("nsfw: %.4f", nsfw),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "しきい値: nsfw >= 0.8 でブロック",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    modelErrorDialogMessage = "モデルのロードに失敗しました。デバッグ用モーダルを表示しています。"
                }) {
                    Text("モデルエラーを表示")
                }
            }
        }
    }

    @Composable
    private fun ChatHistoryCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "チャット履歴管理", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                Text(
                    text = "履歴保存件数",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = chatHistoryLimit == 10,
                        onClick = { chatHistoryLimit = 10 },
                        label = { Text("10") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 30,
                        onClick = { chatHistoryLimit = 30 },
                        label = { Text("30") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 50,
                        onClick = { chatHistoryLimit = 50 },
                        label = { Text("50") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == -1,
                        onClick = { chatHistoryLimit = -1 },
                        label = { Text("無制限") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }



    private fun loadInferenceSettings() {
        settingsAutoSaveSuspended = true
        viewLifecycleOwner.lifecycleScope.launch {
            val config = settingsRepository.getInferenceConfig(requireContext())
            val systemPrompt = settingsRepository.getSystemPrompt()
            val userName = settingsRepository.getUserName()
            val model = settingsRepository.getSelectedModel()
            selectedModel = model
            val contextWindow = settingsRepository.getContextWindowForModel(model)
            val threads = settingsRepository.getLlamaCppThreads()
            val gpuLayers = settingsRepository.getLlamaCppGpuLayers()
            val batchSize = settingsRepository.getLlamaCppBatchSize()
            val uBatchSize = settingsRepository.getLlamaCppUBatchSize()
            val nKeep = settingsRepository.getLlamaCppNKeep()
            val ropeFreqBase = settingsRepository.getLlamaCppRopeFreqBase()
            val ropeFreqScale = settingsRepository.getLlamaCppRopeFreqScale()
            val historyLimit = settingsRepository.getChatHistoryLimit()
            contextWindowInput = contextWindow.toString()
            temperatureInput = config.temperature.toString()
            topPInput = config.topP.toString()
            topkInput = config.maxTopK.toString()
            maxTokensInput = config.maxTokens.toString()
            preloadMemoryWarningThresholdPercent = settingsRepository.getPreloadMemoryWarningThresholdPercent()
            memorySaveMode = settingsRepository.getMemorySaveMode().name
            contextCompressionEnabled = config.contextCompressionEnabled
            contextCompressionThresholdPercent = config.contextCompressionThresholdPercent
            speculativeDecodingEnabled = settingsRepository.isSpeculativeDecodingEnabled()
            requireMultimodal = PreferencesHelper.isRequireMultimodal(requireContext())
            backendType = config.backendType
            themeMode = PreferencesHelper.getThemeMode(requireContext())
            braveSearchApiKeyInput = PreferencesHelper.getBraveSearchApiKey(requireContext())
            maxThreads = InferenceConfig.MAX_THREADS
            llamaCppThreads = threads.coerceIn(1, maxThreads)
            llamaCppGpuLayers = gpuLayers
            llamaCppBatchSize = batchSize
            llamaCppUBatchSize = uBatchSize
            llamaCppKvUnified = settingsRepository.getLlamaCppKvUnified()
            llamaCppNKeep = nKeep
            llamaCppRopeFreqBase = ropeFreqBase
            llamaCppRopeFreqScale = ropeFreqScale
            ropeFreqBaseInput = String.format("%.1f", ropeFreqBase)
            chatHistoryLimit = historyLimit
            sdSteps = PreferencesHelper.getSdSteps(requireContext())
            sdCfg = PreferencesHelper.getSdCfg(requireContext())
            sdSchedulerId = PreferencesHelper.getSdScheduler(requireContext())
            mtpEnabled = settingsRepository.isMtpEnabled()
            mtpDraftTokens = settingsRepository.getMtpDraftTokens()
            flashAttentionEnabled = settingsRepository.isFlashAttentionEnabled()
            dynamicBatchSizeEnabled = settingsRepository.isDynamicBatchSizeEnabled()
            promptBatchSize = settingsRepository.getPromptBatchSize()
            generationBatchSize = settingsRepository.getGenerationBatchSize()
            kvCacheOptimizationEnabled = settingsRepository.isKvCacheOptimizationEnabled()
            contextShiftEnabled = settingsRepository.isContextShiftEnabled()
            // 初期値適用後に自動保存を解除。
            settingsAutoSaveSuspended = false
        }
    }

    private fun validateSettings(): String? {
        val temperature = temperatureInput.toFloatOrNull()
        val topP = topPInput.toFloatOrNull()
        val topK = topkInput.toIntOrNull()
        val maxTokens = maxTokensInput.toIntOrNull()
        val contextWindow = contextWindowInput.toIntOrNull()

        if (temperature == null || topP == null || topK == null || maxTokens == null || contextWindow == null) {
            return "推論設定の入力値が不正です"
        }
        if (temperature !in InferenceConfig.MIN_TEMPERATURE..InferenceConfig.MAX_TEMPERATURE) {
            return "温度は ${InferenceConfig.MIN_TEMPERATURE} - ${InferenceConfig.MAX_TEMPERATURE} の範囲で入力してください"
        }
        if (topP !in InferenceConfig.MIN_TOP_P..InferenceConfig.MAX_TOP_P) {
            return "Top-P は ${InferenceConfig.MIN_TOP_P} - ${InferenceConfig.MAX_TOP_P} の範囲で入力してください"
        }
        if (topK !in InferenceConfig.MIN_TOP_K..InferenceConfig.MAX_TOP_K) {
            return "Top-K は ${InferenceConfig.MIN_TOP_K} - ${InferenceConfig.MAX_TOP_K} の範囲で入力してください"
        }
        if (maxTokens !in InferenceConfig.MIN_MAX_TOKENS..InferenceConfig.MAX_MAX_TOKENS) {
            return "Max Tokens は ${InferenceConfig.MIN_MAX_TOKENS} - ${InferenceConfig.MAX_MAX_TOKENS} の範囲で入力してください"
        }
        // モデル別のコンテキストウィンドウ制限を確認
        val maxContextWindow = if (selectedModel.equals("Gemma4-2B", ignoreCase = true) ||
                                    selectedModel.equals("Gemma4-4B", ignoreCase = true)) {
            8192
        } else {
            4096
        }
        if (contextWindow !in 512..maxContextWindow) {
            return "コンテキストは 512 - $maxContextWindow の範囲で入力してください"
        }
        if (contextCompressionThresholdPercent !in
            InferenceConfig.MIN_COMPRESSION_THRESHOLD..InferenceConfig.MAX_COMPRESSION_THRESHOLD
        ) {
            return "圧縮しきい値は ${InferenceConfig.MIN_COMPRESSION_THRESHOLD} - ${InferenceConfig.MAX_COMPRESSION_THRESHOLD} の範囲で入力してください"
        }
        if (preloadMemoryWarningThresholdPercent !in
            MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT..MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT
        ) {
            return "プリロードメモリ警告閾値は ${MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT} - ${MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT} の範囲で設定してください"
        }
        return null
    }

    private suspend fun persistSettings() {
        val temperature = temperatureInput.toFloatOrNull() ?: 0.7f
        val topP = topPInput.toFloatOrNull() ?: 0.95f
        val topK = topkInput.toIntOrNull() ?: 40
        val maxTokens = maxTokensInput.toIntOrNull() ?: 1024
        val contextWindow = contextWindowInput.toIntOrNull() ?: 4096

        settingsRepository.updateInferenceConfig(
            contextCompressionEnabled = contextCompressionEnabled,
            contextCompressionThresholdPercent = contextCompressionThresholdPercent,
            temperature = temperature,
            topP = topP,
            maxTopK = topK,
            maxTokens = maxTokens,
            contextWindow = contextWindow,
            backendType = backendType,
            backendTargetModel = "ALL"
        )
        settingsRepository.updateLlamaCppRopeFreqBase(llamaCppRopeFreqBase)
        settingsRepository.updateLlamaCppRopeFreqScale(llamaCppRopeFreqScale)
        settingsRepository.updatePreloadMemoryWarningThresholdPercent(preloadMemoryWarningThresholdPercent)
        settingsRepository.updateSpeculativeDecodingEnabled(speculativeDecodingEnabled)
        PreferencesHelper.setRequireMultimodal(requireContext(), requireMultimodal)
        settingsRepository.updatePreloadMemoryWarningThresholdPercent(preloadMemoryWarningThresholdPercent)
        settingsRepository.updateMemorySaveMode(MemorySaveMode.valueOf(memorySaveMode))
        settingsRepository.updateLlamaCppThreads(llamaCppThreads)
        settingsRepository.updateLlamaCppGpuLayers(llamaCppGpuLayers)
        settingsRepository.updateLlamaCppBatchSize(llamaCppBatchSize)
        settingsRepository.updateLlamaCppUBatchSize(llamaCppUBatchSize)
        settingsRepository.updateLlamaCppKvUnified(llamaCppKvUnified)
        settingsRepository.updateLlamaCppNKeep(llamaCppNKeep)
        settingsRepository.updateLlamaCppRopeFreqBase(llamaCppRopeFreqBase)
        settingsRepository.updateLlamaCppRopeFreqScale(llamaCppRopeFreqScale)
        settingsRepository.updateChatHistoryLimit(chatHistoryLimit)
        PreferencesHelper.setSdSteps(requireContext(), sdSteps)
        PreferencesHelper.setSdCfg(requireContext(), sdCfg)
        PreferencesHelper.setSdScheduler(requireContext(), sdSchedulerId)
        PreferencesHelper.setBraveSearchApiKey(requireContext(), braveSearchApiKeyInput.trim())
        settingsRepository.updateMtpEnabled(mtpEnabled)
        settingsRepository.updateMtpDraftTokens(mtpDraftTokens)
        settingsRepository.updateFlashAttentionEnabled(flashAttentionEnabled)
        settingsRepository.updateDynamicBatchSizeEnabled(dynamicBatchSizeEnabled)
        settingsRepository.updatePromptBatchSize(promptBatchSize)
        settingsRepository.updateGenerationBatchSize(generationBatchSize)
        settingsRepository.updateKvCacheOptimizationEnabled(kvCacheOptimizationEnabled)
        settingsRepository.updateContextShiftEnabled(contextShiftEnabled)
    }

    @Composable
    private fun VersionInfoDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("推論エンジンのバージョン") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LiteRT-LM: ${BuildConfig.LITERTLM_VERSION}")
                    Text("llama.cpp: ${BuildConfig.LLAMACPP_VERSION}")
                    Text(
                        "※実行時には内部 JNI / モデル対応により挙動が変わる場合があります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        )
    }

    @Composable
    private fun AboutDialog(
        onDismiss: () -> Unit,
        onOpenLicenses: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("このアプリについて") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_round),
                        contentDescription = "Nezumi AI アイコン",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Nezumi AI",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorResource(id = R.color.text_primary)
                        )
                        Text(
                            text = "端末上で動くローカルAIチャット",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary),
                            textAlign = TextAlign.Center
                        )
                    }

                    AboutSection(title = "アプリ情報") {
                        AboutInfoRow("バージョン", BuildConfig.VERSION_NAME)
                        AboutInfoRow("ビルド番号", BuildConfig.VERSION_CODE.toString())
                        AboutInfoRow("パッケージ", BuildConfig.APPLICATION_ID)
                        AboutInfoRow("ビルド種別", BuildConfig.BUILD_TYPE)
                    }

                    AboutSection(title = "推論エンジン") {
                        AboutInfoRow("LiteRT-LM", BuildConfig.LITERTLM_VERSION)
                        AboutInfoRow("GGUF / llama.cpp", BuildConfig.LLAMACPP_VERSION)
                        AboutInfoRow("Stable Diffusion", "MNN 自前エンジン")
                        if (com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
                            AboutInfoRow("音声合成", "VOICEVOX CORE 0.16.4")
                        }
                    }

                    AboutSection(title = "主な機能") {
                        AboutBullet("Gemma 系モデルのローカルチャット")
                        AboutBullet("GGUF モデル、画像・音声入力、シンキング表示")
                        AboutBullet("メモリ抽出、会話履歴、各種ツール連携")
                        AboutBullet("Web 検索、アラーム、画像生成などのツール連携")
                    }

                    Text(
                        text = "モデルや外部ライブラリには、それぞれの提供元ライセンスと利用条件が適用されます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary),
                        textAlign = TextAlign.Center
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onOpenLicenses) {
                    Text("ライセンス")
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        )
    }

    @Composable
    private fun AboutSection(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.text_primary)
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
    }

    @Composable
    private fun AboutInfoRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.text_secondary),
                modifier = Modifier.weight(0.42f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.text_primary),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.58f)
            )
        }
    }

    @Composable
    private fun AboutBullet(text: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.primary)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.text_primary),
                modifier = Modifier.weight(1f)
            )
        }
    }

    private fun onBackButtonPressed() {
        // 保存自体は入力の都度自動で行われるため、ここではさらに flush するだけ。
        // 不正入力がある際はエラーダイアログで知らせ、戻らないでフィールドの
        // 修正を促す。
        val error = validateSettings()
        if (error != null) {
            errorDialogMessage = error
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                persistSettings()
            }.onFailure {
                toast("設定の保存に失敗しました: ${it.message}")
            }
            if (isAdded) {
                findNavController().navigateUp()
            }
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

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
    private fun PinSetupDialog(
        hasExistingPin: Boolean,
        onPinSet: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        var pinInput by remember { mutableStateOf("") }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnClickOutside = false)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.9f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (hasExistingPin) "PIN の変更" else "シークレットモード PIN 設定",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "4 桁の数字を入力してください",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(id = R.color.text_secondary)
                    )

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                pinInput = it
                            }
                        },
                        label = { Text("PIN") },
                        placeholder = { Text("****") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                text = "${pinInput.length}/4",
                                color = if (pinInput.length == 4) colorResource(id = R.color.success) else colorResource(id = R.color.text_secondary)
                            )
                        }
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("キャンセル")
                        }

                        Button(
                            onClick = { onPinSet(pinInput) },
                            enabled = pinInput.length == 4,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("次へ")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PinConfirmDialog(
        expectedPin: String,
        onConfirmed: () -> Unit,
        onMismatch: () -> Unit,
        onDismiss: () -> Unit
    ) {
        var confirmInput by remember { mutableStateOf("") }
        var showError by remember { mutableStateOf(false) }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnClickOutside = false)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.9f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "確認用 PIN 入力",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "同じ 4 桁の数字をもう一度入力してください",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(id = R.color.text_secondary)
                    )

                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = {
                            if (it.length <= 4 && it.all { char -> char.isDigit() }) {
                                confirmInput = it
                                showError = false
                            }
                            if (it.length == 4) {
                                if (it == expectedPin) {
                                    onConfirmed()
                                } else {
                                    showError = true
                                }
                            }
                        },
                        label = { Text("確認用 PIN") },
                        placeholder = { Text("****") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        isError = showError,
                        supportingText = {
                            if (showError) {
                                Text(
                                    text = "PIN が一致しません",
                                    color = colorResource(id = R.color.error)
                                )
                            } else {
                                Text(
                                    text = "${confirmInput.length}/4",
                                    color = colorResource(id = R.color.text_secondary)
                                )
                            }
                        }
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("キャンセル")
                        }

                        Button(
                            onClick = onMismatch,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("やり直し")
                        }
                    }
                }
            }
        }
    }
}