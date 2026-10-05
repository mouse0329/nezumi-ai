package com.nezumi_ai.presentation.ui.fragment

import android.content.Intent
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import android.util.Log
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.layout.Box
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpFontFamily
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpTypography

class SettingsComposeFragment : Fragment() {
    private lateinit var settingsRepository: SettingsRepository
    internal lateinit var memoryRepository: MemoryRepository

    internal var contextWindowInput by mutableStateOf("4096")
    internal var temperatureInput by mutableStateOf("0.7")
    internal var topPInput by mutableStateOf("0.95")
    internal var topkInput by mutableStateOf("40")
    internal var maxTokensInput by mutableStateOf("1024")
    internal var speculativeDecodingEnabled by mutableStateOf(false)
    internal var requireMultimodal by mutableStateOf(false)
    internal var preloadMemoryWarningThresholdPercent by mutableStateOf(MemoryObserver.DEFAULT_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT)
    internal var selectedModel by mutableStateOf("E2B")
    internal var backendType by mutableStateOf("CPU")
    private var themeMode by mutableStateOf(PreferencesHelper.THEME_SYSTEM)
    internal var errorDialogMessage by mutableStateOf<String?>(null)
    internal var versionDialogVisible by mutableStateOf(false)
    private var aboutDialogVisible by mutableStateOf(false)
    internal var llamaCppThreads by mutableStateOf(InferenceConfig.getDefaultThreadCount())
    internal var maxThreads by mutableStateOf(InferenceConfig.getMaxThreadCount())
    internal var llamaCppGpuLayers by mutableStateOf(0)
    internal var llamaCppGpuBackend by mutableStateOf(LlamaCppGpuBackend.CPU)
    // GGUF 推論エンジンのプロセス分離 (dual-engine-process-isolation-plan) に伴い、
    // メインプロセスでは llama_bridge をロードしない方針になった。
    // nativeProbeGpuBackendAvailable は呼ぶと llama_backend_init() が走り
    // Vulkan/OpenCL のグローバル状態が :main プロセスに残ってしまうため、
    // ここではファイル存在ベースの足切りのみを行う (OpenClAvailability.detect /
    // VulkanAvailability.detect)。正確なプローブは :gguf プロセス側の
    // GgufInferenceEngine のロード時チェックが担い、実際に使えない場合は
    // 従来通り CPU へのフォールバックとしてユーザーに提示される。
    internal val openClAvailable: Boolean by lazy {
        runCatching { OpenClAvailability.detect() }.getOrDefault(false)
    }
    internal val vulkanAvailable: Boolean by lazy {
        runCatching { VulkanAvailability.detect() }.getOrDefault(false)
    }
    // CMakeLists.txt では OpenCL / Vulkan バックエンドがデフォルト ON (option … ON)
    // でビルドされる。llama_bridge を :main プロセスにロードせず判定するため、
    // ここではビルド定義と同じ静的な集合を使う。
    internal val llamaCppCompiledGpuBackends: Set<String> =
        setOf(LlamaCppGpuBackend.OPENCL, LlamaCppGpuBackend.VULKAN)
    internal var llamaCppBatchSize by mutableStateOf(512)
    internal var llamaCppUBatchSize by mutableStateOf(512)
    // マルチモーダル (mtmd) の画像最大トークン数。0 = デフォルト (256)。
    internal var llamaCppImageMaxTokens by mutableStateOf(0)
    internal var llamaCppKvUnified by mutableStateOf(true)
    private var llamaCppNKeep by mutableStateOf(0)
    internal var llamaCppRopeFreqBase by mutableStateOf(0.0f)
    internal var llamaCppRopeFreqScale by mutableStateOf(1.0f)
    internal var ropeFreqBaseInput by mutableStateOf("0.0")
    internal var memorySaveMode by mutableStateOf(MemorySaveMode.TOOL_ONLY.name)
    internal var chatHistoryLimit by mutableStateOf(30)
    internal var sdSteps by mutableStateOf(8)
    internal var sdCfg by mutableStateOf(7.0f)
    // Feature (設定画面への集約):
    //   メインの画像生成ページにあった「スケジューラ設定」「シード値設定」の
    //   デフォルト値をここで管理する。メインページの入力 UI は引き続き使えるが、
    //   初期値とリセット先はここで定める。画面の縦長化を防ぐため、
    //   元ページの大きな UI をそのままコピーしないことを方針とする。
    internal var sdSchedulerId by mutableStateOf(com.nezumi_ai.sd.SdScheduler.DEFAULT.id)
    internal var sdDefaultSeedInput by mutableStateOf("")
    internal var braveSearchApiKeyInput by mutableStateOf("")
    private var selectedSection by mutableStateOf(0)
    // 設定項目検索ボトムシートの表示フラグ。 true になると SettingsSearchSheet が開く。
    private var settingsSearchSheetVisible by mutableStateOf(false)
 // スマホ版設定画面でリスト表示か詳細表示かを切り替えるフラグ。
    //   true = カテゴリリスト表示、false = 選択中セクションの詳細表示。
    //   タブレット（幅 >= 600dp）では常にサイドバー+コンテンツの2ペイン表示なので使用しない。
    private var showSettingsListOnPhone by mutableStateOf(true)
    internal var debugTextAInput by mutableStateOf("")
    internal var debugTextBInput by mutableStateOf("")
    internal var debugTextSimilarityResult by mutableStateOf<String?>(null)
    internal var modelErrorDialogMessage by mutableStateOf<String?>(null)
    internal var mtpEnabled by mutableStateOf(false)
    internal var mtpDraftTokens by mutableStateOf(5)
    internal var flashAttentionEnabled by mutableStateOf(true)
    internal var dynamicBatchSizeEnabled by mutableStateOf(true)
    internal var promptBatchSize by mutableStateOf(512)
    internal var generationBatchSize by mutableStateOf(128)
    internal var kvCacheOptimizationEnabled by mutableStateOf(true)
    internal var contextShiftEnabled by mutableStateOf(true)
    internal var llamaCppThreadsBatch by mutableStateOf(0)
    internal var repeatPenaltyInput by mutableStateOf("1.1")
    internal var repeatLastNInput by mutableStateOf("64")
    internal var llamaCppSeedInput by mutableStateOf("-1")
    internal var llamaCppUseMmap by mutableStateOf(true)
    internal var llamaCppUseMlock by mutableStateOf(false)
    internal var llamaCppOffloadKqv by mutableStateOf(true)
    internal var llamaCppCacheTypeK by mutableStateOf("f16")
    internal var llamaCppCacheTypeV by mutableStateOf("f16")
    internal var llamaCppCacheTypeKMenuExpanded by mutableStateOf(false)
    internal var llamaCppCacheTypeVMenuExpanded by mutableStateOf(false)

