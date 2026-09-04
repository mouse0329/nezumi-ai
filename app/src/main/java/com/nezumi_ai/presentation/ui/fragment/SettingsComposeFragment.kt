package com.nezumi_ai.presentation.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import com.nezumi_ai.data.inference.LlamaBridge
import com.nezumi_ai.data.inference.LlamaCppGpuBackend
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.OpenClAvailability
import com.nezumi_ai.data.inference.VulkanAvailability
import com.nezumi_ai.data.memory.MemorySaveMode
import com.nezumi_ai.MyApplication
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.skill.SkillRepository
import com.nezumi_ai.data.skill.SkillScanResult
import com.nezumi_ai.presentation.ui.component.SkillDirectoryDialog
import com.nezumi_ai.presentation.viewmodel.ChatViewModelFactory

import com.nezumi_ai.utils.LogcatRecorder
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
import java.util.Locale
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpFontFamily
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpTypography

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
    private var maxThreads by mutableStateOf(InferenceConfig.getMaxThreadCount())
    private var llamaCppGpuLayers by mutableStateOf(0)
    private var llamaCppGpuBackend by mutableStateOf(LlamaCppGpuBackend.CPU)
    private val openClAvailable: Boolean by lazy { OpenClAvailability.isAvailable() }
    private val vulkanAvailable: Boolean by lazy { VulkanAvailability.isAvailable() }
    private val llamaCppCompiledGpuBackends: Set<String> by lazy { LlamaBridge.compiledGpuBackends() }
    private var llamaCppBatchSize by mutableStateOf(512)
    private var llamaCppUBatchSize by mutableStateOf(512)
    private var llamaCppKvUnified by mutableStateOf(true)
    private var llamaCppNKeep by mutableStateOf(0)
    private var llamaCppRopeFreqBase by mutableStateOf(0.0f)
    private var llamaCppRopeFreqScale by mutableStateOf(1.0f)
    private var ropeFreqBaseInput by mutableStateOf("0.0")
    private var memorySaveMode by mutableStateOf(MemorySaveMode.TOOL_ONLY.name)
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
 // スマホ版設定画面でリスト表示か詳細表示かを切り替えるフラグ。
    //   true = カテゴリリスト表示、false = 選択中セクションの詳細表示。
    //   タブレット（幅 >= 600dp）では常にサイドバー+コンテンツの2ペイン表示なので使用しない。
    private var showSettingsListOnPhone by mutableStateOf(true)
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
    // image-safety-classifier-xs (NSFL/NSFW/SFW) の並列判定結果
    private var nsfwDebugXsNsflProb by mutableStateOf<Float?>(null)
    private var nsfwDebugXsNsfwProb by mutableStateOf<Float?>(null)
    private var nsfwDebugXsSfwProb by mutableStateOf<Float?>(null)
    private lateinit var nsfwDebugPickLauncher: ActivityResultLauncher<String>
    private lateinit var skillImportLauncher: ActivityResultLauncher<Array<String>>
    private var skillScanResult by mutableStateOf(SkillScanResult(emptyList(), emptyList()))
    // エラーダイアログ用。追加/削除/リネームが失敗したときのメッセージを保持する。
    private var skillDialogMessage by mutableStateOf<String?>(null)
    // 成功時に「エラー」タイトルのダイアログが出ていたバグ対策で、
    // 成功トーストを別ステートで扱う。null 以外なら通知として表示する。
    private var skillInfoMessage by mutableStateOf<String?>(null)

    // logcat 常時収集ビューア用の状態。
    //   LogcatRecorder がバックグラウンドでファイルに書き続けているログを
    //   一定間隔で読み込んで表示するだけで、収集自体はこの画面の開閉に依存しない。
    private var logcatViewerText by mutableStateOf("")
    private var logcatViewerAutoRefresh by mutableStateOf(true)
    private var logcatViewerSizeLabel by mutableStateOf("")

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
 // ログタブは常時 index 5。ツール = 6、スキル = 7、ストレージ = 8、デバッグ = 9。
        val maxAllowedSection = if (BuildConfig.DEBUG) 9 else 8
        if (startSection in 0..maxAllowedSection) {
            selectedSection = startSection
 // スマホで引数指定セクションの詳細を直接表示する
            showSettingsListOnPhone = false
        }

        // Fragment.registerForActivityResult() は onCreate までに登録する必要がある。
        nsfwDebugPickLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            runNsfwDebugCheck(uri)
        }
        skillImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch(Dispatchers.IO) { importSkillArchive(uri) }
        }
        skillScanResult = SkillRepository(requireContext().applicationContext).scan(force = true)
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
        nsfwDebugXsNsflProb = null
        nsfwDebugXsNsfwProb = null
        nsfwDebugXsSfwProb = null
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    } ?: error("画像のデコードに失敗しました")

                    val checker = ImageSafetyChecker(requireContext())
                    val probs = checker.check(bmp)
                        ?: error("NSFW チェッカーの推論に失敗しました (open_nsfw.onnx 未展開?)")

                    val classifierXs = com.nezumi_ai.sd.safety.ImageSafetyClassifierXs(requireContext())
                    val xsResult = classifierXs.check(bmp)
                    classifierXs.close()

                    DebugSafetyCheckOutput(
                        bitmap = bmp,
                        safe = probs.getOrNull(0) ?: 0f,
                        nsfw = probs.getOrNull(1) ?: 0f,
                        xsNsfl = xsResult?.nsflScore,
                        xsNsfw = xsResult?.nsfwScore,
                        xsSfw = xsResult?.sfwScore
                    )
                }
            }
            result.onSuccess { output ->
                nsfwDebugBitmap = output.bitmap
                nsfwDebugSafeProb = output.safe
                nsfwDebugNsfwProb = output.nsfw
                nsfwDebugXsNsflProb = output.xsNsfl
                nsfwDebugXsNsfwProb = output.xsNsfw
                nsfwDebugXsSfwProb = output.xsSfw
                nsfwDebugStatus = if (output.xsNsfl == null) {
                    "警告: image-safety-classifier-xs の推論に失敗しました (モデル未配置?)"
                } else {
                    null
                }
            }.onFailure { e ->
                nsfwDebugBitmap = null
                nsfwDebugStatus = "失敗: ${e.message}"
            }
            nsfwDebugRunning = false
        }
    }

    private data class DebugSafetyCheckOutput(
        val bitmap: Bitmap,
        val safe: Float,
        val nsfw: Float,
        val xsNsfl: Float?,
        val xsNsfw: Float?,
        val xsSfw: Float?
    )

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
        // onViewCreated で初回ロード済みのため、onResume での再ロードは不要。
        // 毎回全 state を更新すると全体が再コンポーズされてタブ切り替えが重くなる。
    }

    @Composable
    private fun SettingsScreen() {
        // ChatViewModel は引数なしコンストラクタを持たないので、Factory を渡さないと
        // NoSuchMethodException を含む RuntimeException でクラッシュする（#SettingsComposeFragment L253 の旧バグ）。
        // ChatFragment と同じ Factory を requireActivity() スコープで供給して
        //   1. このスコープ内で初回取得するときも失敗しない
        //   2. すでに ChatFragment 側で作られていれば同一インスタンスを共有する
        // という既存の共有前提を保ちながらクラッシュを回避する。
        val ctx = requireContext().applicationContext
        val database = NezumiAiDatabase.getInstance(ctx)
        val settingsRepo = SettingsRepository.fromDatabase(database)
        val sessionRepo = ChatSessionRepository(database.chatSessionDao(), settingsRepo)
        val messageRepo = MessageRepository(database.messageDao())
        val presetRepo = PresetRepository(database.presetDao(), ctx)
        val memoryRepo = MemoryRepository(database.memoryDao())
        val chatViewModelFactory = ChatViewModelFactory(
            ctx,
            sessionRepo,
            messageRepo,
            settingsRepo,
            presetRepo,
            memoryRepo
        )
        val chatViewModel = ViewModelProvider(requireActivity(), chatViewModelFactory)
            .get(com.nezumi_ai.presentation.viewmodel.ChatViewModel::class.java)
        val sharedModelErrorMessage by chatViewModel.modelErrorDialogMessage.collectAsState()

        // Bug fix (設定のタブ移動が Android の戻る履歴に残らない):
        //   スマホ表示でのカテゴリ一覧 → 詳細ページ遷移は Fragment 内の Compose
        //   state (showSettingsListOnPhone) の切り替えでしかなく、NavController
        //   のバックスタックに乗らない。そのため詳細表示中に端末の戻るボタンを
        //   押すと設定画面ごと閉じてしまっていた。BackHandler で詳細表示中の
        //   戻るを横取りし、まずカテゴリ一覧へ戻す。タブレットは常時2ペインで
        //   一覧に「戻る」概念がないので無効のまま。
        val isTabletForBack = LocalConfiguration.current.screenWidthDp >= 600
        BackHandler(enabled = !isTabletForBack && !showSettingsListOnPhone) {
            showSettingsListOnPhone = true
        }

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
                    append(llamaCppGpuBackend); append('|')
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
                title = stringResource(id = R.string.settings_error_dialog_title),
                message = message,
                onDismiss = { errorDialogMessage = null }
            )
        }

        sharedModelErrorMessage?.let { message ->
            // Parse message to extract title, body, and details
            val lines = message.split("\n\n")
            val title = lines.getOrNull(0) ?: stringResource(id = R.string.settings_error_title)
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

 // レスポンシブ設定画面: タブレット(幅>=600dp)はサイドバー2ペイン、
        //   スマホはカテゴリリスト→詳細ページ遷移。
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600
        // i18n: セクションタイトルも stringResource にしてロケールごとに切り替わるようにする。
        // [全般, 推論, 画像, メモリ, チャット, ログ, ツール] + (DEBUG時のみデバッグ)
        val sectionTitles = listOf(
            stringResource(id = R.string.settings_section_general),
            stringResource(id = R.string.settings_section_inference),
            stringResource(id = R.string.settings_section_image),
            stringResource(id = R.string.settings_section_memory),
            stringResource(id = R.string.settings_section_chat),
            stringResource(id = R.string.settings_section_logs),
            stringResource(id = R.string.tools_settings),
            stringResource(id = R.string.settings_section_skills),
            stringResource(id = R.string.settings_section_storage)
        ) + if (BuildConfig.DEBUG) listOf(stringResource(id = R.string.settings_section_debug)) else emptyList()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
        ) {
            // ステータスバー余白
            Spacer(modifier = Modifier.statusBarsPadding())
            // ヘッダー行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
            ) {
                if (!isTablet && !showSettingsListOnPhone) {
                    // スマホ詳細画面: カテゴリリストに戻る
                    IconButton(onClick = { showSettingsListOnPhone = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = stringResource(id = R.string.back),
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                } else {
                    IconButton(onClick = { onBackButtonPressed() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = stringResource(id = R.string.back),
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                }
                Text(
                    text = if (!isTablet && !showSettingsListOnPhone) {
                        sectionTitles.getOrElse(selectedSection) { stringResource(id = R.string.settings_title) }
                    } else {
                        stringResource(id = R.string.settings_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorResource(id = R.color.text_primary),
                    fontWeight = FontWeight.Bold
                )
            }

 // タブレット: サイドバー + コンテンツ / スマホ: リスト or コンテンツ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isTablet) {
 // タブレット: 縦型サイドバー
                    Column(
                        modifier = Modifier
                            .width(120.dp)
                            .fillMaxHeight()
                            .background(colorResource(id = R.color.primary_light))
                    ) {
                        sectionTitles.forEachIndexed { index, title ->
                            val isSelected = selectedSection == index
                            val isDark = isSystemInDarkTheme()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSection = index }
                                    .background(
                                        if (isSelected) colorResource(id = R.color.primary)
                                        else Color.Transparent
                                    )
                                    .padding(vertical = 14.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(20.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (isSelected) colorResource(id = R.color.nezumi_on_primary)
                                            else Color.Transparent
                                        )
                                )
                                Text(
                                    text = title,
                                    color = if (isSelected) {
                                        colorResource(id = R.color.nezumi_on_primary)
                                    } else {
                                        if (isDark) Color.White.copy(alpha = 0.7f) else colorResource(id = R.color.text_secondary)
                                    },
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                } else if (showSettingsListOnPhone) {
 // スマホ: カテゴリリスト（タップで詳細ページへ遷移）
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sectionTitles.size) { index ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedSection = index
                                        showSettingsListOnPhone = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = colorResource(id = R.color.surface_card)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = sectionTitles[index],
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colorResource(id = R.color.text_primary),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_chevron_right_24),
                                        contentDescription = null,
                                        tint = colorResource(id = R.color.text_secondary)
                                    )
                                }
                            }
                        }
                    }
                }

 // コンテンツエリア（タブレットは常時、スマホは詳細表示時のみ）
                if (isTablet || !showSettingsListOnPhone) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    item(key = selectedSection) {
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
                            var isShowContextMeter by remember { mutableStateOf(PreferencesHelper.isShowContextMeter(context)) }
                            var isShowTps by remember { mutableStateOf(PreferencesHelper.isShowTps(context)) }
                            var isShowTtft by remember { mutableStateOf(PreferencesHelper.isShowTtft(context)) }
                            var isDisableScreenshot by remember { mutableStateOf(PreferencesHelper.isDisableScreenshot(context)) }
                            var pendingAlwaysLockEnable by remember { mutableStateOf(false) }
                            // i18n: アプリ UI の言語 (SYSTEM / JA / EN) を全般タブから切り替える。
                            var appLanguage by remember { mutableStateOf(PreferencesHelper.getLanguage(context)) }

                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = stringResource(id = R.string.settings_general_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                                // テーマ設定セクション
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val themeCurrentLabel = stringResource(
                                        id = R.string.settings_theme_current_format,
                                        when (themeMode) {
                                            PreferencesHelper.THEME_LIGHT -> stringResource(id = R.string.settings_theme_light_display)
                                            PreferencesHelper.THEME_DARK -> stringResource(id = R.string.settings_theme_dark_display)
                                            else -> stringResource(id = R.string.settings_theme_system_display)
                                        }
                                    )
                                    Text(
                                        text = themeCurrentLabel,
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
                                            label = { Text(stringResource(id = R.string.settings_theme_system)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = themeMode == PreferencesHelper.THEME_LIGHT,
                                            onClick = {
                                                themeMode = PreferencesHelper.THEME_LIGHT
                                                PreferencesHelper.setThemeMode(context, PreferencesHelper.THEME_LIGHT)
                                                PreferencesHelper.applyThemeMode(context)
                                            },
                                            label = { Text(stringResource(id = R.string.settings_theme_light)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = themeMode == PreferencesHelper.THEME_DARK,
                                            onClick = {
                                                themeMode = PreferencesHelper.THEME_DARK
                                                PreferencesHelper.setThemeMode(context, PreferencesHelper.THEME_DARK)
                                                PreferencesHelper.applyThemeMode(context)
                                            },
                                            label = { Text(stringResource(id = R.string.settings_theme_dark)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

                                // i18n: 言語切替 (全般タブ内)。値は PreferencesHelper に保存し、
                                //   実際のリソース選択は attachBaseContext で LocaleHelper.wrap() することで
                                //   行う。切り替え直後に activity.recreate() して UI を再構築する。
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val languageLabel = stringResource(
                                        id = R.string.settings_language_current_format,
                                        when (appLanguage) {
                                            PreferencesHelper.LANG_JA -> stringResource(id = R.string.settings_language_japanese)
                                            PreferencesHelper.LANG_EN -> stringResource(id = R.string.settings_language_english)
                                            else -> stringResource(id = R.string.settings_language_system)
                                        }
                                    )
                                    Text(
                                        text = languageLabel,
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        FilterChip(
                                            selected = appLanguage == PreferencesHelper.LANG_SYSTEM,
                                            onClick = {
                                                if (appLanguage != PreferencesHelper.LANG_SYSTEM) {
                                                    appLanguage = PreferencesHelper.LANG_SYSTEM
                                                    PreferencesHelper.setLanguage(context, PreferencesHelper.LANG_SYSTEM)
                                                    activity?.recreate()
                                                }
                                            },
                                            label = { Text(stringResource(id = R.string.settings_language_system)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = appLanguage == PreferencesHelper.LANG_JA,
                                            onClick = {
                                                if (appLanguage != PreferencesHelper.LANG_JA) {
                                                    appLanguage = PreferencesHelper.LANG_JA
                                                    PreferencesHelper.setLanguage(context, PreferencesHelper.LANG_JA)
                                                    activity?.recreate()
                                                }
                                            },
                                            label = { Text(stringResource(id = R.string.settings_language_japanese)) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = appLanguage == PreferencesHelper.LANG_EN,
                                            onClick = {
                                                if (appLanguage != PreferencesHelper.LANG_EN) {
                                                    appLanguage = PreferencesHelper.LANG_EN
                                                    PreferencesHelper.setLanguage(context, PreferencesHelper.LANG_EN)
                                                    activity?.recreate()
                                                }
                                            },
                                            label = { Text(stringResource(id = R.string.settings_language_english)) },
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
                                            text = stringResource(id = R.string.settings_always_lock_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_always_lock_desc),
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
                                        },
                                        colors = nezumiSwitchColors()
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
                                            text = stringResource(id = R.string.settings_stop_kb_learning_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_stop_kb_learning_desc),
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isStopKeyboardLearning,
                                        onCheckedChange = { checked ->
                                            isStopKeyboardLearning = checked
                                            PreferencesHelper.setStopKeyboardLearningEnabled(context, checked)
                                        },
                                        colors = nezumiSwitchColors()
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

 // 新: コンテキストメーターの表示 (既定: 表示しない)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(id = R.string.settings_show_context_meter_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_show_context_meter_desc),
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isShowContextMeter,
                                        onCheckedChange = { checked ->
                                            isShowContextMeter = checked
                                            PreferencesHelper.setShowContextMeter(context, checked)
                                        },
                                        colors = nezumiSwitchColors()
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

 // 新: t/s (トークン/秒) の表示 (既定: 表示しない)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(id = R.string.settings_show_tps_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_show_tps_desc),
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isShowTps,
                                        onCheckedChange = { checked ->
                                            isShowTps = checked
                                            PreferencesHelper.setShowTps(context, checked)
                                        },
                                        colors = nezumiSwitchColors()
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

 // 新: TTFT (最初のトークンまでの時間) の表示 (既定: 表示しない)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(id = R.string.settings_show_ttft_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_show_ttft_desc),
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isShowTtft,
                                        onCheckedChange = { checked ->
                                            isShowTtft = checked
                                            PreferencesHelper.setShowTtft(context, checked)
                                        },
                                        colors = nezumiSwitchColors()
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

 // 新: スクリーンショット無効化 (既定: 無効)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(id = R.string.settings_disable_screenshot_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_disable_screenshot_desc),
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isDisableScreenshot,
                                        onCheckedChange = { checked ->
                                            isDisableScreenshot = checked
                                            PreferencesHelper.setDisableScreenshot(context, checked)
                                            val activity = context as? android.app.Activity
                                            if (checked) {
                                                activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                            } else {
                                                // 常時ロックもシークレットモードも有効でない場合のみ解除
                                                val mainActivity = activity as? com.nezumi_ai.MainActivity
                                                val incognitoActive = mainActivity?.isInIncognitoMode() ?: false
                                                if (!PreferencesHelper.isAlwaysLockEnabled(context) && !incognitoActive) {
                                                    activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                                }
                                            }
                                        },
                                        colors = nezumiSwitchColors()
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

                                // シークレットモード設定
                                Text(
                                    text = stringResource(id = R.string.settings_secret_mode_title),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isSecretModeEnabled) stringResource(id = R.string.settings_secret_mode_enabled) else stringResource(id = R.string.settings_secret_mode_disabled),
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
                                        Text(text = if (hasSecretModePin) stringResource(id = R.string.settings_secret_pin_change) else stringResource(id = R.string.settings_secret_pin_set))
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
                                            Text(text = stringResource(id = R.string.settings_secret_pin_reset))
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
                    // ログタブ（常時・リリースビルドでも表示）: ツール呼出履歴 / logcat をサブタブで表示
                    5 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LogsSettingsCard()
                    }
                    // ツールタブ（常時 index 6）: ページ取得のJS実行モード + MCPサーバー管理
                    6 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToolsSettingsCard()
                    }
                    7 -> SkillManagementCard(skillScanResult, onImport = { skillImportLauncher.launch(arrayOf("application/zip")) })
                    8 -> StorageManagementSection(onOpenSession = { sessionId ->
                        findNavController().navigate(R.id.chatFragment, Bundle().apply { putLong("sessionId", sessionId) })
                    })
                    // デバッグタブは BuildConfig.DEBUG 時のみ index 9
                    9 -> if (BuildConfig.DEBUG) {
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
                        Text(text = stringResource(id = R.string.settings_about_dialog_title))
                    }
                    TextButton(onClick = {
                        PreferencesHelper.resetInitialSetupCompleted(requireContext())
                        findNavController().navigate(R.id.setupWizardFragment)
                    }) {
                        Text(text = stringResource(id = R.string.settings_inference_setup_open))
                    }
                    TextButton(onClick = { findNavController().navigate(R.id.action_settingsFragment_to_helpFragment) }) {
                        Text(text = stringResource(id = R.string.open_help_page))
                    }
                    TextButton(onClick = { findNavController().navigate(R.id.action_settingsFragment_to_licenseFragment) }) {
                        Text(text = stringResource(id = R.string.open_license_page))
                    }
                }
            }
                    } // LazyColumn close
                } // if (content) close
            } // Row close
        } // Column close
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
                Text(text = stringResource(id = R.string.settings_brave_card_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                Text(
                    text = stringResource(id = R.string.settings_brave_card_desc),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = braveSearchApiKeyInput,
                    onValueChange = { braveSearchApiKeyInput = it },
                    label = { Text(stringResource(id = R.string.settings_brave_api_label)) },
                    placeholder = { Text(stringResource(id = R.string.settings_brave_api_ph)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Text(
                    text = if (braveSearchApiKeyInput.isBlank()) stringResource(id = R.string.settings_brave_unset_hint) else stringResource(id = R.string.settings_brave_set_hint),
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
                Text(text = stringResource(id = R.string.settings_backend_card_title), fontWeight = FontWeight.Bold)
                Text(
                    text = stringResource(id = R.string.settings_backend_current_format, backendType),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { versionDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.settings_inference_check_engine_version))
                }
            }
        }
    }

    @Composable
    private fun InferenceParamsCard() {
 // ユーザー要望: コンテキストウィンドウの上限を 128k まで拡張
        val maxContextWindow = if (selectedModel.equals("Gemma4-2B", ignoreCase = true) ||
                                    selectedModel.equals("Gemma4-4B", ignoreCase = true)) {
            131072
        } else {
            131072
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(id = R.string.settings_inference_params_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                // コンテキストサイズと最大トークン数を2列グリッド
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = contextWindowInput,
                        onValueChange = { contextWindowInput = it },
                        label = { Text(stringResource(id = R.string.settings_inference_context_size)) },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = maxTokensInput,
                        onValueChange = { maxTokensInput = it },
                        label = { Text(stringResource(id = R.string.settings_inference_max_tokens)) },
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
                            text = stringResource(id = R.string.settings_inference_temperature_label),
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
                            text = stringResource(id = R.string.settings_inference_topk_label),
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
                            text = stringResource(id = R.string.settings_inference_topp_label),
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
                                text = stringResource(id = R.string.settings_inference_auto_compress_title),
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = contextCompressionEnabled,
                                onCheckedChange = { contextCompressionEnabled = it },
                                colors = nezumiSwitchColors()
                            )
                        }
                        Text(
                            text = stringResource(id = R.string.settings_inference_auto_compress_desc),
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
                                    text = stringResource(id = R.string.settings_inference_compression_threshold_title),
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
                                text = stringResource(id = R.string.settings_inference_compression_threshold_desc),
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
                            text = stringResource(id = R.string.settings_inference_preload_warning_title),
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
                        text = stringResource(id = R.string.settings_inference_preload_warning_desc),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // GGUF / llama.cpp 固有設定は別のカードに移動しました
            }
        }
    }

    @Composable
    private fun SkillManagementCard(result: SkillScanResult, onImport: () -> Unit) {
        var creatingSkill by remember { mutableStateOf(false) }
        var browsingSkill by remember { mutableStateOf<com.nezumi_ai.data.skill.Skill?>(null) }
        var deletingSkill by remember { mutableStateOf<com.nezumi_ai.data.skill.Skill?>(null) }
        var renamingSkill by remember { mutableStateOf<com.nezumi_ai.data.skill.Skill?>(null) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.skills_settings_title), fontWeight = FontWeight.Bold)
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
    private fun SkillCreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
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
    private fun SkillRenameDialog(
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
    private fun truncateSkillName(source: String): String {
        val limit = androidx.compose.ui.res.integerResource(id = R.integer.preset_skill_description_max_chars)
        return if (source.length <= limit) source else source.take(limit) + "…"
    }

    private fun importSkillArchive(uri: Uri) {
        val context = requireContext().applicationContext
        val temporaryRoot = File(context.cacheDir, "skill-import-${System.currentTimeMillis()}").apply { mkdirs() }
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val output = com.nezumi_ai.data.skill.SkillPathResolver.resolveChild(temporaryRoot, entry.name)
                                ?: error("invalid_archive_path")
                            output.parentFile?.mkdirs()
                            output.outputStream().use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: error("archive_open_failed")
            val directories = temporaryRoot.listFiles().orEmpty().filter { it.isDirectory }
            require(directories.size == 1) { "archive_must_contain_one_skill_directory" }
            val skillDirectory = directories.single()
            require(com.nezumi_ai.data.skill.SkillPathResolver.isValidName(skillDirectory.name)) { "invalid_skill_name" }
            require(File(skillDirectory, "SKILL.md").isFile) { "skill_md_missing" }
            val destination = File(context.filesDir, "skills/${skillDirectory.name}")
            require(!destination.exists()) { "skill_already_exists" }
            destination.parentFile?.mkdirs()
            require(skillDirectory.renameTo(destination)) { "skill_install_failed" }
        }
        temporaryRoot.deleteRecursively()
        skillScanResult = SkillRepository(context).scan(force = true)
        val error = copied.exceptionOrNull()
        // 成功時に「エラー」タイトルのダイアログが出ていた不具合を修正:
        // 成功メッセージは skillInfoMessage 経由で情報通知として表示する。
        if (error == null) {
            skillInfoMessage = context.getString(R.string.skills_import_success)
        } else {
            skillDialogMessage = context.getString(R.string.skills_import_failed, error.message ?: "unknown")
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
                Text(text = stringResource(id = R.string.settings_gguf_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                Text(
                    text = stringResource(id = R.string.settings_gguf_desc),
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
                            text = stringResource(id = R.string.settings_basic_settings),
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
                            val openClSelectable = llamaCppCompiledGpuBackends.contains(LlamaCppGpuBackend.OPENCL) && openClAvailable
                            val vulkanSelectable = llamaCppCompiledGpuBackends.contains(LlamaCppGpuBackend.VULKAN) && vulkanAvailable
                            val gpuOffloadEnabled = when (llamaCppGpuBackend) {
                                LlamaCppGpuBackend.OPENCL -> openClSelectable
                                LlamaCppGpuBackend.VULKAN -> vulkanSelectable
                                else -> false
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(id = R.string.settings_llamacpp_gpu_backend),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.settings_llamacpp_gpu_backend_desc),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    FilterChip(
                                        selected = llamaCppGpuBackend == LlamaCppGpuBackend.CPU,
                                        onClick = { llamaCppGpuBackend = LlamaCppGpuBackend.CPU },
                                        label = { Text(stringResource(id = R.string.settings_backend_cpu)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = llamaCppGpuBackend == LlamaCppGpuBackend.OPENCL,
                                        onClick = {
                                            llamaCppGpuBackend = LlamaCppGpuBackend.OPENCL
                                            if (llamaCppGpuLayers == 0) llamaCppGpuLayers = 99
                                        },
                                        enabled = openClSelectable,
                                        label = { Text(stringResource(id = R.string.settings_llamacpp_backend_opencl)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = llamaCppGpuBackend == LlamaCppGpuBackend.VULKAN,
                                        onClick = {
                                            llamaCppGpuBackend = LlamaCppGpuBackend.VULKAN
                                            if (llamaCppGpuLayers == 0) llamaCppGpuLayers = 99
                                        },
                                        enabled = vulkanSelectable,
                                        label = { Text(stringResource(id = R.string.settings_llamacpp_backend_vulkan)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (!openClSelectable || !vulkanSelectable) {
                                    val unavailable = buildString {
                                        if (!openClSelectable) append("OpenCL")
                                        if (!openClSelectable && !vulkanSelectable) append(" / ")
                                        if (!vulkanSelectable) append("Vulkan")
                                    }
                                    Text(
                                        text = stringResource(id = R.string.settings_llamacpp_gpu_backend_unavailable, unavailable),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.settings_cpu_threads),
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
                                        text = stringResource(id = R.string.settings_gpu_layers),
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
                                    onValueChange = {
                                        if (gpuOffloadEnabled) {
                                            llamaCppGpuLayers = it.roundToInt()
                                        }
                                    },
                                    valueRange = 0f..128f,
                                    steps = 127,
                                    enabled = gpuOffloadEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (!gpuOffloadEnabled) {
                                    Text(
                                        text = stringResource(id = R.string.settings_gpu_layers_backend_required),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.settings_batch_size),
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
                                        text = stringResource(id = R.string.settings_internal_batch_size),
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
                                        text = stringResource(id = R.string.settings_kv_unified),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(id = R.string.settings_kv_unified_desc),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = llamaCppKvUnified,
                                    onCheckedChange = { llamaCppKvUnified = it },
                                    colors = nezumiSwitchColors()
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(id = R.string.settings_rope_base),
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
                                    text = stringResource(id = R.string.settings_rope_base_hint),
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
                                        text = stringResource(id = R.string.settings_rope_scale),
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
                                    text = stringResource(id = R.string.settings_rope_scale_hint),
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
                            text = stringResource(id = R.string.settings_performance_optimization),
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
                                        text = stringResource(id = R.string.settings_mtp_title),
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(id = R.string.settings_mtp_desc),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = mtpEnabled,
                                    onCheckedChange = { mtpEnabled = it },
                                    colors = nezumiSwitchColors()
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
                                            text = stringResource(id = R.string.settings_mtp_draft_tokens),
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
                                        text = stringResource(id = R.string.settings_mtp_recommended),
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
                                        text = stringResource(id = R.string.settings_flash_attention),
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(id = R.string.settings_flash_attention_desc),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = flashAttentionEnabled,
                                    onCheckedChange = { flashAttentionEnabled = it },
                                    colors = nezumiSwitchColors()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(id = R.string.settings_dynamic_batch),
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(id = R.string.settings_dynamic_batch_desc),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = dynamicBatchSizeEnabled,
                                    onCheckedChange = { dynamicBatchSizeEnabled = it },
                                    colors = nezumiSwitchColors()
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
                                            text = stringResource(id = R.string.settings_prompt_batch),
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
                                            text = stringResource(id = R.string.settings_inference_generation_batch_title),
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
                                        text = stringResource(id = R.string.settings_inference_generation_batch_desc),
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
                                        text = stringResource(id = R.string.settings_inference_kv_cache_title),
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(id = R.string.settings_inference_kv_cache_desc),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = kvCacheOptimizationEnabled,
                                    onCheckedChange = { kvCacheOptimizationEnabled = it },
                                    colors = nezumiSwitchColors()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(id = R.string.settings_inference_context_shift_title),
                                        color = colorResource(id = R.color.text_primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(id = R.string.settings_inference_context_shift_desc),
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = contextShiftEnabled,
                                    onCheckedChange = { contextShiftEnabled = it },
                                    colors = nezumiSwitchColors()
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
                Text(text = stringResource(id = R.string.settings_inference_literlm_settings_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(id = R.string.settings_inference_backend_title),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(id = R.string.settings_inference_backend_desc),
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
                            label = { Text(stringResource(id = R.string.settings_backend_cpu)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = backendType == "GPU",
                            onClick = { backendType = "GPU" },
                            label = { Text(stringResource(id = R.string.settings_backend_gpu)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = backendType == "NPU",
                            onClick = { backendType = "NPU" },
                            label = { Text(stringResource(id = R.string.settings_backend_npu)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                TextButton(
                    onClick = { versionDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.settings_inference_check_engine_version))
                }
                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.settings_inference_speculative_decoding_title),
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = R.string.settings_inference_speculative_decoding_desc),
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = speculativeDecodingEnabled,
                        onCheckedChange = { speculativeDecodingEnabled = it },
                        colors = nezumiSwitchColors()
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.settings_require_multimodal),
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = R.string.settings_require_multimodal_desc),
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = requireMultimodal,
                        onCheckedChange = { requireMultimodal = it },
                        colors = nezumiSwitchColors()
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
                Text(text = stringResource(id = R.string.settings_image_generation_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                Text(
                    text = stringResource(id = R.string.settings_image_generation_desc),
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
                            text = stringResource(id = R.string.settings_steps_title),
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
                            text = stringResource(id = R.string.settings_cfg_scale_title),
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
                        text = stringResource(id = R.string.settings_scheduler_title),
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
                        text = stringResource(id = R.string.settings_seed_title),
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
                        placeholder = { Text(stringResource(id = R.string.settings_seed_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(id = R.string.settings_seed_hint),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    @Composable
    private fun MemoryManagementCard() {
        val localContext = LocalContext.current
        val memories by memoryRepository.observeMemories().collectAsState(initial = emptyList())
        var showMemoryListModal by remember { mutableStateOf(false) }
        var confirmDeleteAll by remember { mutableStateOf(false) }

        if (confirmDeleteAll) {
            AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                title = { Text(stringResource(id = R.string.settings_memory_delete_all_title)) },
                text = { Text(stringResource(id = R.string.settings_memory_delete_all_body)) },
                confirmButton = {
                    Button(onClick = {
                        viewLifecycleOwner.lifecycleScope.launch {
                            memoryRepository.softDeleteAll()
                            confirmDeleteAll = false
                            toast(localContext.getString(R.string.settings_memory_deleted_toast))
                        }
                    }) {
                        Text(stringResource(id = R.string.common_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteAll = false }) {
                        Text(stringResource(id = R.string.common_cancel))
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
                        Text(text = stringResource(id = R.string.settings_memory_management_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                        Text(
                            text = stringResource(id = R.string.settings_memory_count_format, memories.size),
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row {
                        TextButton(
                            enabled = memories.isNotEmpty(),
                            onClick = { showMemoryListModal = true }
                        ) {
                            Text(stringResource(id = R.string.settings_memory_list_show))
                        }
                        TextButton(
                            enabled = memories.isNotEmpty(),
                            onClick = { confirmDeleteAll = true }
                        ) {
                            Text(stringResource(id = R.string.settings_memory_delete_all_button2))
                        }
                    }
                }

                Text(
                    text = stringResource(id = R.string.settings_memory_save_mode_title),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // v2.1+ デフォルト方式: LLM が明示的に save_memory ツールを呼んだときのみ保存する。
                    FilterChip(
                        selected = memorySaveMode == MemorySaveMode.TOOL_ONLY.name,
                        onClick = { memorySaveMode = MemorySaveMode.TOOL_ONLY.name },
                        label = { Text(stringResource(id = R.string.settings_memory_mode_tool_only_label)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = memorySaveMode == MemorySaveMode.LLM.name,
                        onClick = { memorySaveMode = MemorySaveMode.LLM.name },
                        label = { Text(stringResource(id = R.string.settings_memory_mode_llm_label)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = memorySaveMode == MemorySaveMode.RULE_BASED.name,
                        onClick = { memorySaveMode = MemorySaveMode.RULE_BASED.name },
                        label = { Text(stringResource(id = R.string.settings_memory_mode_rule_label)) },
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
            title = { Text(stringResource(id = R.string.settings_memory_list_title)) },
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
                                    text = stringResource(id = R.string.settings_memory_importance_format, String.format("%.2f", memory.importance), memory.accessCount),
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = { onDeleteMemory(memory.id) }) {
                                Text(stringResource(id = R.string.common_delete))
                            }
                        }
                        HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.14f), thickness = 1.dp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.common_close))
                }
            }
        )
    }


    /**
     * 設定 > ツール タブ
     * ページ取得のJS実行モードON/OFFと、MCPサーバーの登録・編集・削除を扱う。
     * 各ツール（アラーム・タイマー・画像生成等）自体の有効化は
     * プリセット編集画面（PresetSettingsFragment）側で行う。
     */
    @Composable
    private fun ToolsSettingsCard() {
        val localContext = LocalContext.current
        val toolPreferences = remember { com.nezumi_ai.data.inference.ToolPreferences(localContext) }
        var webFetchJsRenderEnabled by remember {
            mutableStateOf(toolPreferences.isWebFetchJsRenderEnabled())
        }
        val mcpPrefs = remember { com.nezumi_ai.data.mcp.McpPreferences.get(localContext) }
        val mcpServers by mcpPrefs.servers.collectAsState()
        var showMcpManager by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(id = R.string.preset_tool_name_web_fetch),
                    fontWeight = FontWeight.Bold,
                    fontSize = MaterialTheme.typography.titleMedium.fontSize
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.tools_web_fetch_js_render),
                            color = colorResource(id = R.color.text_primary)
                        )
                        Text(
                            text = stringResource(id = R.string.tools_web_fetch_js_render_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                    Switch(
                        checked = webFetchJsRenderEnabled,
                        onCheckedChange = { checked ->
                            toolPreferences.setWebFetchJsRenderEnabled(checked)
                            webFetchJsRenderEnabled = checked
                        },
                        colors = nezumiSwitchColors()
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.preset_edit_mcp_server_label),
                            fontWeight = FontWeight.Bold,
                            fontSize = MaterialTheme.typography.titleMedium.fontSize
                        )
                        val subLabel = if (mcpServers.isEmpty()) {
                            stringResource(id = R.string.preset_edit_mcp_servers_unregistered)
                        } else {
                            stringResource(id = R.string.mcp_server_manager_count_format, mcpServers.size)
                        }
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                    TextButton(onClick = { showMcpManager = true }) {
                        Text(stringResource(id = R.string.preset_edit_mcp_add))
                    }
                }
                if (mcpServers.isNotEmpty()) {
                    mcpServers.forEach { server ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(server.name, color = colorResource(id = R.color.text_primary))
                            Text(
                                text = "${server.transport.label} • ${server.url}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorResource(id = R.color.text_secondary)
                            )
                        }
                    }
                }
            }
        }

        if (showMcpManager) {
            // このダイアログは本来プリセットへの有効化選択も兼ねるが、ここでは
            // サーバーの登録・編集・削除のみを目的として開くため、選択状態は
            // 空集合のまま扱い、プリセット側の紐付けには影響させない。
            com.nezumi_ai.presentation.ui.component.McpServerManagerDialog(
                servers = mcpServers,
                selectedIds = emptySet(),
                onSelectionChange = {},
                onUpsert = { mcpPrefs.upsert(it) },
                onDelete = { mcpPrefs.remove(it) },
                onDismiss = { showMcpManager = false }
            )
        }
    }

    /**
     * 設定 > ログ タブ
     * ツールコール呼出履歴と logcat をサブタブで分けて表示する。
     * リリースビルドでも利用可能。
     */
    @Composable
    private fun LogsSettingsCard() {
        var selectedLogSubTab by remember { mutableIntStateOf(0) }
        val logSubTabs = listOf(
            stringResource(id = R.string.logs_tab_tool_history),
            stringResource(id = R.string.logs_tab_logcat)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ScrollableTabRow(
                    selectedTabIndex = selectedLogSubTab,
                    edgePadding = 0.dp,
                    containerColor = colorResource(id = R.color.primary_light)
                ) {
                    logSubTabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedLogSubTab == index,
                            onClick = { selectedLogSubTab = index },
                            text = { Text(title) }
                        )
                    }
                }

                when (selectedLogSubTab) {
                    0 -> ToolHistorySection()
                    else -> LogcatViewerSection()
                }
            }
        }
    }

    /**
     * ツールコール呼出履歴（時刻・セッション・ツール・クエリ）を表示する。
     */
    @Composable
    private fun ToolHistorySection() {
        val localContext = LocalContext.current
        val db = remember { NezumiAiDatabase.getInstance(localContext) }
        val toolHistoryRepo = remember {
            com.nezumi_ai.data.repository.ToolCallHistoryRepository(
                db.toolCallHistoryDao(),
                db.chatSessionDao()
            )
        }
        val history by toolHistoryRepo.observeRecent(300).collectAsState(initial = emptyList())
        var query by remember { mutableStateOf("") }
        var filtered by remember { mutableStateOf<List<com.nezumi_ai.data.database.entity.ToolCallHistoryEntity>?>(null) }
        val scope = rememberCoroutineScope()
        val timeFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()) }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(id = R.string.logs_tool_history_query)) }
                )
                Button(onClick = {
                    val q = query.trim().lowercase()
                    filtered = if (q.isEmpty()) null else history.filter {
                        it.toolName.lowercase().contains(q) ||
                            (it.query?.lowercase()?.contains(q) == true) ||
                            (it.sessionName?.lowercase()?.contains(q) == true)
                    }
                }) { Text(stringResource(id = android.R.string.search_go)) }
                Button(onClick = {
                    scope.launch {
                        toolHistoryRepo.clearAll()
                        filtered = null
                        query = ""
                    }
                }) { Text(stringResource(id = R.string.logs_clear_tool_history)) }
            }
            val display = filtered ?: history
            if (display.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.logs_tool_history_empty),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    display.take(100).forEach { row ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = timeFmt.format(java.util.Date(row.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorResource(id = R.color.text_secondary)
                                )
                                Text(
                                    text = "${stringResource(id = R.string.logs_tool_history_tool)}: ${row.toolName}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${stringResource(id = R.string.logs_tool_history_session)}: " +
                                        (row.sessionName?.takeIf { it.isNotBlank() } ?: "#${row.sessionId}"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!row.query.isNullOrBlank()) {
                                    Text(
                                        text = "${stringResource(id = R.string.logs_tool_history_query)}: ${row.query}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    text = if (row.success) "OK" else "FAIL",
                                    color = if (row.success) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
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
                    Text(text = stringResource(id = R.string.settings_debug_section_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                    SvgSpinner(modifier = Modifier.size(32.dp))
                }
                Text(
                    text = stringResource(id = R.string.settings_debug_similarity_title),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(id = R.string.settings_debug_similarity_desc),
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
                        Text(text = stringResource(id = R.string.settings_debug_memory_status), fontWeight = FontWeight.SemiBold)
                        Text(
                            text = stringResource(id = R.string.settings_debug_memory_usage, systemMemoryInfo.usedPercent, systemMemoryInfo.availablePercent),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(id = R.string.settings_debug_memory_summary, systemMemoryInfo.totalMemoryMB, systemMemoryInfo.usedMemoryMB, systemMemoryInfo.availableMemoryMB),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                        Text(
                            text = if (systemMemoryInfo.lowMemoryFlag) stringResource(id = R.string.settings_debug_memory_low) else stringResource(id = R.string.settings_debug_memory_stable),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (systemMemoryInfo.lowMemoryFlag) MaterialTheme.colorScheme.error else colorResource(id = R.color.text_secondary)
                        )
                        Text(
    text = stringResource(id = R.string.settings_debug_memory_source_format, systemMemoryInfo.source),
    style = MaterialTheme.typography.bodySmall,
    color = colorResource(id = R.color.text_secondary)
)
                    }
                }

                OutlinedTextField(
                    value = debugTextAInput,
                    onValueChange = { debugTextAInput = it },
                    label = { Text(stringResource(id = R.string.settings_debug_text_a)) },
                    placeholder = { Text(stringResource(id = R.string.settings_debug_text_a_ph)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                    maxLines = 4
                )
                OutlinedTextField(
                    value = debugTextBInput,
                    onValueChange = { debugTextBInput = it },
                    label = { Text(stringResource(id = R.string.settings_debug_text_b)) },
                    placeholder = { Text(stringResource(id = R.string.settings_debug_text_b_ph)) },
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
                            errorDialogMessage = localContext.getString(R.string.settings_debug_text_a_required)
                            return@Button
                        }
                        if (debugTextBInput.isBlank()) {
                            errorDialogMessage = localContext.getString(R.string.settings_debug_text_b_required)
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
                                    errorDialogMessage = localContext.getString(R.string.settings_debug_embedding_failed)
                                    return@withContext
                                }
                                if (embeddingA.size != embeddingB.size) {
                                    errorDialogMessage = localContext.getString(R.string.settings_debug_embedding_dimension_mismatch)
                                    return@withContext
                                }
                                if (normA == 0f || normB == 0f) {
                                    errorDialogMessage = localContext.getString(R.string.settings_debug_embedding_zero_vector)
                                    return@withContext
                                }
                                debugTextSimilarityResult = localContext.getString(R.string.settings_debug_similarity_result_format, similarity ?: 0.0)
                            }
                        }
                    }) {
                        Text(stringResource(id = R.string.settings_debug_compute_button))
                    }
                    Button(onClick = {
                        debugTextAInput = ""
                        debugTextBInput = ""
                        debugTextSimilarityResult = null
                        errorDialogMessage = null
                    }) {
                        Text(stringResource(id = R.string.common_clear))
                    }
                }

                debugTextSimilarityResult?.let {
                    Text(text = it, color = colorResource(id = R.color.primary))
                }

                // ---- NSFW チェッカー (open_nsfw.onnx / Yahoo Open NSFW) ----
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(id = R.string.settings_debug_nsfw_title),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(id = R.string.settings_debug_nsfw_desc),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { nsfwDebugPickLauncher.launch("image/*") },
                        enabled = !nsfwDebugRunning
                    ) {
                        Text(if (nsfwDebugRunning) stringResource(id = R.string.settings_debug_nsfw_running) else stringResource(id = R.string.settings_debug_nsfw_pick))
                    }
                    Button(onClick = {
                        nsfwDebugBitmap = null
                        nsfwDebugStatus = null
                        nsfwDebugSafeProb = null
                        nsfwDebugNsfwProb = null
                        nsfwDebugXsNsflProb = null
                        nsfwDebugXsNsfwProb = null
                        nsfwDebugXsSfwProb = null
                    }, enabled = !nsfwDebugRunning) {
                        Text(stringResource(id = R.string.common_clear))
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
                            contentDescription = stringResource(id = R.string.settings_debug_nsfw_image_desc),
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val safe = nsfwDebugSafeProb
                            val nsfw = nsfwDebugNsfwProb
                            val xsNsfl = nsfwDebugXsNsflProb
                            val xsNsfw = nsfwDebugXsNsfwProb
                            val xsSfw = nsfwDebugXsSfwProb

                            if (safe != null && nsfw != null) {
                                // SafetyPolicy 経由で統合判定(Open NSFW + xs のOR結合)を算出。
                                // ハードコードした独自閾値ではなく、実運用と同じロジックを使う。
                                val nsfwResult = com.nezumi_ai.sd.safety.SafetyPolicy.fromRawOutput(
                                    floatArrayOf(safe, nsfw)
                                )
                                val nsfwVerdict = nsfwResult.verdict
                                val xsVerdict = if (xsNsfl != null && xsNsfw != null && xsSfw != null) {
                                    com.nezumi_ai.sd.safety.SafetyPolicy.evaluateClassifierXs(
                                        com.nezumi_ai.sd.safety.ImageSafetyClassifierResult(xsNsfl, xsNsfw, xsSfw)
                                    )
                                } else null
                                val finalVerdict = if (xsVerdict != null) {
                                    com.nezumi_ai.sd.safety.SafetyPolicy.combine(nsfwVerdict, xsVerdict)
                                } else nsfwVerdict

                                val verdictLabel = when (finalVerdict) {
                                    com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLOCK -> stringResource(id = R.string.settings_debug_nsfw_verdict_block)
                                    com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLUR -> "BLUR"
                                    com.nezumi_ai.sd.safety.SafetyResult.Verdict.ALLOW -> stringResource(id = R.string.settings_debug_nsfw_verdict_allow)
                                }
                                val verdictColor = when (finalVerdict) {
                                    com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLOCK -> MaterialTheme.colorScheme.error
                                    com.nezumi_ai.sd.safety.SafetyResult.Verdict.BLUR -> colorResource(id = R.color.text_secondary)
                                    com.nezumi_ai.sd.safety.SafetyResult.Verdict.ALLOW -> colorResource(id = R.color.primary)
                                }
                                Text(
                                    text = stringResource(id = R.string.settings_debug_verdict_format, verdictLabel),
                                    color = verdictColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "[Open NSFW] safe=${String.format(Locale.US, "%.4f", safe)} nsfw=${String.format(Locale.US, "%.4f", nsfw)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (xsNsfl != null && xsNsfw != null && xsSfw != null) {
                                    Text(
                                        text = "[xs] NSFL=${String.format(Locale.US, "%.4f", xsNsfl)} NSFW=${String.format(Locale.US, "%.4f", xsNsfw)} SFW=${String.format(Locale.US, "%.4f", xsSfw)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    text = "閾値: NSFW block=0.85/blur=0.30, NSFL block=0.75/blur=0.45 (2モデルOR結合)",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    modelErrorDialogMessage = localContext.getString(R.string.settings_debug_model_error_message)
                }) {
                    Text(stringResource(id = R.string.settings_debug_model_error_button))
                }

                // logcat / ツール履歴は「ログ」タブへ移動
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(id = R.string.settings_section_logs) + " →",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }
    }

    /**
     * LogcatRecorder がバックグラウンドで書き続けているログを表示するセクション。
     * - 収集自体は MyApplication 起動時から常時継続しているため、この画面を開くたびに
     *   その時点までの蓄積ログ（古いものは自動削除済み）を読み込むだけでよい。
     * - 自動更新 ON の間は一定間隔でファイルを再読込し、末尾に追従する。
     * - テキスト選択・全文コピー・ファイル書き出し（共有）・ログレベル別カラーリングに対応。
     */
    @Composable
    private fun LogcatViewerSection() {
        val localContext = LocalContext.current
        val scrollState = rememberScrollState()
        val clipboardManager = LocalClipboardManager.current

        fun refreshLogcatViewer() {
            logcatViewerText = LogcatRecorder.readAllLogs(localContext)
            val bytes = LogcatRecorder.totalSizeBytes(localContext)
            logcatViewerSizeLabel = "%.1f KB".format(bytes / 1024.0)
        }

        // 画面表示中、自動更新 ON なら 2 秒おきに再読込して末尾へ追従する。
        LaunchedEffect(logcatViewerAutoRefresh) {
            refreshLogcatViewer()
            while (logcatViewerAutoRefresh) {
                kotlinx.coroutines.delay(2000)
                refreshLogcatViewer()
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(id = R.string.settings_logcat_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
        }
        Text(
            text = stringResource(id = R.string.settings_logcat_desc),
            color = colorResource(id = R.color.text_secondary),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(id = R.string.settings_logcat_size, logcatViewerSizeLabel),
            color = colorResource(id = R.color.text_secondary),
            style = MaterialTheme.typography.labelSmall
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Button(onClick = { refreshLogcatViewer() }) {
                Text(stringResource(id = R.string.settings_debug_reload_button))
            }
            Button(onClick = { logcatViewerAutoRefresh = !logcatViewerAutoRefresh }) {
                Text(if (logcatViewerAutoRefresh) stringResource(id = R.string.settings_logcat_auto_on) else stringResource(id = R.string.settings_logcat_auto_off))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Button(onClick = {
                // 表示中の全文をクリップボードへコピーする。
                clipboardManager.setText(AnnotatedString(logcatViewerText))
                Toast.makeText(localContext, localContext.getString(R.string.settings_logcat_copied), Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(id = R.string.settings_debug_copy_button))
            }
            Button(onClick = {
                // 蓄積ログを1ファイルにマージして cacheDir へ書き出し、
                // FileProvider 経由で共有 Intent を発行する（メール添付・保存アプリなどに渡せる）。
                runCatching {
                    val file = LogcatRecorder.exportToFile(localContext)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        localContext,
                        "${localContext.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    localContext.startActivity(Intent.createChooser(shareIntent, localContext.getString(R.string.settings_logcat_export_title)))
                }.onFailure {
                    Toast.makeText(localContext, localContext.getString(R.string.settings_logcat_export_failed, it.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(stringResource(id = R.string.settings_debug_export_button))
            }
            Button(onClick = {
                LogcatRecorder.clearAll(localContext)
                refreshLogcatViewer()
            }) {
                Text(stringResource(id = R.string.settings_debug_clear_log_button))
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            // SelectionContainer でログ本文を選択可能にする（部分コピー・共有アプリへの引き渡し用）。
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) {
                Text(
                    text = if (logcatViewerText.isBlank()) {
                        AnnotatedString(stringResource(id = R.string.settings_logcat_empty))
                    } else {
                        colorizeLogcatText(logcatViewerText)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = colorResource(id = R.color.text_secondary)
                )
            }
        }
    }

    /**
     * logcat の各行を `threadtime` フォーマットのログレベル1文字（V/D/I/W/E/F）に基づいて色分けする。
     * 例: "08-03 12:34:56.789  1234  5678 E TAG: message" -> "E" を検出して赤系に着色。
     * 想定外のフォーマットの行はデフォルト色のまま表示する。
     */
    private fun colorizeLogcatText(rawText: String): AnnotatedString {
        // threadtime 形式: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
        // LEVEL 部分（1文字）だけを抜き出す軽量な正規表現。
        val levelRegex = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s""")

        return buildAnnotatedString {
            val lines = rawText.split("\n")
            for ((index, line) in lines.withIndex()) {
                val level = levelRegex.find(line)?.groupValues?.get(1)
                val color = when (level) {
                    "E", "F" -> Color(0xFFE57373) // Error / Fatal: 赤
                    "W" -> Color(0xFFFFB74D)       // Warning: オレンジ
                    "I" -> Color(0xFF81C784)       // Info: 緑
                    "D" -> Color(0xFF64B5F6)       // Debug: 青
                    "V" -> Color(0xFFB0BEC5)       // Verbose: グレー
                    else -> Color.Unspecified      // 不明なフォーマットはデフォルト色
                }
                withStyle(SpanStyle(color = color)) {
                    append(line)
                }
                if (index != lines.lastIndex) append("\n")
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
                Text(text = stringResource(id = R.string.settings_chat_history_management_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                Text(
                    text = stringResource(id = R.string.settings_chat_history_count_title),
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
                        label = { Text(stringResource(id = R.string.settings_chat_history_10)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 30,
                        onClick = { chatHistoryLimit = 30 },
                        label = { Text(stringResource(id = R.string.settings_chat_history_30)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 50,
                        onClick = { chatHistoryLimit = 50 },
                        label = { Text(stringResource(id = R.string.settings_chat_history_50)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == -1,
                        onClick = { chatHistoryLimit = -1 },
                        label = { Text(stringResource(id = R.string.settings_chat_history_unlimited_label)) },
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
            maxThreads = InferenceConfig.getMaxThreadCount()
            llamaCppThreads = threads.coerceIn(1, maxThreads)
            llamaCppGpuBackend = settingsRepository.getLlamaCppGpuBackend()
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
            return requireContext().getString(R.string.settings_inference_invalid_input)
        }
        if (temperature !in InferenceConfig.MIN_TEMPERATURE..InferenceConfig.MAX_TEMPERATURE) {
            return requireContext().getString(R.string.settings_inference_temperature_range, InferenceConfig.MIN_TEMPERATURE.toString(), InferenceConfig.MAX_TEMPERATURE.toString())
        }
        if (topP !in InferenceConfig.MIN_TOP_P..InferenceConfig.MAX_TOP_P) {
            return requireContext().getString(R.string.settings_inference_topp_range, InferenceConfig.MIN_TOP_P.toString(), InferenceConfig.MAX_TOP_P.toString())
        }
        if (topK !in InferenceConfig.MIN_TOP_K..InferenceConfig.MAX_TOP_K) {
            return requireContext().getString(R.string.settings_inference_topk_range, InferenceConfig.MIN_TOP_K.toString(), InferenceConfig.MAX_TOP_K.toString())
        }
        if (maxTokens !in InferenceConfig.MIN_MAX_TOKENS..InferenceConfig.MAX_MAX_TOKENS) {
            return requireContext().getString(R.string.settings_inference_max_tokens_range, InferenceConfig.MIN_MAX_TOKENS.toString(), InferenceConfig.MAX_MAX_TOKENS.toString())
        }
 // ユーザー要望: コンテキストウィンドウの上限を 128k まで拡張
        val maxContextWindow = if (selectedModel.equals("Gemma4-2B", ignoreCase = true) ||
                                    selectedModel.equals("Gemma4-4B", ignoreCase = true)) {
            131072
        } else {
            131072
        }
        if (contextWindow !in 512..maxContextWindow) {
            return requireContext().getString(R.string.settings_inference_context_range, maxContextWindow.toString())
        }
        if (contextCompressionThresholdPercent !in
            InferenceConfig.MIN_COMPRESSION_THRESHOLD..InferenceConfig.MAX_COMPRESSION_THRESHOLD
        ) {
            return requireContext().getString(R.string.settings_inference_compression_range, InferenceConfig.MIN_COMPRESSION_THRESHOLD.toString(), InferenceConfig.MAX_COMPRESSION_THRESHOLD.toString())
        }
        if (preloadMemoryWarningThresholdPercent !in
            MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT..MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT
        ) {
            return requireContext().getString(R.string.settings_inference_preload_memory_range, MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT.toString(), MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT.toString())
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
        settingsRepository.updateLlamaCppGpuBackend(llamaCppGpuBackend)
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
            title = { Text(stringResource(id = R.string.settings_engine_version_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(id = R.string.settings_engine_version_literlm_format, BuildConfig.LITERTLM_VERSION))
                    Text(stringResource(id = R.string.settings_engine_version_llamacpp_format, BuildConfig.LLAMACPP_VERSION))
                    Text(
                        stringResource(id = R.string.settings_engine_version_runtime_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(id = R.string.common_close))
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
            title = { Text(stringResource(id = R.string.settings_about_dialog_title)) },
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
                        contentDescription = stringResource(id = R.string.settings_about_icon_content_description),
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(id = R.string.brand_name_display),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorResource(id = R.color.text_primary)
                        )
                        Text(
                            text = stringResource(id = R.string.settings_about_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary),
                            textAlign = TextAlign.Center
                        )
                    }

                    AboutSection(title = stringResource(id = R.string.settings_about_app_info_title)) {
                        AboutInfoRow(stringResource(id = R.string.settings_about_version_label), BuildConfig.VERSION_NAME)
                        AboutInfoRow(stringResource(id = R.string.settings_about_build_number_label), BuildConfig.VERSION_CODE.toString())
                        AboutInfoRow(stringResource(id = R.string.settings_about_package_label), BuildConfig.APPLICATION_ID)
                        AboutInfoRow(stringResource(id = R.string.settings_about_build_type_label), BuildConfig.BUILD_TYPE)
                    }

                    AboutSection(title = stringResource(id = R.string.settings_about_engine_title)) {
                        AboutInfoRow(stringResource(id = R.string.settings_about_engine_literlm), BuildConfig.LITERTLM_VERSION)
                        AboutInfoRow(stringResource(id = R.string.settings_about_engine_gguf), BuildConfig.LLAMACPP_VERSION)
                        AboutInfoRow(stringResource(id = R.string.settings_about_engine_stable_diffusion), stringResource(id = R.string.settings_about_engine_stable_diffusion_value))
                        if (com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
                            AboutInfoRow(stringResource(id = R.string.settings_about_tts_label), "VOICEVOX CORE 0.16.4")
                        }
                    }

                    AboutSection(title = stringResource(id = R.string.settings_about_features_title)) {
                        AboutBullet(stringResource(id = R.string.settings_about_feature_gemma))
                        AboutBullet(stringResource(id = R.string.settings_about_feature_multimodal))
                        AboutBullet(stringResource(id = R.string.settings_about_feature_memory))
                        AboutBullet(stringResource(id = R.string.settings_about_feature_tools))
                    }

                    Text(
                        text = stringResource(id = R.string.settings_about_license_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary),
                        textAlign = TextAlign.Center
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onOpenLicenses) {
                    Text(stringResource(id = R.string.settings_about_license_link))
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(id = R.string.common_close))
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
                toast(requireContext().getString(R.string.settings_save_failed, it.message ?: ""))
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
                        text = if (hasExistingPin) stringResource(id = R.string.settings_pin_change_title) else stringResource(id = R.string.settings_pin_setup_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(id = R.string.settings_pin_instruction),
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
                        label = { Text(stringResource(id = R.string.settings_pin_label)) },
                        placeholder = { Text(stringResource(id = R.string.settings_pin_ph)) },
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
                            Text(stringResource(id = R.string.common_cancel))
                        }

                        Button(
                            onClick = { onPinSet(pinInput) },
                            enabled = pinInput.length == 4,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(id = R.string.common_next))
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
                        text = stringResource(id = R.string.settings_pin_confirm_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(id = R.string.settings_pin_confirm_instruction),
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
                        label = { Text(stringResource(id = R.string.settings_pin_confirm_label2)) },
                        placeholder = { Text(stringResource(id = R.string.settings_pin_ph)) },
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
                                    text = stringResource(id = R.string.settings_pin_mismatch),
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
                            Text(stringResource(id = R.string.common_cancel))
                        }

                        Button(
                            onClick = onMismatch,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(id = R.string.common_retry))
                        }
                    }
                }
            }
        }
    }
}