    // NSFW チェッカー用のデバッグ UI 状態。ノン UI スレッドに入らないよう collectAsState でバインドする。
    internal var nsfwDebugBitmap by mutableStateOf<Bitmap?>(null)
    internal var nsfwDebugStatus by mutableStateOf<String?>(null)
    internal var nsfwDebugSafeProb by mutableStateOf<Float?>(null)
    internal var nsfwDebugNsfwProb by mutableStateOf<Float?>(null)
    internal var nsfwDebugRunning by mutableStateOf(false)
    // image-safety-classifier-xs (NSFL/NSFW/SFW) の並列判定結果
    internal var nsfwDebugXsNsflProb by mutableStateOf<Float?>(null)
    internal var nsfwDebugXsNsfwProb by mutableStateOf<Float?>(null)
    internal var nsfwDebugXsSfwProb by mutableStateOf<Float?>(null)
    internal lateinit var nsfwDebugPickLauncher: ActivityResultLauncher<String>

    // Qwen3-TTS (llama.cpp TTS) デバッグ用の UI 状態。
    internal var ttsDebugStatus by mutableStateOf<String?>(null)
    internal var ttsDebugResult by mutableStateOf<String?>(null)
    internal var ttsDebugReady by mutableStateOf(false)
    internal var ttsDebugDownloading by mutableStateOf(false)
    internal var ttsDebugDownloadProgress by mutableStateOf<Float?>(null)
    internal var ttsDebugSynthesizing by mutableStateOf(false)
    internal var ttsDebugTextInput by mutableStateOf("こんにちは。ネズミAI の音声合成テストです。")
    private var ttsDebugSpeakerPath by mutableStateOf<String?>(null)
    internal var ttsDebugSpeakerName by mutableStateOf<String?>(null)
    internal var ttsDebugOutputPath by mutableStateOf<String?>(null)
    internal var ttsDebugPlaying by mutableStateOf(false)
    private var ttsDebugPlayer: MediaPlayer? = null
    internal var ttsDebugAudioHistory by mutableStateOf<List<TtsDebugAudio>>(emptyList())
    private var ttsDebugSavePath: String? = null
    internal lateinit var ttsSpeakerPickLauncher: ActivityResultLauncher<String>
    private lateinit var ttsAudioSaveLauncher: ActivityResultLauncher<String>

    // Qwen3-TTS 0.6B の GGUF (バックボーン + トークナイザ)。llama-tts (mtmd) 形式の 2 ファイル構成。
    // 差し替え理由: 旧 URL (Jahaz/koboldcpp 向け変換) は本家 llama.cpp の qwen3tts アーキ実装と
    // メタデータキーの命名規則が食い違い、"key not found in model: qwen3tts.embedding_length" で
    // ロードに失敗していた。Serveurperso 配布版も general.architecture のハイフン有無や
    // general.file_type の型 (str/u32) が不正で、結局は自前で最新 llama.cpp 変換した GGUF を
    // Mouserat/qwen3-tts-0.6b-base-gguf にホストして使う。トークナイザ側は量子化せず f16 のまま。
    private val ttsDebugBackboneUrl = "https://huggingface.co/Mouserat/qwen3-tts-0.6b-base-gguf/resolve/main/qwen-talker-0.6b-base-Q8_0.gguf"
    private val ttsDebugBackboneName = "qwen-talker-0.6b-base-Q8_0.gguf"
    private val ttsDebugTokenizerUrl = "https://huggingface.co/Mouserat/qwen3-tts-0.6b-base-gguf/resolve/main/qwen-tokenizer-12hz-f16.gguf"
    private val ttsDebugTokenizerName = "qwen-tokenizer-12hz-f16.gguf"
    private lateinit var skillImportLauncher: ActivityResultLauncher<Array<String>>
    internal var skillScanResult by mutableStateOf(SkillScanResult(emptyList(), emptyList()))
    // エラーダイアログ用。追加/削除/リネームが失敗したときのメッセージを保持する。
    internal var skillDialogMessage by mutableStateOf<String?>(null)
    // 成功時に「エラー」タイトルのダイアログが出ていたバグ対策で、
    // 成功トーストを別ステートで扱う。null 以外なら通知として表示する。
    internal var skillInfoMessage by mutableStateOf<String?>(null)

    // logcat 常時収集ビューア用の状態。
    //   LogcatRecorder がバックグラウンドでファイルに書き続けているログを
    //   一定間隔で読み込んで表示するだけで、収集自体はこの画面の開閉に依存しない。
    internal var logcatViewerText by mutableStateOf("")
    internal var logcatViewerAutoRefresh by mutableStateOf(true)
    internal var logcatViewerSizeLabel by mutableStateOf("")

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
        ttsSpeakerPickLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) importTtsDebugSpeakerAudio(uri)
        }
        ttsAudioSaveLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("audio/wav")
        ) { uri: Uri? ->
            val sourcePath = ttsDebugSavePath
            ttsDebugSavePath = null
            if (uri != null && sourcePath != null) saveTtsDebugAudio(File(sourcePath), uri)
        }
        loadTtsDebugAudioHistory(requireContext().applicationContext)
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

    // ---- Qwen3-TTS (llama.cpp TTS) デバッグ用ヘルパー ----
    // モデルは filesDir/tts/ に保存する。バックボーン + トークナイザ (mmproj 相当) の 2 ファイル構成。
    private fun ttsDebugDir(context: Context): File = File(context.filesDir, "tts")
    private fun ttsDebugBackboneFile(context: Context): File = File(ttsDebugDir(context), ttsDebugBackboneName)
    private fun ttsDebugTokenizerFile(context: Context): File = File(ttsDebugDir(context), ttsDebugTokenizerName)

    private fun ttsDebugAudioDir(context: Context): File = File(context.filesDir, "tts_generated")

    internal data class TtsDebugAudio(
        val file: File,
        val text: String,
        val createdAt: Long
    )

    private fun loadTtsDebugAudioHistory(context: Context) {
        val dir = ttsDebugAudioDir(context)
        ttsDebugAudioHistory = dir.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { TtsDebugAudio(it, it.nameWithoutExtension, it.lastModified()) }
    }

    private fun saveTtsDebugAudio(source: File, destination: Uri) {
        val context = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                source.inputStream().use { input ->
                    context.contentResolver.openOutputStream(destination)?.use { output ->
                        input.copyTo(output)
                    } ?: error("保存先を開けませんでした")
                }
            }
            withContext(Dispatchers.Main) {
                result.onFailure {
                    ttsDebugStatus = getString(R.string.settings_debug_tts_failed, it.message ?: "save")
                }
            }
        }
    }

    internal fun requestSaveTtsDebugAudio(audio: TtsDebugAudio) {
        ttsDebugSavePath = audio.file.absolutePath
        ttsAudioSaveLauncher.launch(audio.file.name)
    }

    internal fun deleteTtsDebugAudio(audio: TtsDebugAudio) {
        if (ttsDebugPlaying && ttsDebugOutputPath == audio.file.absolutePath) stopTtsDebugAudio()
        audio.file.delete()
        ttsDebugAudioHistory = ttsDebugAudioHistory.filterNot { it.file.absolutePath == audio.file.absolutePath }
        if (ttsDebugOutputPath == audio.file.absolutePath) ttsDebugOutputPath = null
    }

    private fun isTtsDebugGgufFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                ByteArray(4).also { raf.readFully(it) }.contentEquals(byteArrayOf(
                    'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()
                ))
            }
        }.getOrDefault(false)
    }

    internal fun refreshTtsDebugStatus(context: Context) {
        val backbone = ttsDebugBackboneFile(context)
        val tokenizer = ttsDebugTokenizerFile(context)
        val backboneValid = isTtsDebugGgufFile(backbone)
        val tokenizerValid = isTtsDebugGgufFile(tokenizer)
        ttsDebugReady = backboneValid && tokenizerValid
        if (backbone.exists() && !backboneValid) backbone.delete()
        if (tokenizer.exists() && !tokenizerValid) tokenizer.delete()
        Log.i(
            "TtsDebug",
            "model status: backboneExists=${backbone.exists()}, backboneBytes=${backbone.length()}, " +
                "tokenizerExists=${tokenizer.exists()}, tokenizerBytes=${tokenizer.length()}, ready=$ttsDebugReady"
        )
        if (ttsDebugReady) {
            ttsDebugStatus = getString(R.string.settings_debug_tts_ready)
        }
    }

    // TTS デバッグ用モデルの削除。tts/ ディレクトリを丸ごと消すことで、
    // URL 変更前にダウンロードされた古いファイル (例: 旧 Jahaz 版の
    // qwen3-tts-0.6b-q5k.gguf、旧 Serveurperso 版の qwen-tokenizer-12hz-Q8_0.gguf) が
    // 残っていてもまとめて片付く。アプリ全体のデータ初期化 (他モデル含め
    // 全消去) を避けて、TTS モデルだけをピンポイントで消せるようにする。
    internal fun deleteTtsDebugModels(context: Context) {
        if (ttsDebugDownloading || ttsDebugSynthesizing) return
        val appContext = context.applicationContext
        val dir = ttsDebugDir(appContext)
        val deleted = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        Log.i("TtsDebug", "model delete: dir=${dir.absolutePath}, deleted=$deleted")
        ttsDebugReady = false
        ttsDebugResult = null
        ttsDebugStatus = if (deleted || !dir.exists()) {
            getString(R.string.settings_debug_tts_deleted)
        } else {
            getString(R.string.settings_debug_tts_failed, "delete")
        }
    }

    internal fun downloadTtsDebugModels(context: Context) {
        if (ttsDebugDownloading) return
        ttsDebugDownloading = true
        ttsDebugDownloadProgress = 0f
        ttsDebugStatus = null
        val appContext = context.applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val targets = listOf(
                ttsDebugBackboneUrl to ttsDebugBackboneFile(appContext),
                ttsDebugTokenizerUrl to ttsDebugTokenizerFile(appContext)
            )
            var ok = true
            for ((url, dest) in targets) {
                if (isTtsDebugGgufFile(dest)) {
                    Log.i("TtsDebug", "model download skipped; valid file exists: ${dest.name}, bytes=${dest.length()}")
                    continue
                }
                if (dest.exists()) dest.delete()
                ok = downloadTtsDebugFile(url, dest)
                if (!ok) break
            }
            withContext(Dispatchers.Main) {
                ttsDebugDownloading = false
                ttsDebugDownloadProgress = null
                if (ok) {
                    refreshTtsDebugStatus(appContext)
                } else {
                    ttsDebugStatus = getString(R.string.settings_debug_tts_failed, "download")
                }
            }
        }
    }

    /** Hugging Face (resolve URL) から 1 ファイルをダウンロードする。進捗はステータス欄に反映。 */
    private suspend fun downloadTtsDebugFile(url: String, dest: File): Boolean {
        return runCatching {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            Log.i("TtsDebug", "model download started: file=${dest.name}")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode} for $url")
            }
            conn.inputStream.use { input ->
                val total = conn.contentLengthLong
                var lastLoggedPercent = -1
                var lastLoggedMegabytes = -1L
                Log.i("TtsDebug", "model download connected: file=${dest.name}, totalBytes=$total")
                withContext(Dispatchers.Main) {
                    ttsDebugDownloadProgress = if (total > 0L) 0f else null
                }
                tmp.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var written = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt()
                            withContext(Dispatchers.Main) {
                                ttsDebugDownloadProgress = (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                ttsDebugStatus = getString(
                                    R.string.settings_debug_tts_downloading,
                                    "${dest.name} $pct%"
                                )
                            }
                            if (pct != lastLoggedPercent) {
                                Log.d("TtsDebug", "model download progress: file=${dest.name}, percent=$pct, downloadedBytes=$written, totalBytes=$total")
                                lastLoggedPercent = pct
                            }
                        } else {
                            val megabytes = written / (1024 * 1024)
                            withContext(Dispatchers.Main) {
                                ttsDebugDownloadProgress = null
                                ttsDebugStatus = getString(
                                    R.string.settings_debug_tts_downloading,
                                    "${dest.name} ${megabytes} MB"
                                )
                            }
                            if (megabytes != lastLoggedMegabytes) {
                                Log.d("TtsDebug", "model download progress: file=${dest.name}, downloadedBytes=$written, totalBytes=unknown")
                                lastLoggedMegabytes = megabytes
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (!tmp.renameTo(dest)) {
                throw java.io.IOException("failed to move temporary file to ${dest.absolutePath}")
            }
            if (!isTtsDebugGgufFile(dest)) {
                dest.delete()
                throw java.io.IOException("downloaded file is not a GGUF: ${dest.name}")
            }
            Log.i("TtsDebug", "model download completed: file=${dest.name}, bytes=${dest.length()}")
            dest.length() > 0L
        }.onFailure { error ->
            Log.e("TtsDebug", "model download failed: file=${dest.name}, reason=${error.message}", error)
        }.getOrDefault(false)
    }

    /** 声色クローン用の参照音声を SAF から取り込み、cacheDir にコピーする。 */
    private fun importTtsDebugSpeakerAudio(uri: Uri) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val name = runCatching {
                appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
            }.getOrNull() ?: "speaker.wav"
            val ext = name.substringAfterLast('.', "wav")
            val dest = File(appContext.cacheDir, "tts_speaker.$ext")
            val ok = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) {
                    ttsDebugSpeakerPath = dest.absolutePath
                    ttsDebugSpeakerName = name
                } else {
                    ttsDebugStatus = getString(R.string.settings_debug_tts_failed, name)
                }
            }
        }
    }

    /** Qwen3-TTS で音声を合成し、MediaPlayer で再生する。ネイティブ側は数十秒ブロックするので IO スレッドで呼ぶ。 */
    internal fun runTtsDebugSynthesis(context: Context) {
        if (ttsDebugSynthesizing) return
        ttsDebugSynthesizing = true
        ttsDebugResult = null
        ttsDebugOutputPath = null
        stopTtsDebugAudio()
        val appContext = context.applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val backbone = ttsDebugBackboneFile(appContext)
            val tokenizer = ttsDebugTokenizerFile(appContext)
            Log.i(
                "TtsDebug",
                "synthesis requested: backboneExists=${backbone.isFile}, backboneBytes=${backbone.length()}, " +
                    "backboneGguf=${isTtsDebugGgufFile(backbone)}, tokenizerExists=${tokenizer.isFile}, " +
                    "tokenizerBytes=${tokenizer.length()}, tokenizerGguf=${isTtsDebugGgufFile(tokenizer)}"
            )
            val outFile = File(
                ttsDebugAudioDir(appContext),
                "tts_${System.currentTimeMillis()}.wav"
            ).apply { parentFile?.mkdirs() }
            // llama_bridge はプロセス分離後メインプロセスにロードしないため、
            // TTS 合成は :gguf プロセスの GgufInferenceService 経由で実行する。
            val raw = try {
                com.nezumi_ai.data.inference.remote.RemoteGgufInferenceEngine(appContext)
                    .ttsSynthesize(
                        backbone.absolutePath,
                        tokenizer.absolutePath,
                        ttsDebugTextInput,
                        ttsDebugSpeakerPath,
                        outFile.absolutePath,
                        4,
                        512,
                        -1
                    )
            } catch (t: Throwable) {
                org.json.JSONObject()
                    .put("ok", false)
                    .put("error", t.message ?: "tts remote call failed")
                    .toString()
            }
            withContext(Dispatchers.Main) {
                ttsDebugSynthesizing = false
                runCatching {
                    val json = JSONObject(raw)
                    if (json.optBoolean("ok")) {
                        val sampleRate = json.optInt("sample_rate")
                        val audioSec = json.optDouble("audio_sec")
                        ttsDebugResult = getString(
                            R.string.settings_debug_tts_result,
                            outFile.name, audioSec, sampleRate
                        )
                        ttsDebugOutputPath = outFile.absolutePath
                        ttsDebugAudioHistory = listOf(
                            TtsDebugAudio(outFile, ttsDebugTextInput, outFile.lastModified())
                        ) + ttsDebugAudioHistory
                        playTtsDebugAudio(outFile)
                    } else {
                        ttsDebugStatus = getString(
                            R.string.settings_debug_tts_failed,
                            json.optString("error")
                        )
                    }
                }.onFailure {
                    ttsDebugStatus = getString(R.string.settings_debug_tts_failed, raw.take(120))
                }
            }
        }
    }

    internal fun playTtsDebugAudio(file: File) {
        runCatching {
            ttsDebugPlayer?.release()
            ttsDebugPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    it.release()
                    ttsDebugPlaying = false
                }
                start()
            }
            ttsDebugPlaying = true
        }.onFailure {
            ttsDebugPlaying = false
            ttsDebugStatus = getString(R.string.settings_debug_tts_failed, it.message ?: "playback")
        }
    }

    internal fun stopTtsDebugAudio() {
        ttsDebugPlayer?.runCatching { stop() }
        ttsDebugPlayer?.release()
        ttsDebugPlayer = null
        ttsDebugPlaying = false
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

    override fun onDestroyView() {
        ttsDebugPlayer?.release()
        ttsDebugPlayer = null
        ttsDebugPlaying = false
        super.onDestroyView()
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
        val ctx = remember { requireContext().applicationContext }
        val chatViewModel = remember(ctx) {
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
            ViewModelProvider(requireActivity(), chatViewModelFactory)
                .get(com.nezumi_ai.presentation.viewmodel.ChatViewModel::class.java)
        }
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
                    append(speculativeDecodingEnabled); append('|')
                    append(requireMultimodal); append('|')
                    append(preloadMemoryWarningThresholdPercent); append('|')
                    append(backendType); append('|')
                    append(llamaCppThreads); append('|')
                    append(llamaCppGpuBackend); append('|')
                    append(llamaCppGpuLayers); append('|')
                    append(llamaCppBatchSize); append('|')
                    append(llamaCppUBatchSize); append('|')
                    append(llamaCppImageMaxTokens); append('|')
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
        // 検索結果タップ時のジャンプ先。「どの行がどこにいるか」は各行が settingsSearchAnchor で
        // このインスタンスに自己登録するので、ここでは労せず本物の行へスクロール・点滅できる。
        val settingsSearchJumpState = remember { SettingsSearchJumpState() }
        var pendingSearchJump by remember { mutableStateOf<SettingsSearchResult?>(null) }
        val settingsContentListState = rememberLazyListState()
        LaunchedEffect(pendingSearchJump) {
            val target = pendingSearchJump ?: return@LaunchedEffect
            // セクション切替直後はまだ対象行がコンポーズされていないことがあるため、
            // まずリストの先頭付近まで戻してから、行が自己登録されるのを待って実座標へスクロールする。
            settingsContentListState.scrollToItem(0)
            settingsSearchJumpState.jumpTo(target.anchorKey)
            if (pendingSearchJump == target) pendingSearchJump = null
        }
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
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // 設定項目検索: キーワードで項目を探し、タップでそのセクションへジャンプする。
                IconButton(onClick = { settingsSearchSheetVisible = true }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = stringResource(id = R.string.settings_search_hint),
                        tint = colorResource(id = R.color.text_primary)
                    )
                }
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
                        state = settingsContentListState,
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
                            var isShowEngineLoadLog by remember { mutableStateOf(PreferencesHelper.isShowEngineLoadLog(context)) }
                            var isMiniAppDevMode by remember { mutableStateOf(PreferencesHelper.isMiniAppDevModeEnabled(context)) }
                            var isShowTps by remember { mutableStateOf(PreferencesHelper.isShowTps(context)) }
                            var isShowTtft by remember { mutableStateOf(PreferencesHelper.isShowTtft(context)) }
                            var isDisableScreenshot by remember { mutableStateOf(PreferencesHelper.isDisableScreenshot(context)) }
                            var pendingAlwaysLockEnable by remember { mutableStateOf(false) }
                            // i18n: アプリ UI の言語 (SYSTEM / JA / EN) を全般タブから切り替える。
                            var appLanguage by remember { mutableStateOf(PreferencesHelper.getLanguage(context)) }

                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = stringResource(id = R.string.settings_general_title), fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)

                                // テーマ設定セクション
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.settingsSearchAnchor(
                                        R.string.settings_theme_current_format,
                                        settingsSearchJumpState
                                    )
                                ) {
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
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.settingsSearchAnchor(
                                        R.string.settings_language_current_format,
                                        settingsSearchJumpState
                                    )
                                ) {
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
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_always_lock_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
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
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_stop_kb_learning_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
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
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_show_context_meter_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
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

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_show_engine_load_log_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
                                        Text(
                                            text = stringResource(id = R.string.settings_show_engine_load_log_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_show_engine_load_log_desc),
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isShowEngineLoadLog,
                                        onCheckedChange = { checked ->
                                            isShowEngineLoadLog = checked
                                            PreferencesHelper.setShowEngineLoadLog(context, checked)
                                        },
                                        colors = nezumiSwitchColors()
                                    )
                                }

                                HorizontalDivider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)

                                // Mini App 開発者モード (仕様 v1.1 §32 Developer Mode)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_miniapp_dev_mode_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
                                        Text(
                                            text = stringResource(id = R.string.settings_miniapp_dev_mode_title),
                                            color = colorResource(id = R.color.text_primary),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = stringResource(id = R.string.settings_miniapp_dev_mode_desc),
                                            color = colorResource(id = R.color.text_secondary),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Switch(
                                        checked = isMiniAppDevMode,
                                        onCheckedChange = { checked ->
                                            isMiniAppDevMode = checked
                                            PreferencesHelper.setMiniAppDevModeEnabled(context, checked)
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
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_show_tps_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
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
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_show_ttft_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
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
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .settingsSearchAnchor(
                                                R.string.settings_disable_screenshot_title,
                                                settingsSearchJumpState
                                            )
                                    ) {
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
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.settingsSearchAnchor(
                                        R.string.settings_secret_mode_title,
                                        settingsSearchJumpState
                                    )
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
                        TelemetryConsentCard()
                    }
                    1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InferenceParamsCard(settingsSearchJumpState)
                        GgufLlamaCppSettingsCard(settingsSearchJumpState)
                        LiteRtSettingsCard(settingsSearchJumpState)
                    }
                    2 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ImageGenSettingsCard(settingsSearchJumpState)
                    }
                    3 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MemoryManagementCard(settingsSearchJumpState)
                    }
                    4 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatHistoryCard(settingsSearchJumpState)
                    }
                    // ログタブ（常時・リリースビルドでも表示）: ツール呼出履歴 / logcat をサブタブで表示
                    5 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LogsSettingsCard(settingsSearchJumpState)
                    }
                    // ツールタブ（常時 index 6）: ページ取得のJS実行モード + MCPサーバー管理
                    6 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToolsSettingsCard(settingsSearchJumpState)
                    }
                    7 -> SkillManagementCard(skillScanResult, onImport = { skillImportLauncher.launch(arrayOf("application/zip")) }, settingsSearchJumpState = settingsSearchJumpState)
                    8 -> StorageManagementSection(onOpenSession = { sessionId ->
                        findNavController().navigate(R.id.chatFragment, Bundle().apply { putLong("sessionId", sessionId) })
                    })
                    // デバッグタブは BuildConfig.DEBUG 時のみ index 9
                    9 -> if (BuildConfig.DEBUG) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DebugSettingsCard(settingsSearchJumpState)
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

        // 設定検索ボトムシート: 結果タップで該当セクションへジャンプする。
        //   スマホでは詳細表示に切り替え、タブレットではサイドバーの選択を変えるだけでよい。
        if (settingsSearchSheetVisible) {
            SettingsSearchSheet(
                onJumpToSection = { result ->
                    selectedSection = result.sectionIndex
                    pendingSearchJump = result
                    if (!isTablet) showSettingsListOnPhone = false
                },
                onDismiss = { settingsSearchSheetVisible = false }
            )
        }
    }


    /**
     * ユーザースキルのフォルダ名 (= skill.name) を変更するダイアログ。
     * SkillPathResolver.isValidName と同じパターン ([a-z0-9-]{1,64}) でバリデーションし、
     * 現在の名前と同じ場合は確定ボタンを無効化する。
     */

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


    /**
     * 設定 > ツール タブ
     * ページ取得のJS実行モードON/OFFと、MCPサーバーの登録・編集・削除を扱う。
     * 各ツール（アラーム・タイマー・画像生成等）自体の有効化は
     * プリセット編集画面（PresetSettingsFragment）側で行う。
     */

    /**
     * 設定 > ログ タブ
     * ツールコール呼出履歴と logcat をサブタブで分けて表示する。
     * リリースビルドでも利用可能。
     */

    /**
     * ツールコール呼出履歴（時刻・セッション・ツール・クエリ）を表示する。
     */


    /**
     * LogcatRecorder がバックグラウンドで書き続けているログを表示するセクション。
     * - 収集自体は MyApplication 起動時から常時継続しているため、この画面を開くたびに
     *   その時点までの蓄積ログ（古いものは自動削除済み）を読み込むだけでよい。
     * - 自動更新 ON の間は一定間隔でファイルを再読込し、末尾に追従する。
     * - テキスト選択・全文コピー・ファイル書き出し（共有）・ログレベル別カラーリングに対応。
     */

    /**
     * logcat の各行を `threadtime` フォーマットのログレベル1文字（V/D/I/W/E/F）に基づいて色分けする。
     * 例: "08-03 12:34:56.789  1234  5678 E TAG: message" -> "E" を検出して赤系に着色。
     * 想定外のフォーマットの行はデフォルト色のまま表示する。
     */


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
            llamaCppImageMaxTokens = PreferencesHelper.getLlamaCppImageMaxTokens(requireContext())
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
            val prefsContext = requireContext()
            llamaCppThreadsBatch = PreferencesHelper.getLlamaCppThreadsBatch(prefsContext)
            repeatPenaltyInput = PreferencesHelper.getLlamaCppRepeatPenalty(prefsContext).toString()
            repeatLastNInput = PreferencesHelper.getLlamaCppRepeatLastN(prefsContext).toString()
            llamaCppSeedInput = PreferencesHelper.getLlamaCppSeed(prefsContext).toString()
            llamaCppUseMmap = PreferencesHelper.getLlamaCppUseMmap(prefsContext)
            llamaCppUseMlock = PreferencesHelper.getLlamaCppUseMlock(prefsContext)
            llamaCppOffloadKqv = PreferencesHelper.getLlamaCppOffloadKqv(prefsContext)
            llamaCppCacheTypeK = PreferencesHelper.getLlamaCppCacheTypeK(prefsContext)
            llamaCppCacheTypeV = PreferencesHelper.getLlamaCppCacheTypeV(prefsContext)
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
        PreferencesHelper.setLlamaCppImageMaxTokens(requireContext(), llamaCppImageMaxTokens)
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
        val prefsContext = requireContext()
        PreferencesHelper.setLlamaCppThreadsBatch(prefsContext, llamaCppThreadsBatch)
        PreferencesHelper.setLlamaCppRepeatPenalty(prefsContext, repeatPenaltyInput.toFloatOrNull() ?: 1.1f)
        PreferencesHelper.setLlamaCppRepeatLastN(prefsContext, repeatLastNInput.toIntOrNull() ?: 64)
        PreferencesHelper.setLlamaCppSeed(prefsContext, llamaCppSeedInput.toIntOrNull() ?: -1)
        PreferencesHelper.setLlamaCppUseMmap(prefsContext, llamaCppUseMmap)
        PreferencesHelper.setLlamaCppUseMlock(prefsContext, llamaCppUseMlock)
        PreferencesHelper.setLlamaCppOffloadKqv(prefsContext, llamaCppOffloadKqv)
        PreferencesHelper.setLlamaCppCacheTypeK(prefsContext, llamaCppCacheTypeK)
        PreferencesHelper.setLlamaCppCacheTypeV(prefsContext, llamaCppCacheTypeV)
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

    internal fun toast(message: String) {
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


}
