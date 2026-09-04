package com.nezumi_ai.presentation.ui.fragment

import com.nezumi_ai.data.inference.cloud.*

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
// pointerInput: ロードオーバーレイのタップ消費用
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.nezumi_ai.presentation.ui.composable.ErrorModalDialog
import com.nezumi_ai.presentation.ui.composable.ErrorModalDialogContent
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
import com.nezumi_ai.databinding.FragmentChatBinding
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.stripGemmaTokens
import com.nezumi_ai.data.inference.stripTxtFileBlocks
import com.nezumi_ai.data.inference.stripVideoBlocks
import com.nezumi_ai.data.media.VideoAttachmentEncoding
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.presentation.viewmodel.ChatViewModel
import com.nezumi_ai.presentation.viewmodel.ChatViewModelFactory
import com.nezumi_ai.presentation.viewmodel.ImageGenConfirmationRequest
import com.nezumi_ai.presentation.ui.adapter.MessageAdapter
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.presentation.ui.composable.ToolCallProgressBar
import com.nezumi_ai.presentation.ui.composable.MediaPreviewBar
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlin.math.max
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.style.TextOverflow
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpFontFamily
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpTypography

class ChatFragment : Fragment(R.layout.fragment_chat) {

    companion object {
        private const val TAG = "ChatFragment"
        /** ドロップダウン・ヘッダーで長いラベルを省略するときの先頭文字数 */
        private const val MODEL_NAME_DISPLAY_CHARS = 16
        /** 画像のマルチセレクト上限。 LiteRtLmEngine.MAX_VISION_IMAGES と揃える。 */
        private const val MAX_SELECTABLE_IMAGES = 5

        /**
         * プレーンテキストとして扱えるファイル拡張子。
         * md / js / ts / cs / log / txt / py をはじめ、ソースコード・設定・データ交換系を広く含む。
         */
        private val TEXT_FILE_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "cs", "java", "kt", "kts", "c", "h", "cpp", "cc", "hpp", "go", "rs", "rb",
            "php", "swift", "sh", "bash", "zsh", "bat", "ps1", "json", "yaml", "yml",
            "xml", "html", "htm", "css", "scss", "less", "sql", "ini", "cfg", "conf",
            "toml", "csv", "tsv", "gradle", "properties", "env", "gitignore", "lua", "r"
        )
    }

    private fun modelDisplaySuffix(label: String): String =
        if (label.length <= MODEL_NAME_DISPLAY_CHARS) label
        else label.take(MODEL_NAME_DISPLAY_CHARS).trimEnd() + "…"

    private fun isImageGenerationToolState(state: ToolCallState?): Boolean =
        when (state) {
            is ToolCallState.Executing -> state.toolName.equals("generate_image", ignoreCase = true)
            else -> false
        }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private var defaultInputHint: CharSequence? = null

    private lateinit var viewModel: ChatViewModel
    private lateinit var adapter: MessageAdapter
    private val args: ChatFragmentArgs by navArgs()
    private var modelOptions: List<ModelOption> = emptyList()
    private var responseTypingAnimationJob: Job? = null
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var presetRepository: PresetRepository
    private var isGenerating = false
    private var isModelLoadingNow = false
    private var currentBackendType = "CPU"
    private var currentModelKey = "E2B"
    private var isCompressingNow = false

 // セッション切り替え最適化: navigateToChatSession がフラグメントを再生成する代わりに、
    //   このメソッドを呼んでセッションIDだけ切り替えることでページ遷移の重さを軽減する。
    //   MainActivity.navigateToChatSession から呼ばれる。
    fun switchSession(sessionId: Long) {
        if (_binding == null || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.saveCurrentSessionId(sessionId)
                val prefs = requireContext().getSharedPreferences("nezumi_ai_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putLong("current_session_id", sessionId).apply()
                viewModel.setCurrentSession(sessionId)
                withContext(Dispatchers.Main) {
                    pendingInitialScrollToBottom = true
                    userScrolledAwayDuringGeneration = false
                    // ドロワーの履歴リストは「セッション更新」をきっかけにしか
                    // ハイライトを再評価しない。switchSession 経由の切り替えでは
                    // 履歴の並びが変わらないため通知が来ず、前のセッションの
                    // ハイライトが残り続けるバグがあったので、ここで明示的に
                    // ハイライトだけ最新のセッションへ追随させる。
                    (activity as? com.nezumi_ai.MainActivity)?.refreshDrawerSessionHighlight()
                }
            } catch (e: Exception) {
                Log.e(TAG, "switchSession failed", e)
            }
        }
    }
    private var responseTypingVisible by mutableStateOf(false)
    private var responseTypingText by mutableStateOf("")
    private var modelLoadingOverlayVisible by mutableStateOf(false)
    private var modelLoadingText by mutableStateOf("")
    private var contextMeterText by mutableStateOf("")
    private var contextMeterProgress by mutableStateOf(0f)
    private var contextUsageCharsNow by mutableStateOf(0)
 // 新: コンテキストメーターの表示可否。全般タブで切り替えられる。既定は表示しない。
    private var contextMeterVisible by mutableStateOf(false)
    // メーターをタップしたときに表示する raw コンテキストモーダルの可視フラグと中身。
    private var contextRawDialogVisible by mutableStateOf(false)
    private var contextRawText by mutableStateOf("")

    // Bug fix(#43): t/s ・ TTFT トグルの値をフラグメント側でも保持し、onResume で変化を検知して
    // MessageAdapter に強制リバインドを依頼する。これにより、設定タブでトグルした直後に
    // チャット画面に戻ってきたとき、既存 ViewHolder でも即座に反映される。
    private var showTpsIndicator: Boolean = false
    private var showTtftIndicator: Boolean = false
    private var scrollToBottomVisible by mutableStateOf(false)
    private var compressButtonVisible by mutableStateOf(true)
    private var compressButtonEnabled by mutableStateOf(true)
    private var compressButtonText by mutableStateOf("")
    private var contextCompressionEnabled by mutableStateOf(false)
    private var thinkingToggleVisible by mutableStateOf(false)
    private var thinkingToggleEnabled by mutableStateOf(false)
    private var thinkingToggleChecked by mutableStateOf(false)
    private var thinkingToggleText by mutableStateOf("")
    private var currentToolCallState by mutableStateOf<ToolCallState?>(null)
    private var currentImageGenProgress by mutableStateOf<Pair<Int, Int>?>(null)
    private var messagesIsEmpty by mutableStateOf(true)
    private var isUserAtBottom = true
    private var wasImeVisible = false
    private var autoScrollPosted = false
    private val autoScrollDebounceMs = 48L
    private val autoFollowMaxFrames = 18
    private val immediateScrollMaxFrames = 10
    private val autoFollowBottomThresholdPx = 120
    private var lastKnownScrollRange = 0
    private var lastObservedPresetId: String? = null
    // バグ修正: モデル削除でプリセットが未選択状態になった際のダイアログが
    // onResume のたびに重複して出ないようにするためのガード。
    private var presetModelUnselectedDialogShownForPresetId: String? = null
    private var pendingInitialScrollToBottom = true
    // 生成中にユーザーが意図的に上スクロールしたときだけ true。
    // 最下部に戻るか送信するとリセット。
    private var userScrolledAwayDuringGeneration = false
    // ユーザーが RecyclerView を直接ドラッグしている間だけ true。
    // プログラム側の scrollBy / postOnAnimation で auto-follow を誤停止しないために分離して扱う。
    private var userIsDraggingMessages = false
    // 自動追従中は、テーブル列追加などの大きな再レイアウトで底判定が一瞬外れても維持する。
    // ユーザーが明示的に上へドラッグした時だけ false にする。
    private var autoFollowBottomLocked = true
    // 生成完了直後の後処理フェーズ (TPS 表示 / 再生成・読み上げボタン / Markdown 再レイアウト等が
    // 遅延登場する短時間) では、shouldAutoFollowBottom() が false でも末尾追従を強制するためのフラグ。
    // これが true の間は下矢印ボタンも表示しない（自動で下に着地するため）。
    private var postGenerationSettleActive = false

    /**
 * 再生成タップ後、AI メッセージの animateContentSize 完了コールバックを受けたときにだけ
     *   生成物の下端までスクロールさせるワンショットフラグ。
     *   生成中 (isGenerating=true) の普段は既存の shouldAutoFollowBottom() 経路で追従するので
     *   ここでは生成完了後の一回のジャンプだけを担当する。
     */
    private var scrollToBottomOnNextAiLayout = false

    private data class ScrollAnchor(val position: Int, val offset: Int)

    private val autoScrollRunnable = Runnable {
        autoScrollPosted = false
        followBottomAfterLayout()
    }

    // Phase 11: 複数画像対応（Compose State管理で UI 再構成を自動化）
    private var selectedImageUrisList by mutableStateOf<List<String>>(emptyList())
    private var selectedAudioUri by mutableStateOf<String?>(null)  // State管理化
    /**
     * 選択された元動画 URI とメタ情報。DB スキーマを変えない方針でメモリ内のみ保持。
     * ビュワーとプレビューでの "動画を見せる" 用と、送信時に Gemma 4 向けプロンプトに
     * <video> ブロック (音声メタ / フレーム一覧) を差し込むための情報源として使う。
     */
    private var selectedVideoUri by mutableStateOf<String?>(null)
    private var selectedVideoDurationMs: Long = 0L
    private var selectedVideoFrameCount: Int = 0
    /**
     * テキストファイル添付の一覧。内容は送信時に `<txtfile>` ブロックとして
     * プロンプトへ挿入され、UI にはファイル名の一覧だけを表示する
     * (タップすると TextFileViewerDialog で中身を開ける)。
     */
    private var selectedTextFiles by mutableStateOf<List<com.nezumi_ai.data.media.TextFileAttachmentEncoding.TextFileEntry>>(emptyList())
    /** 動画選択後、フレーム/音声抽出が完了するまで true。抽出中は送信不可にする。 */
    private var isExtractingVideo by mutableStateOf(false)
    /**
     * ドキュメント添付 (PDF/Word/Excel等) の Markdown 変換が進行中かどうか。
     * 動画のフレーム抽出 (isExtractingVideo) と同じ思想で、変換完了までは
     * 送信ボタンを無効化し、プレビューバーにスピナーチップを表示する。
     */
    private var isConvertingDocument by mutableStateOf(false)
    /** 現在変換中のドキュメント名 (複数同時添付は逐次処理。表示用) */
    private var convertingDocumentName by mutableStateOf<String?>(null)
    private var cameraImageUri: Uri? = null
    private var imageInputEnabled = true
    private var audioInputEnabled = true

    // 音声録音関連
    // NOTE: 上限は 30 秒。VoiceVox / whisper 系の後段処理と体験（ダイアログ表示時間）の
    // バランスに基づく上限で、UI と MediaRecorder.setMaxDuration の両方でこの値を参照する。
    private val MAX_RECORDING_DURATION_MS = 30_000
    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingAudio = false
    private var recordingAnimationJob: Job? = null
    private var recordingFile: java.io.File? = null
    private var recordingDialog: androidx.appcompat.app.AlertDialog? = null
    private var recordingStatusTextView: TextView? = null
    private var recordingWaveBars: List<View> = emptyList()
    // インライン録音バー専用のキャンセルフラグ。
    // cancelAudioRecording() から stopAudioRecording() を呼んだときだけ true になり、
    // 録音ファイルを selectedAudioUri に搭載せずに破棄する。
    private var discardRecordingOnStop = false
    private var embeddingDownloadDialog: androidx.appcompat.app.AlertDialog? = null
    private var embeddingDownloadProgressTextView: TextView? = null
    private var embeddingDownloadProgressBar: ProgressBar? = null


    // Phase 11: 複数画像選択
    //   PickMultipleVisualMedia(maxItems=5) を使って、ピッカーの段階で 6 枚以上選べないようにする
    //   (GetMultipleContents だと picker 側で選択枚数を制限できず、後段の take(5) で無音カットになっていた)
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTABLE_IMAGES)
    ) { uris ->
        if (!imageInputEnabled) {
            Toast.makeText(requireContext(), getString(R.string.multimodal_image_disabled), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (uris.isNotEmpty()) {
            // 保険で二重にキャップする
            val remaining = (MAX_SELECTABLE_IMAGES - selectedImageUrisList.size).coerceAtLeast(0)
            if (remaining <= 0) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.multimodal_image_max_reached, MAX_SELECTABLE_IMAGES),
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }
            val newUris = uris.take(remaining).map { it.toString() }
            selectedImageUrisList = (selectedImageUrisList + newUris).take(MAX_SELECTABLE_IMAGES)
            Toast.makeText(
                requireContext(),
                getString(R.string.multimodal_image_selected, newUris.size, selectedImageUrisList.size, MAX_SELECTABLE_IMAGES),
                Toast.LENGTH_SHORT
            ).show()
            updateMediaPreview()
        }
    }

    /**
     * 動画ピッカー (1本まで)。 30 秒 / 1fps でフレーム抽出し、音声トラックがあれば同時に取り出して
     * 既存の画像 + 音声パイプラインに流し込む。 Gemma 4 (LiteRT-LM 0.13.x Kotlin API) の
     * Content は Text/ImageBytes/ImageFile/AudioBytes/AudioFile のみで VideoBytes 相当はなく、
     * モデルカードも "process videos as frames" となっているため、
     * この展開によって間接的に "動画対応" させる。
     */
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!imageInputEnabled) {
            Toast.makeText(requireContext(), getString(R.string.multimodal_video_image_disabled), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        processPickedVideo(uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // #10 fix: full-size image captured via EXTRA_OUTPUT is already saved to cameraImageUri
            val savedUri = cameraImageUri
            if (savedUri != null) {
                if (selectedImageUrisList.size < 5) {
                    selectedImageUrisList = selectedImageUrisList + savedUri.toString()
                    Log.d("ChatFragment", "Camera image added (full size): $savedUri")
                    Toast.makeText(requireContext(), "Photo captured (${selectedImageUrisList.size}/5)", Toast.LENGTH_SHORT).show()
                    updateMediaPreview()
                } else {
                    Toast.makeText(requireContext(), "Max 5 images allowed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.multimodal_image_fetch_failed), Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d("ChatFragment", "Camera cancelled by user")
        }
    }

    private val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (!audioInputEnabled) {
            Toast.makeText(requireContext(), getString(R.string.multimodal_audio_disabled), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (uri != null) {
            selectedAudioUri = uri.toString()
            Toast.makeText(requireContext(), getString(R.string.multimodal_audio_selected), Toast.LENGTH_SHORT).show()
            updateMediaPreview()
        }
    }

    // アクションシート「ファイル」から呼ばれる汎用ファイルピッカー。
    //   - image  : 画像として selectedImageUrisList に追加
    //   - video  : Gemma 系のみ processPickedVideo() でフレーム抽出
    //   - audio  : selectedAudioUri に採用
    //   - その他 : 拡張子で最終判定。それでも判別できなければ Toast で通知
    // SAF を経由するので Google ドライブ等の外部プロバイダーからでも選択可能。
    // NOTE: Kotlin のブロックコメントはネスト可能なので、KDoc 内に `image/*` のような
    // `/*` を含む文字列を書くと新しいブロックコメント開始と見なされて
    // ファイル末尾までコメントになってしまう。ここは 行コメントにすること。
    // ドキュメント作成カード (convert_md_to_document) の「保存」ボタン用。
    //   ツール実行時点では実体ファイルは無く、カードの payload に載っている
    //   Markdown 本文だけがある。保存ボタンが押されたら pendingDocumentSaveRequest に
    //   Markdown/形式/ファイル名を保持して SAF の保存ダイアログを開き、
    //   保存先が決まった時点で初めて Markdown からの docx/pdf/xlsx 作成を行う。
    //   カード側のスピナーは onComplete コールバックで止める。
    private data class PendingDocumentSave(
        val markdown: String,
        val format: String,
        val fileName: String,
        val onComplete: (Boolean) -> Unit
    )
    private var pendingDocumentSaveRequest: PendingDocumentSave? = null

    private val documentSaverLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        val request = pendingDocumentSaveRequest
        pendingDocumentSaveRequest = null
        if (request == null) return@registerForActivityResult
        if (destUri == null) {
            // ユーザーが保存ダイアログをキャンセルした。スピナーだけ止める。
            request.onComplete(false)
            return@registerForActivityResult
        }
        val ctx = requireContext().applicationContext
        val format = when (request.format) {
            "docx" -> com.nezumi_ai.data.document.DocumentConversionManager.TargetFormat.DOCX
            "pdf" -> com.nezumi_ai.data.document.DocumentConversionManager.TargetFormat.PDF
            "xlsx" -> com.nezumi_ai.data.document.DocumentConversionManager.TargetFormat.XLSX
            else -> null
        }
        if (format == null) {
            request.onComplete(false)
            return@registerForActivityResult
        }
        // 変換 (Apache POI / PDFBox) は IO スレッドで行う。
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val result = com.nezumi_ai.data.document.DocumentConversionManager
                    .generateFromMarkdown(
                        context = ctx,
                        markdown = request.markdown,
                        format = format,
                        baseName = request.fileName.substringBeforeLast('.')
                    )
                if (!result.success || result.filePath.isNullOrBlank()) {
                    throw java.io.IOException(result.errorMessage ?: "conversion failed")
                }
                java.io.File(result.filePath).inputStream().use { input ->
                    ctx.contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                    } ?: throw java.io.IOException("openOutputStream returned null")
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to convert/save generated document: ${request.fileName}", e)
            }.isSuccess
            withContext(Dispatchers.Main) {
                request.onComplete(ok)
                context?.let {
                    Toast.makeText(
                        it,
                        getString(
                            if (ok) R.string.docgen_saved_toast else R.string.docgen_save_failed_toast
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val genericFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) { /* 一部プロバイダは permission grant 不能 */ }
        handlePickedGenericFile(uri)
    }

    // 権限リクエストランチャー
    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCameraInternal()
        } else {
            Toast.makeText(requireContext(), getString(R.string.perm_camera_required), Toast.LENGTH_SHORT).show()
        }
    }

    private val recordPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startAudioRecording()
        } else {
            Toast.makeText(requireContext(), getString(R.string.perm_microphone_required), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * ピッカーで選ばれた動画 URI をバックグラウンドで [VideoFrameExtractor] にかけ、
     *   - 先頭から 30 秒分を 1fps でフレーム化 (最大 30 枚)
     *   - 音声トラックがあれば mono PCM WAV へ変換
     * して、既存の selectedImageUrisList / selectedAudioUri に搭載する。
     * 注意: このフレーム列は画像 5 枚制限 (MAX_SELECTABLE_IMAGES) とは別建てで
     * カウントしているため、後段の take() を漏れないよう selectedImageUrisList には
     * クリア後に一括代入する。
     */
    private fun processPickedVideo(uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        isExtractingVideo = true
        renderSendButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    com.nezumi_ai.data.media.VideoFrameExtractor.extract(ctx, uri)
                }
                if (extracted == null || extracted.frames.isEmpty()) {
                    // コルーチン完了時にビューが破棄済みだと requireContext() が落ちるため
                    // context?.let でガードする (NPE クラッシュの修正)。
                    context?.let {
                        Toast.makeText(
                            it,
                            getString(R.string.multimodal_video_frame_extract_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                // フレームを PNG としてキャッシュに書き出し、 file:// URI にする
                val frameUris = withContext(Dispatchers.IO) {
                    val dir = java.io.File(ctx.cacheDir, "video_frames").apply { mkdirs() }
                    val runId = java.util.UUID.randomUUID().toString().take(8)
                    extracted.frames.mapIndexedNotNull { i, bmp ->
                        try {
                            val f = java.io.File(dir, "vf_${runId}_${i}.png")
                            java.io.FileOutputStream(f).use { out ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            }
                            android.net.Uri.fromFile(f).toString()
                        } catch (t: Throwable) {
                            Log.w("ChatFragment", "Failed to persist frame #$i", t)
                            null
                        } finally {
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    }
                }
                if (frameUris.isEmpty()) {
                    context?.let {
                        Toast.makeText(
                            it,
                            getString(R.string.multimodal_video_frame_save_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                // 既存の手動選択画像はクリアして、動画のフレームに入れ替える
                //   (画像 5 枚制限と 30 フレームの両方を一列に並べると 5 枚で切られてしまうため)
                selectedImageUrisList = frameUris
                selectedVideoUri = uri.toString()
                selectedVideoDurationMs = extracted.effectiveDurationMs
                selectedVideoFrameCount = frameUris.size
                if (audioInputEnabled) {
                    extracted.audioUriString?.let { selectedAudioUri = it }
                }
                updateMediaPreview()
            } finally {
                // 成功・失敗・早期returnのいずれでも必ずスピナーを止め、送信を再度可能にする。
                isExtractingVideo = false
                renderSendButtonState()
            }
        }
    }

    /**
     * ピッカーで選ばれたドキュメント (PDF/Word/Excel/PowerPoint) を、送信を待たず
     * この時点で Markdown に変換する。動画のフレーム抽出 (processPickedVideo) と
     * 同じ思想で、変換が完了するまで送信ボタンを無効化し、プレビューバーには
     * 変換中スピナーを表示する。
     *
     * 変換に成功すると、変換後の .md を message_media に保存した URI を持つ
     * TextFileEntry (isConvertedDocument=true) として添付一覧に載せる。
     * こうすることで、
     *   - 送信時のプロンプト埋め込みは既存の <txtfile> 経路 (buildTxtFilePromptBlocks)
     *     がそのまま .md を読むだけで済む
     *   - 添付チップ / 送信後カードのタップでビュワーから内容を確認できる
     * 変換に失敗した場合は添付せずトーストで通知する。
     */
    private fun processPickedDocument(uri: Uri, displayName: String) {
        val ctx = requireContext().applicationContext
        isConvertingDocument = true
        convertingDocumentName = displayName
        renderSendButtonState()
        updateMediaPreview()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    com.nezumi_ai.data.document.DocumentConversionManager
                        .extractMarkdownText(ctx, uri.toString())
                }
                if (!result.success || result.markdown.isNullOrBlank()) {
                    Log.w(
                        TAG,
                        "Document conversion failed for $displayName: " +
                            "${result.errorCode} / ${result.errorMessage}"
                    )
                    context?.let {
                        Toast.makeText(
                            it,
                            getString(R.string.docfile_convert_failed_toast, displayName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                // 変換結果を .md として永続化し、ビュワー表示と <txtfile> 埋め込みの
                // 両方で再利用できるようにする。name には元のファイル名 (拡張子付き) を
                // 残し、isConvertedDocument=true で「中身は .md」であることを示す。
                val mdUri = withContext(Dispatchers.IO) {
                    com.nezumi_ai.data.media.MessageMediaStore.persistTextContent(
                        ctx, result.markdown, displayName
                    )
                }
                if (mdUri == null) {
                    context?.let {
                        Toast.makeText(
                            it,
                            getString(R.string.docfile_convert_failed_toast, displayName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                val entry = com.nezumi_ai.data.media.TextFileAttachmentEncoding.TextFileEntry(
                    name = displayName,
                    uri = mdUri,
                    isConvertedDocument = true
                )
                if (selectedTextFiles.any { it.uri == entry.uri }) {
                    context?.let {
                        Toast.makeText(it, getString(R.string.attachment_already_added), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                selectedTextFiles = selectedTextFiles + entry
                context?.let {
                    Toast.makeText(
                        it,
                        getString(R.string.txtfile_added_toast, displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                // 成功・失敗・早期returnのいずれでも必ずスピナーを止め、送信を再度可能にする。
                isConvertingDocument = false
                convertingDocumentName = null
                renderSendButtonState()
                updateMediaPreview()
            }
        }
    }

    /**
     * Gemma 4 (LiteRT-LM) 向けのプロンプト整形。
     * 動画を送信するときだけ、ユーザー本文の先頭に英語の <video> ブロックとして
     *   <video>
     *   Audio track (0s to Ns) is attached directly to the model.
     *   Video frames sampled at 1 fps, M frames in total:
     *   img_<uuid>.jpg: 0s
     *   img_<uuid>.jpg: 1s
     *   ...
     *   </video>
     * を差し込む。
     *
     * フレームの識別子は、モデルに実際に添付される画像の basename
     * (MessageMediaStoreに persist 済みの img_<uuid>.jpg) をそのまま使う。
     * モデルが内部で参照するファイル名とプロンプト内の識別子を同一化することで、
     * 「img_c16a2de9-...の2枚目と img_8f...の3枚目の違いは…」のようなクエリも
     * 不自然なマッピングなしに成立する。
     */
    private fun buildVideoAwarePrompt(
        userText: String,
        frameUris: List<String>,
        durationMs: Long,
        hasAudio: Boolean
    ): String {
        // 動画メタ情報は <video>...</video> で囲んでモデルに渡す。
        //   - 日本語ヘッダ (【音声…】【動画フレーム一覧…】) だと Gemma 4 が日本語説明文に
        //     引っ張られて英語質問への回答も日本語化しがちだったため、英語の記述に変更。
        //   - UI の吹き出し・セッションタイトルには出さない
        //     (stripVideoBlocks で除去 / buildSessionTitle 側でも除外)。
        val frameCount = frameUris.size
        val sec = (durationMs / 1000L).coerceAtLeast(1L)
        val sb = StringBuilder()
        sb.append("<video>")
        if (hasAudio) {
            sb.append("Audio track (0s to ${sec}s) is attached directly to the model.\n")
        }
        sb.append("Video frames sampled at 1 fps, ${frameCount} frames in total:\n")
        for (i in 0 until frameCount) {
            val name = extractDisplayName(frameUris[i], fallback = "frame_${i + 1}")
            sb.append("$name: ${i}s\n")
        }
        sb.append("</video>\n")
        sb.append(userText)
        return sb.toString()
    }

    /**
     * URI 文字列からモデルに見せるファイル名 (basename) を取り出す。
     * - content:// の場合は ContentResolver の OpenableColumns.DISPLAY_NAME を優先
     * - file:// やパス付き URI なら lastPathSegment
     * - どちらも失敗したら fallback
     */
    private fun extractDisplayName(uriString: String, fallback: String): String {
        return try {
            val uri = android.net.Uri.parse(uriString)
            if (uri.scheme == "content") {
                val ctx = requireContext()
                ctx.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() } else null
                    } else null
                } ?: run {
                    // DISPLAY_NAME を返さないプロバイダ向けのフォールバック。
                    //   lastPathSegment は URL エンコードされた "primary:Download/cat.md"
                    //   のような形をしていることがあるので、デコードして basename を推定する。
                    uri.lastPathSegment
                        ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull() ?: it }
                        ?.substringAfterLast('/')
                        ?.substringAfterLast(':')
                        ?.takeIf { it.isNotBlank() }
                        ?: fallback
                }
            } else {
                uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallback
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun updateMediaPreview() {
        // Phase 11: 複数画像対応
        if (selectedImageUrisList.isEmpty() && selectedAudioUri.isNullOrEmpty() &&
            selectedVideoUri.isNullOrEmpty() && selectedTextFiles.isEmpty()) {
            viewModel.clearPendingMediaPreview()
            return
        }

        // チャット欄への空メッセージ表示は不要（MediaPreviewBar で十分）
        // 画像と音声のプレビューは MediaPreviewBar（Compose）で入力欄上に直接表示
    }

    // createMediaPreviewItem メソッドは削除されました（プレビュー機能廃止）

    // removeMedia メソッドは削除されました（プレビュー機能廃止）


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyStatusBarInset()
        responseTypingText = getString(R.string.response_generating)
        modelLoadingText = getString(R.string.model_loading)
        contextMeterText = getString(R.string.context_meter_format, 0, 0)
 // 初期値として全般タブのコンテキストメーター表示フラグを反映。
        contextMeterVisible = PreferencesHelper.isShowContextMeter(requireContext())
        compressButtonText = ""
        thinkingToggleText = getString(R.string.chat_thinking_follow_settings)
        setupComposeIndicators()

        // ViewModel初期化: DBアクセスをIOスレッドで実行してメインスレッドのブロックを防ぐ
        val appContext = requireContext().applicationContext
        val database = NezumiAiDatabase.getInstance(appContext)
        settingsRepository = SettingsRepository.fromDatabase(database)
        val sessionRepository = ChatSessionRepository(database.chatSessionDao(), settingsRepository)
        val messageRepository = MessageRepository(database.messageDao())
        presetRepository = PresetRepository(database.presetDao(), appContext)
        val memoryRepository = MemoryRepository(database.memoryDao())
 // 起動直後フリーズ対策: SharedPreferences の初回読み込みはディスク I/O を
        //   ブロックするため、UI スレッドではなく IO スレッドに逃がす。
        //   lastObservedPresetId は onResume() でのプリセット変更検知に使われるが、
        //   起動直後は変更検知が発火しないため null 初期値でも支障なく、
        //   IO で読み終わり次第 UI スレッドに書き戻す。
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val presetId = PreferencesHelper.getCurrentPresetId(appContext)
            withContext(Dispatchers.Main) {
                if (lastObservedPresetId == null) {
                    lastObservedPresetId = presetId
                }
            }
        }
        val factory = ChatViewModelFactory(
            appContext,
            sessionRepository,
            messageRepository,
            settingsRepository,
            presetRepository,
            memoryRepository
        )
        viewModel = ViewModelProvider(requireActivity(), factory).get(ChatViewModel::class.java)

        // デフォルトプリセット「ネズミAI」が指すモデル (Gemma4-2B) が未ダウンロードの
        // ときだけ、モデル選択モーダルを出す。利用可能なモデルが 1 つも無い場合は
        // 「モデル管理画面でダウンロードまたはクラウドモデルを追加してください」と案内する。
        viewLifecycleOwner.lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) {
                presetRepository.isDefaultPresetModelAvailable()
            }
            if (!available) {
                val first = withContext(Dispatchers.IO) {
                    com.nezumi_ai.data.preset.PresetModelCatalog
                        .downloadedModels(appContext)
                        .firstOrNull()
                }
                showDefaultModelMissingDialog(first?.label)
            } else {
                // バグ修正: モデル削除によって「現在選択中のプリセット」が
                // モデル未選択状態になっているケースをここでも検知する。
                // (デフォルトプリセット自体は正常でも、ユーザーが選んだ別プリセットが
                //  孤児化している場合があるため、isDefaultPresetModelAvailable とは別チェック)
                checkAndShowPresetModelUnselectedDialog()
            }
        }

        binding.chatTitle.setOnClickListener {
            findNavController().navigate(R.id.presetSettingsFragment)
        }
        setupModelDisplay()

        // 保存しておいたデフォルトの入力ヒントを保持（null で上書きしないようにするため）
        defaultInputHint = binding.messageInput.hint

        // RecyclerView設定（adapterの初期化をStateFlowのcollect前に移動）
        adapter = MessageAdapter(
            // Bug fix(#Edit-Instead-Of-Revoke): 元は「取り消し」(対象メッセージ以降を
            //   削除するだけ)だったが、ペンアイコンに変更したのに合わせて「編集」に
            //   した。編集の中身は次の3ステップ:
            //     1) メッセージのテキスト本文 (内部タグ除去済み) を入力欄に戻す
            //     2) 画像/動画/音声/テキストファイル添付も、送信前と同じプレビュー
            //        state (selectedImageUrisList など) に復元する
            //     3) 対象メッセージ以降を DB から削除する (revokePromptFromMessage を流用)
            //   ステップの順序が重要: 削除は非同期 (IO スレッド) なので、UI 側の
            //   state 復元を先に同期的に済ませてから削除を投げる。
            onUserPromptEdit = { message ->
                val (videoMeta, imageUrisRaw) = VideoAttachmentEncoding.split(message.imageUri)
                val textFiles = TextFileAttachmentEncoding.extract(message.imageUri)
                val imageUris = imageUrisRaw.filter { !TextFileAttachmentEncoding.isMarker(it) }

                binding.messageInput.setText(
                    message.content.stripTxtFileBlocks().stripVideoBlocks()
                )
                binding.messageInput.setSelection(binding.messageInput.text?.length ?: 0)

                selectedImageUrisList = imageUris
                selectedTextFiles = textFiles
                selectedVideoUri = videoMeta?.originalVideoUri
                selectedVideoDurationMs = videoMeta?.durationMs ?: 0L
                // フレーム数は動画メタに保持していないため、復元した imageUris の件数を
                // そのまま使う（動画由来でなければ 0 のまま）。
                selectedVideoFrameCount = if (videoMeta != null) imageUris.size else 0
                selectedAudioUri = videoMeta?.audioUri ?: message.audioUri

                binding.messageInput.requestFocus()
                context?.let { ctx ->
                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(binding.messageInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }

                viewModel.revokePromptFromMessage(message.id, isEditing = true)
            },
            onAiMessageLayoutChanged = {
                if (shouldAutoFollowBottom()) {
                    scheduleAutoScrollToBottom()
                }
 // 再生成後の animateContentSize 完了を拾って一回だけ下端までジャンプする。
                if (scrollToBottomOnNextAiLayout) {
                    scrollToBottomOnNextAiLayout = false
                    autoFollowBottomLocked = true
                    scrollToBottomImmediate()
                }
            },
            onAiMessageSpeak = { message, generatedText ->
                viewModel.synthesizeText(message.id, generatedText)
            },
            onAiMessageRegenerate = { message ->
 // 再生成タップの直後は必ず末尾追従をリセットしておく。
                userScrolledAwayDuringGeneration = false
                autoFollowBottomLocked = true
                postGenerationSettleActive = false
 // animateContentSize の完了コールバックで下端までジャンプさせるフラグを立てる。
                scrollToBottomOnNextAiLayout = true
                viewModel.regenerateLastResponse(message.id)
            },
            onAiVariantSelect = { parentId, newIndex ->
                viewModel.selectAssistantVariant(parentId, newIndex)
            },
            lifecycleOwner = viewLifecycleOwner,
            viewModelStoreOwner = this,
            // 生成ドキュメント (docx/pdf/xlsx) の「保存」ボタン。
            //   カード側からファイル名と内部 URI を受け取り、SAF の保存ダイアログを開く。
            //   ランチャーの結果コールバックに対象 URI を引き渡すため pending に保持する
            //   (複数カードがあっても「最後に押されたカード」のファイルだけが対象になる)。
            onSaveGeneratedDocument = { markdown, format, fileName, onComplete ->
                pendingDocumentSaveRequest = PendingDocumentSave(markdown, format, fileName, onComplete)
                runCatching {
                    documentSaverLauncher.launch(fileName)
                }.onFailure { e ->
                    Log.e(TAG, "Error launching document saver", e)
                    pendingDocumentSaveRequest = null
                    onComplete(false)
                    context?.let {
                        Toast.makeText(
                            it,
                            getString(R.string.docgen_save_failed_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
        binding.messagesRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = false
        }
        binding.messagesRecyclerView.adapter = adapter
        binding.messagesRecyclerView.itemAnimator = null

 // バグ修正: RecyclerView のスクロール状態をリアルタイム監視
        binding.messagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        userIsDraggingMessages = true
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        userIsDraggingMessages = false
                        // ユーザーが下端付近まで戻ったら自動追従を再開する。
                        if (isGenerating && isNearBottom(recyclerView)) {
                            autoFollowBottomLocked = true
                            userScrolledAwayDuringGeneration = false
                            scheduleAutoScrollToBottom()
                            Log.d(TAG, "USER_SCROLL_BACK_TO_BOTTOM: Re-enabling auto-follow")
                        }
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                isUserAtBottom = !recyclerView.canScrollVertically(1)
                // 生成中に「上へ」ドラッグして下端から離れたときだけ auto-follow を解除する。
                if (isGenerating && userIsDraggingMessages && dy < 0 && !isNearBottom(recyclerView)) {
                    autoFollowBottomLocked = false
                    userScrolledAwayDuringGeneration = true
                }
                // 生成完了直後の settle フェーズ中でも、ユーザーが明示的に上へドラッグしたら settle を中断する。
                if (postGenerationSettleActive && userIsDraggingMessages && dy < 0 && !isNearBottom(recyclerView)) {
                    postGenerationSettleActive = false
                    autoFollowBottomLocked = false
                }
                updateScrollToBottomButtonVisibility()
            }
        })

        // AdapterDataObserverを一度だけ登録（毎回登録するとメモリリーク）
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            private fun maybeScrollToBottom() {
                if (shouldAutoFollowBottom()) {
                    scheduleAutoScrollToBottom()
                }
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = maybeScrollToBottom()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = maybeScrollToBottom()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = maybeScrollToBottom()
        })

        // nav args から incognito フラグを適用
        viewModel.setIncognitoMode(args.isIncognito)

        // Observe incognito mode and apply security settings
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isIncognitoMode.collect { isIncognito ->
                applyIncognitoModeSettings(isIncognito)
                updateIncognitoModeIndicator(isIncognito)
            }
        }


        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.getSettings().collect { settings ->
                contextCompressionEnabled = settings?.contextCompressionEnabled == true && BuildConfig.CONTEXT_COMPRESSION_ENABLED
 // Thinking 表示はアダプタ側で「常時表示」に固定済み。
                //   そのため adapter.setThinkingVisible(true) の呼び出しは不要になり、完全に廃止された。
                updateThinkingToggleVisibility()
                renderCompressButtonState()
            }
        }

        // セッションID取得（Navigation argsから、またはSettingsから）
        val sessionId = args.sessionId
        if (sessionId <= 0) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val savedSessionId = settingsRepository.loadCurrentSessionId()
                    if (savedSessionId > 0) {
                        val savedSession = sessionRepository.getSessionById(savedSessionId)
                        if (savedSession != null && !savedSession.isIncognito) {
                            Log.d("ChatFragment", "Restoring previous session: $savedSessionId")
                            val prefs = requireContext().getSharedPreferences("nezumi_ai_prefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putLong("current_session_id", savedSessionId).apply()
 // setCurrentSession は suspend 関数に変更されたため、直接 await する
                            viewModel.setCurrentSession(savedSessionId)
                        } else {
                            Log.d("ChatFragment", "Saved session is unavailable or incognito. Creating new session.")
                            val newSessionId = sessionRepository.createSession(getString(R.string.chat_new_session_title))
                            settingsRepository.saveCurrentSessionId(newSessionId)
                            val prefs = requireContext().getSharedPreferences("nezumi_ai_prefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putLong("current_session_id", newSessionId).apply()
                            viewModel.setCurrentSession(newSessionId)
                        }
                    } else {
                        Log.d("ChatFragment", "No saved session found. Creating new session.")
                        val newSessionId = sessionRepository.createSession(getString(R.string.chat_new_session_title))
                        settingsRepository.saveCurrentSessionId(newSessionId)
                        val prefs = requireContext().getSharedPreferences("nezumi_ai_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putLong("current_session_id", newSessionId).apply()
 // setCurrentSession は suspend 関数に変更されたため、直接 await する
                        viewModel.setCurrentSession(newSessionId)
                    }
                } catch (e: Exception) {
                    Log.e("ChatFragment", "Failed to handle session", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.chat_session_dispatch_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (!args.isIncognito) {
                        settingsRepository.saveCurrentSessionId(sessionId)
                        // SharedPreferencesにも保存（SessionListFragmentで参照）
                        val prefs = requireContext().getSharedPreferences("nezumi_ai_prefs", android.content.Context.MODE_PRIVATE)
                        prefs.edit().putLong("current_session_id", sessionId).apply()
                    }
 // setCurrentSession は suspend 関数に変更されたため、直接 await する
                    viewModel.setCurrentSession(sessionId)

                    // 検索結果からのジャンプ: scrollToMessageId が指定されている場合
                    val scrollToId = arguments?.getLong("scrollToMessageId", -1L) ?: -1L
                    if (scrollToId > 0L) {
                        withContext(Dispatchers.Main) {
                            scrollToMessageId(scrollToId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatFragment", "Failed to save session", e)
                }
            }
        }

        currentToolCallState = null

        binding.backButton.setOnClickListener {
            (activity as? com.nezumi_ai.MainActivity)?.openDrawer()
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.messagesRecyclerView,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                private var wasAtBottom = false

                override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    wasAtBottom = isAtBottom()
                }

                override fun onProgress(
                    insets: androidx.core.view.WindowInsetsCompat,
                    runningAnimations: List<androidx.core.view.WindowInsetsAnimationCompat>
                ): androidx.core.view.WindowInsetsCompat {
                    return insets
                }

                override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    if (wasAtBottom && isUserAtBottom) {
                        scrollToBottomImmediate()
                    }
                }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentSessionId.collect {
                pendingInitialScrollToBottom = true
                userScrolledAwayDuringGeneration = false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.messages,
                viewModel.pendingMediaMessage
            ) { messages, pendingMedia ->
                if (pendingMedia != null) messages + listOf(pendingMedia) else messages
            }.collect { displayMessages ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "ChatFragment",
                        "DISPLAY_MESSAGES: count=${displayMessages.size} messages=${displayMessages.map { "${it.role}:${it.content}" }}"
                    )
                }
                // インライン tool-call カード表示のため、UI 側に流す content では
                // <tool_call>...</tool_call> タグを保持したまま sanitize する。
                // タグ位置は MessageAdapter → InlineToolCallMessageBody でセグメント化され、
                // カードを差し込む目印として使われる。コピー・読み上げなどは
                // 引き続きタグなしパス (stripGemmaTokens() default = false) を使う。
                val filteredMessages = displayMessages.map { msg ->
                    msg.copy(
                        content = msg.content
                            .stripGemmaTokens(preserveToolCallTags = true)
                            .stripTxtFileBlocks()
                            .stripVideoBlocks()
                    )
                }
                val empty = filteredMessages.isEmpty()
                messagesIsEmpty = empty
                // ローディング中はエンプティ表示を出さない (スピナーの背後で一瞬チラつくのを防ぐ)
                binding.emptyStateCompose.visibility =
                    if (empty && !viewModel.isMessagesLoading.value) View.VISIBLE else View.GONE
                adapter.submitList(filteredMessages) {
                    if (pendingInitialScrollToBottom && filteredMessages.isNotEmpty()) {
                        pendingInitialScrollToBottom = false
                        binding.messagesRecyclerView.post {
                            if (_binding != null && isAdded) scrollToBottomImmediate()
                        }
                    } else if (shouldAutoFollowBottom()) {
                        scheduleAutoScrollToBottom()
                    }
                    updateScrollToBottomButtonVisibility()
                }
            }
        }

        binding.sendButton.setOnClickListener {
            if (viewModel.isLoading.value) {
                viewModel.stopGeneration()
                return@setOnClickListener
            }
            // isEnabled 制御の取りこぼし対策。動画のフレーム/音声抽出や
            // ドキュメントの Markdown 変換がまだ終わっていない間は
            // 送信できる添付物が確定していないため、ここでも明示的にブロックする。
            if (isExtractingVideo || isConvertingDocument) {
                return@setOnClickListener
            }
            val message = binding.messageInput.text.toString().trim()
            val hasMediaToSend = (imageInputEnabled && selectedImageUrisList.isNotEmpty()) ||
                (audioInputEnabled && !selectedAudioUri.isNullOrEmpty()) ||
                selectedTextFiles.isNotEmpty()
            // 従来はテキストが空だと送信ボタンが完全に無反応だった。
            // 画像や音声(動画から抽出した音声を含む)だけを送りたいケース
            // (「これ何？」すら打たずに音声/画像だけ送る) が弾かれてしまっていたため、
            // メディアが1つでも添付されていればテキスト空でも送信できるようにする。
            if (message.isNotEmpty() || hasMediaToSend) {
                // ドキュメント添付 (PDF/Word/Excel等) の Markdown 変換が suspend 関数のため、
                // 送信処理全体をコルーチンで実行する。変換は IO スレッドで行い、
                // 失敗時のトーストと sendMessageWithMedia は Main に戻して呼ぶ。
                viewLifecycleOwner.lifecycleScope.launch {
                    // Fragment が変換中に detach される可能性があるため、
                    // コルーチン冒頭で applicationContext をキャプチャして使い回す。
                    val appCtx = requireContext().applicationContext
                    val imagesToSend = if (imageInputEnabled) selectedImageUrisList else emptyList()
                    val audioToSend = if (audioInputEnabled) selectedAudioUri else null
                    val videoUriToSend = selectedVideoUri
                    // Gemma 4 向けのプロンプト整形: 動画を送っているときだけ先頭に
                    // 英語の <video> ブロック (音声メタ / フレーム一覧) を差し込む。
                    // フレームの basename をプロンプトに埋め込むので、モデルが実際に受け取る
                    // img_<uuid>.jpg と一致させるためここで先行 persist して確定ファイル名を得る。
                    val imagesPersisted = if (videoUriToSend != null && imagesToSend.isNotEmpty()) {
                        imagesToSend.map { uriStr ->
                            com.nezumi_ai.data.media.MessageMediaStore.persistUriIfNeeded(
                                appCtx, uriStr
                            ) ?: uriStr
                        }
                    } else imagesToSend
                    // 動画本体もセッション削除時に一緒に掃除できるよう message_media にコピーしておく。
                    // 以前はピッカーが返した content:// をそのまま DB に入れていたため、
                    // セッションごと削除しても外部ストレージ側の動画は残り続けていた。
                    val videoPersisted = if (videoUriToSend != null) {
                        com.nezumi_ai.data.media.MessageMediaStore.persistVideoUriIfNeeded(
                            appCtx, videoUriToSend
                        ) ?: videoUriToSend
                    } else null
                    // テキストファイル添付: 実体を message_media に永続化した上で、
                    // 内容を <txtfile>{name:"...",body:"..."}</txtfile> としてプロンプト先頭に挿入する。
                    // このタグはモデル向けの情報であり、UI の吹き出しには表示しない
                    // (stripTxtFileBlocks で除去する)。
                    val persistedTextFiles = selectedTextFiles.mapNotNull { entry ->
                        val persisted = com.nezumi_ai.data.media.MessageMediaStore.persistTextFileIfNeeded(
                            appCtx, entry.uri, entry.name
                        )
                        if (persisted != null) entry.copy(uri = persisted) else null
                    }
                    // ドキュメント添付 (PDF/Word/Excel/PowerPoint) はピック時点で
                    // Markdown に変換済み (processPickedDocument) で、uri は変換後の
                    // .md ファイルを指している。プレーンテキスト添付と同じく
                    // buildTxtFilePromptBlocks が <txtfile>{name,body} を組み立てるだけでよい。
                    val attachmentPromptPrefix =
                        com.nezumi_ai.data.media.MessageMediaStore.buildTxtFilePromptBlocks(
                            persistedTextFiles, appCtx
                        )
                    val messageWithTextFiles = if (attachmentPromptPrefix.isNotEmpty()) {
                        attachmentPromptPrefix + message
                    } else {
                        message
                    }
                    val effectiveMessage = if (videoPersisted != null && imagesPersisted.isNotEmpty()) {
                        buildVideoAwarePrompt(
                            userText = messageWithTextFiles,
                            frameUris = imagesPersisted,
                            durationMs = selectedVideoDurationMs,
                            hasAudio = audioToSend != null
                        )
                    } else {
                        messageWithTextFiles
                    }
                    // 元動画 URI + 音声 URI + 長さ を "フレーム列の先頭にマーカーとして差し込む"
                    // ことで DB スキーマを変えずに後から MediaViewerDialog で展開できるようにする。
                    // テキスト添付は nezumi://txtfile マーカーとして imageUri 列に載せる。
                    //   DB スキーマを変えずに「このメッセージに添付されたテキストファイル一覧」を
                    //   復元できるようにするため (VideoAttachmentEncoding と同じ思想)。
                    val textFileMarkers = persistedTextFiles.map {
                        com.nezumi_ai.data.media.TextFileAttachmentEncoding.encode(it)
                    }
                    val imagesFinal = if (videoPersisted != null && imagesPersisted.isNotEmpty()) {
                        listOf(
                            com.nezumi_ai.data.media.VideoAttachmentEncoding.encode(
                                com.nezumi_ai.data.media.VideoAttachmentEncoding.Meta(
                                    originalVideoUri = videoPersisted,
                                    audioUri = audioToSend,
                                    durationMs = selectedVideoDurationMs
                                )
                            )
                        ) + imagesPersisted + textFileMarkers
                    } else {
                        imagesPersisted + textFileMarkers
                    }
                    userScrolledAwayDuringGeneration = false
                    autoFollowBottomLocked = true
                    postGenerationSettleActive = false
                    viewModel.sendMessageWithMedia(effectiveMessage, imagesFinal, audioToSend)
                    binding.messageInput.text?.clear()
                    selectedImageUrisList = emptyList()
                    selectedAudioUri = null
                    selectedVideoUri = null
                    selectedVideoDurationMs = 0L
                    selectedVideoFrameCount = 0
                    selectedTextFiles = emptyList()
                    updateMediaPreview()
                }
            }
        }

        // OS の「貼り付け」経由で画像が渡ってきた時のフック。
        // ClipboardAwareEditText 側で URI ペイロードを検知して呼ばれる。
        binding.messageInput.onClipboardImagePaste = {
            pasteFromClipboard()
        }

        // + ボタン: 画像 / カメラ / ファイル (画像・動画・音声) を選ぶアクションシート
        binding.mediaMenuButton.setOnClickListener { view ->
            // テキストファイル添付はモデルのマルチモーダル対応に依らず常に利用可能なため、
            // 画像/音声が非対応のモデルでもアクションシート自体は開ける。
            // (個別タイル側でそれぞれ imageInputEnabled / audioInputEnabled を見てガードする)
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            showAttachmentActionSheet()
        }

        // マイクボタン: 音声録音 (インライン録音バーに切り替え)
        binding.micButton.setOnClickListener {
            if (isRecordingAudio) {
                stopAudioRecording()
            } else {
                launchAudioRecording()
            }
        }

        // インライン録音バーの「削除」ボタン: 録音を破棄して入力欄に戻す
        binding.inlineRecordCancel.setOnClickListener {
            cancelAudioRecording()
        }

 // バリアント切り替えスクロール要求を受け取るコレクター。
        //   ChatViewModel.selectAssistantVariant() から切り替え先メッセージ id が流れてくるので、
        //   そのメッセージの "一番下" をビューポート下側に合わせる。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scrollToVariantMessageId.collect { messageId ->
                    scrollVariantMessageBottomIntoView(messageId)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    val wasGenerating = isGenerating
                    isGenerating = isLoading
                    // ユーザーメッセージの取り消しボタンを生成中は隠す。
                    // Bug fix: 生成中に取り消しして KV キャッシュと不整合を起こさないため。
                    adapter.setIsGenerating(isLoading)
                    renderSendButtonState()
                    if (isLoading) {
                        userScrolledAwayDuringGeneration = false
                        postGenerationSettleActive = false
                        autoFollowBottomLocked = isUserAtBottom || isNearBottom()
                        startResponseTypingAnimation()
                        if (autoFollowBottomLocked) {
                            binding.messagesRecyclerView.post {
                                val lastItem = adapter.itemCount - 1
                                if (lastItem >= 0) scrollToBottom(lastItem)
                            }
                        }
                    } else {
                        stopResponseTypingAnimation()
                        responseTypingVisible = false
                        responseTypingText = getString(R.string.response_generating)
 // Scroll bug fix: 生成完了の瞬間に、TPS 表示や各アクションボタン（再生成・スピーク）が
                        //   登場してアイテムの高さが一度局所的に変わる。この後の onItemRangeChanged は
                        //   isGenerating==false だがゆえに shouldAutoFollowBottom() は false を返し、
                        //   自動追従がかからないまま RecyclerView のレイアウト内部リセットで一番上に飛んでしまう
                        //   キャリブレーションが見られていた。
                        //   直前まで末尾追従していた場合に限り、複数フレームにわたって末尾に強制着地させることで
                        //   このジャンプを防ぐ。ユーザーが生成中に上にスクロールして見ていた場合はその位置を尊重する。
                        //   さらに: 単発の post {} だけだと、TPS/アクションボタンが遅延して登場したり、
                        //   Markdown/コードブロックの再レイアウトが後続フレームで走った場合に、その時点では
                        //   すでに isGenerating==false かつ shouldAutoFollowBottom()==false なので
                        //   一番上にジャンプしてしまう。 finishingSettleFrames の間だけ末尾追従を強制する。
                        if (wasGenerating && !userScrolledAwayDuringGeneration && autoFollowBottomLocked) {
                            postGenerationSettleActive = true
                            binding.messagesRecyclerView.post {
                                if (_binding != null && isAdded) scrollToBottomImmediate()
                            }
                            binding.messagesRecyclerView.postDelayed({
                                if (_binding != null && isAdded) scrollToBottomImmediate()
                            }, 32L)
                            binding.messagesRecyclerView.postDelayed({
                                if (_binding != null && isAdded) scrollToBottomImmediate()
                            }, 96L)
                            binding.messagesRecyclerView.postDelayed({
                                if (_binding != null && isAdded) {
                                    scrollToBottomImmediate()
                                    postGenerationSettleActive = false
                                    updateScrollToBottomButtonVisibility()
                                }
                            }, 240L)
                        } else {
                            postGenerationSettleActive = false
                            updateScrollToBottomButtonVisibility()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            if (com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
                viewModel.speakingMessageId.collect { messageId ->
                    adapter.setSpeakingMessageId(messageId)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chatSessionDisableThinking.collect { disabled ->
                thinkingToggleChecked = disabled
                thinkingToggleText = if (disabled) {
                    getString(R.string.chat_thinking_off_for_session)
                } else {
                    getString(R.string.chat_thinking_follow_settings)
                }
            }
        }

        // #9 fix: merge duplicate isCompressing collects into one combine block to prevent double animation
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.isCompressing,
                viewModel.isEmbeddingDownloadInProgress
            ) { compressing, downloading -> Pair(compressing, downloading) }
            .collect { (compressing, downloading) ->
                isCompressingNow = compressing
                // 抽出中・埋め込みダウンロード中は入力を無効化しないが、ダウンロード中は送信を防ぐ
                binding.messageInput.isEnabled = !compressing && !downloading
                binding.sendButton.isEnabled =
                    isGenerating || (!compressing && !downloading && !isModelLoadingNow)
                renderCompressButtonState()
                renderSendButtonState()
                if (isGenerating) {
                    responseTypingText = when {
                        compressing -> ""
                        downloading -> getString(R.string.embedding_download_progress_message)
                        else -> getString(R.string.response_generating)
                    }
                }
                if (compressing || downloading) startResponseTypingAnimation() else stopResponseTypingAnimation()
            }
        }

        // Phase 3: メモリ抽出中インジケーター
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isExtracting.collect { extracting ->
                // 入力欄上部に「⟳ 記憶を整理中...」を表示
                // （既存の compressingHintText または inputHint を流用）
                if (extracting) {
                    binding.messageInput.hint = getString(R.string.response_extracting)
                } else {
                    // デフォルトのヒントを復元（null をセットすると完全に消えるため）
                    binding.messageInput.hint = defaultInputHint
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isEmbeddingDownloadInProgress.collect { downloading ->
                if (downloading) {
                    showEmbeddingDownloadDialog()
                } else {
                    dismissEmbeddingDownloadDialog()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.embeddingDownloadProgress.collect { progress ->
                updateEmbeddingDownloadDialog(progress)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedModel.collect { model ->
                currentModelKey = model
                refreshCurrentBackendType()
                updateMediaAvailability(model)
                updateThinkingToggleVisibility()
                updateModelNameText(model)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sessionTitle.collect { title ->
                binding.chatTitle.contentDescription = title
                refreshPresetHeader()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            presetRepository.observePresets().collect {
                refreshPresetHeader()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiMessage.collect { message ->
                // ツール実行入口の長めメッセージは LONG で見せる。日本語/英語両方のプレフィックスを判定。
                val duration = if (message.startsWith("実行ツール") || message.startsWith("Running ") || message.startsWith("Tool ")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(requireContext(), message, duration).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.navigationEvent.collect { event ->
                when (event) {
                    ChatViewModel.NavigationEvent.BACK_TO_HOME -> {
                        Log.i("ChatFragment", "Memory shortage detected - opening drawer instead of navigating home")
                        (activity as? com.nezumi_ai.MainActivity)?.openDrawer()
                    }
                    ChatViewModel.NavigationEvent.CLEAR_CHAT -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.contextUsageChars,
                viewModel.contextWindowSize
            ) { usedChars, maxTokens ->
                Pair(usedChars, maxTokens)
            }.collect { (usedChars, maxTokens) ->
                val usedTokens = ((usedChars + 3) / 4).coerceAtLeast(0)
                val safeMaxTokens = maxTokens.coerceAtLeast(1)
                contextUsageCharsNow = usedChars
                contextMeterText = getString(R.string.context_meter_format, usedTokens, safeMaxTokens)
                contextMeterProgress =
                    (((usedTokens.toLong() * 1000L) / safeMaxTokens.toLong()).toInt().coerceIn(0, 1000) / 1000f)
                renderCompressButtonState()
            }
        }

        // チャット履歴読み込み中スピナー: 履歴が積まれたセッションでは最初の
        // メッセージ表示まで時間がかかるため、その間は中央のぐるぐるを表示する。
        // 読み込み中はエンプティ表示も隠す (読み込み完了後のメッセージ収集側で再評価される)。
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isMessagesLoading.collect { loading ->
                if (_binding == null) return@collect
                binding.messagesLoadingIndicator.visibility =
                    if (loading) View.VISIBLE else View.GONE
                if (loading) {
                    binding.emptyStateCompose.visibility = View.GONE
                } else if (messagesIsEmpty) {
                    binding.emptyStateCompose.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isModelLoading.collect { loading ->
                isModelLoadingNow = loading
                modelLoadingOverlayVisible = loading
                // 全画面 ComposeView は非表示時もヒットテストに乗るため、GONE でタッチを下層へ通す
                binding.modelLoadingComposeOverlay.visibility =
                    if (loading) View.VISIBLE else View.GONE
                binding.backButton.isEnabled = !loading
                renderSendButtonState()
                renderCompressButtonState()
                binding.messageInput.isEnabled = !loading
                if (loading) {
                    requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.toolCallState.collect { state ->
                currentToolCallState = state
                // 入力欄上のステータスバーは画像生成ツール専用
                binding.toolCallProgressCompose.visibility =
                    if (isImageGenerationToolState(state)) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.messages, viewModel.toolCallState) { messages, toolState ->
                val streamingId = messages.lastOrNull { it.isStreaming && it.role == "assistant" }?.id
                streamingId to toolState
            }.collect { (streamingId, toolState) ->
                adapter.setStreamingToolCallState(streamingId, toolState)
            }
        }

 // 応答バリアント情報を Adapter に流し込む。parent ごとに (全バリアント件数, 現在選択中の index)。
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.variantInfoByParent.collect { info ->
                adapter.setVariantInfo(info)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.imageGenProgress.collect { progress ->
                currentImageGenProgress = progress
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.memoryError.collect { error ->
                if (error != null) showMemoryErrorDialog(error)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gpuBackendFallback.collect { info ->
                if (info != null) showGpuBackendFallbackDialog(info)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.memoryWarning.collect { warning ->
                if (warning != null) showMemoryWarningDialog(warning)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.cpuCompatibilityWarning.collect { warning ->
                if (warning != null) showCpuCompatibilityWarningDialog(warning)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.modelErrorDialogMessage.collect { message ->
                if (!message.isNullOrBlank()) showModelErrorDialog(message)
            }
        }

        // isCompressing handling merged into combine block above

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isChatReady.collect { isReady ->
                binding.messageInput.isEnabled = isReady
                binding.sendButton.isEnabled = isReady
                renderSendButtonState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.modelLoadingStatus.collect { status ->
                if (status.isNotEmpty()) modelLoadingText = status
            }
        }
    }

    private fun applyIncognitoModeSettings(isIncognito: Boolean) {
        if (isIncognito) {
            requireActivity().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            requireActivity().window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            PreferencesHelper.applyThemeMode(requireContext())
        }
        val headerColor = if (isIncognito)
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.incognito_surface)
        else
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.surface_card)
        binding.chatHeader.setBackgroundColor(headerColor)
        binding.inputBar.setBackgroundColor(headerColor)
        binding.mediaPreviewCompose.setBackgroundColor(headerColor)
        disableKeyboardLearning(isIncognito)
    }

    private fun disableKeyboardLearning(disable: Boolean) {
        val isStopKeyboardLearning = PreferencesHelper.isStopKeyboardLearningEnabled(requireContext())
        val shouldDisable = disable || isStopKeyboardLearning
        val imeOptions = if (shouldDisable) {
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            0
        }

        // Find all EditText views and update IME options
        updateEditTextImeOptions(binding.root, imeOptions, shouldDisable)
    }

    private fun updateEditTextImeOptions(view: View, imeOptions: Int, disable: Boolean) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is EditText) {
                    if (disable) {
                        // Add the no personalized learning flag
                        child.imeOptions = child.imeOptions or imeOptions
                    } else {
                        // Remove the flag when disabling incognito mode
                        child.imeOptions = child.imeOptions and imeOptions.inv()
                    }
                } else if (child is ViewGroup) {
                    updateEditTextImeOptions(child, imeOptions, disable)
                }
            }
        } else if (view is EditText) {
            if (disable) {
                view.imeOptions = view.imeOptions or imeOptions
            } else {
                view.imeOptions = view.imeOptions and imeOptions.inv()
            }
        }
    }

    /**
     * MainActivity 側でシークレットモードが終了/開始されたときに UI フラグを同期する。
     * これがないと通常セッションに移動してもヘッダ色などがシークレットモードのまま戻らない。
     */
    fun syncIncognitoModeWithActivity() {
        val active = (activity as? com.nezumi_ai.MainActivity)?.isInIncognitoMode() ?: false
        if (viewModel.isIncognitoMode.value != active) {
            viewModel.setIncognitoMode(active)
        }
    }

    private fun updateIncognitoModeIndicator(isIncognito: Boolean) {
        if (isIncognito) {
            binding.backButton.setOnClickListener {
                viewModel.setIncognitoMode(false)
                Toast.makeText(requireContext(), "Incognito mode exited", Toast.LENGTH_SHORT).show()
            }
            binding.backButton.contentDescription = "Exit Incognito Mode"
        } else {
            binding.backButton.setOnClickListener {
                (activity as? com.nezumi_ai.MainActivity)?.openDrawer()
            }
            binding.backButton.contentDescription = "Menu"
        }
    }

    override fun onResume() {
        super.onResume()
        disableKeyboardLearning(viewModel.isIncognitoMode.value)

        // SharedPreferences 初回読取・listFiles・プリセット取得はすべて IO へ逃がす。
        // 以前は onResume のメインスレッド上で PreferencesHelper / getCurrentPreset を
        // 呼んでいたため、チャット画面復帰時に一瞬固まることがあった。
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val options = buildDownloadedModelOptions()
            val newContextMeterVisible = PreferencesHelper.isShowContextMeter(requireContext())
            val newShowTps = PreferencesHelper.isShowTps(requireContext())
            val newShowTtft = PreferencesHelper.isShowTtft(requireContext())
            val currentPresetId = PreferencesHelper.getCurrentPresetId(requireContext())
            val preset = presetRepository.getCurrentPreset()

            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                modelOptions = options
                updateModelNameText(viewModel.selectedModel.value)
                refreshCurrentBackendType()
                updateMediaAvailability(currentModelKey)
                updateThinkingToggleVisibility()

                if (newContextMeterVisible != contextMeterVisible) {
                    contextMeterVisible = newContextMeterVisible
                    adapter.notifyDataSetChanged()
                }
                if (newShowTps != showTpsIndicator || newShowTtft != showTtftIndicator) {
                    showTpsIndicator = newShowTps
                    showTtftIndicator = newShowTtft
                    adapter.refreshPerfIndicatorVisibility(newShowTps, newShowTtft)
                }

                binding.chatTitle.text = if (preset != null) {
                    "${preset.icon} ${preset.name} ▼"
                } else {
                    getString(R.string.chat_title)
                }

                if (lastObservedPresetId != null && currentPresetId != lastObservedPresetId) {
                    lastObservedPresetId = currentPresetId
                    viewModel.preloadActivePresetModel()
                } else {
                    lastObservedPresetId = currentPresetId
                }

                // バグ修正: モデル管理画面でモデルを削除して戻ってきたときに、
                // 選択中のプリセットが未選択状態になっていないかここでも確認する。
                checkAndShowPresetModelUnselectedDialog()
            }
        }
    }

    private suspend fun refreshPresetHeader() {
        val preset = presetRepository.getCurrentPreset()
        binding.chatTitle.text = if (preset != null) {
            "${preset.icon} ${preset.name} ▼"
        } else {
            getString(R.string.chat_title)
        }
    }

    override fun onStop() {
        super.onStop()
        // バックグラウンドでも推論を続ける - LiteRtLmEngine の会話終了処理により
        // セッション遷移時や明示的な停止時に completeness 検証が行われる
        // onStop でのキャンセルはKVキャッシュを不完全な状態で残し、
        // 次セッションで DYNAMIC_UPDATE_SLICE エラーを引き起こすため削除
    }

    private fun applyStatusBarInset() {
        val initialTop = binding.root.paddingTop
        val initialBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            root.updatePadding(
                top = initialTop + topInset,
                bottom = initialBottom + max(imeInset, navInset)
            )
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && imeInset > 0
            if (imeVisible && !wasImeVisible) {
                scrollToBottomImmediate()
            }
            wasImeVisible = imeVisible
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }


    private fun scrollToMessageId(messageId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            // メッセージリストが描画されるまで少し待つ
            kotlinx.coroutines.delay(400L)
            val currentList = adapter.currentList
            val position = currentList.indexOfFirst { it.id == messageId }
            if (position >= 0) {
                val lm = binding.messagesRecyclerView.layoutManager
                    as? androidx.recyclerview.widget.LinearLayoutManager ?: return@launch
                lm.scrollToPositionWithOffset(position, 120)
                Log.d("ChatFragment", "scrollToMessageId: id=$messageId pos=$position")
            } else {
                Log.w("ChatFragment", "scrollToMessageId: messageId=$messageId not found in list")
            }
        }
    }

    /**
 * バリアント切り替え後のスクロール: 切り替え先メッセージの「一番下」に合わせる。
 * ItemView の底辺が RecyclerView のビューポート底に合わさるよう offset を計算する。
 * submitList の後で高さが確定しないことがあるので、複数フレームにわたって追従する。
     */
    private fun scrollVariantMessageBottomIntoView(messageId: Long) {
        // ユーザーの能動的スクロール状態をリセットしてこのジャンプを優先する。
        userScrolledAwayDuringGeneration = false
        viewLifecycleOwner.lifecycleScope.launch {
            // submitList で新しいリストが反映されるまでちょっと待つ
            kotlinx.coroutines.delay(48L)
            val rv = _binding?.messagesRecyclerView ?: return@launch
            val lm = rv.layoutManager
                as? androidx.recyclerview.widget.LinearLayoutManager ?: return@launch
            val currentList = adapter.currentList
            val position = currentList.indexOfFirst { it.id == messageId }
            if (position < 0) {
                Log.w(TAG, "scrollVariantMessageBottomIntoView: id=$messageId not found")
                return@launch
            }
            fun landBottom() {
                if (_binding == null || !isAdded) return
                val view = lm.findViewByPosition(position)
                if (view == null) {
                    // まだレイアウトされていないときは、とりあえずその位置を上に見えるようジャンプし、次フレームで再試行。
                    lm.scrollToPositionWithOffset(position, 0)
                    return
                }
                val rvBottomInside = rv.height - rv.paddingBottom
                val itemBottom = view.bottom
                val delta = itemBottom - rvBottomInside
                if (delta > 0) {
                    // ItemView の底がビューポートより下: その分だけ下スクロールして底を見える位置にする。
                    rv.scrollBy(0, delta)
                } else if (view.top < rv.paddingTop) {
                    // アイテムが高すぎて一画面に収まらない場合は、底を見せつつ top をビューポートに入れることを優先しない。
                    // 、選択中バリアントの「一番下」を見せるのが目的なので delta<=0 なら何もしない。
                }
            }
            // フレーム内でビュー内にスクロールしておいてから delta 調整する。
            lm.scrollToPositionWithOffset(position, 0)
            rv.post {
                if (_binding == null || !isAdded) return@post
                landBottom()
                // Markdown / コードブロックの遅延レイアウトに備えて数フレーム後にも一度確定スクロールする。
                rv.postDelayed({ landBottom() }, 64L)
                rv.postDelayed({ landBottom() }, 160L)
                rv.postDelayed({
                    landBottom()
                    updateScrollToBottomButtonVisibility()
                }, 320L)
            }
            Log.d(TAG, "scrollVariantMessageBottomIntoView: id=$messageId pos=$position")
        }
    }

    private fun scrollToBottomImmediate() {
        val rv = _binding?.messagesRecyclerView ?: return
        val lastItem = adapter.itemCount - 1
        if (lastItem < 0) return
        autoFollowBottomLocked = true
        rv.removeCallbacks(autoScrollRunnable)
        autoScrollPosted = false
        rv.stopScroll()
        rv.post {
            if (_binding == null || !isAdded) return@post
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@post
            lm.scrollToPositionWithOffset(lastItem, 0)
            // Double-post to ensure layout is complete before forcing bottom
            rv.post {
                if (_binding == null || !isAdded) return@post
                rv.post {
                    if (_binding == null || !isAdded) return@post
                    forceBottomForFrames(rv, immediateScrollMaxFrames, rv.computeVerticalScrollRange())
                }
            }
        }
    }

    private fun shouldAutoFollowBottom(): Boolean {
        if (userScrolledAwayDuringGeneration) return false
        // 生成完了直後の settle フェーズも末尾追従を続ける（バグ修正: 生成完了瞬間に一番上へ飛ぶ問題）。
        if (postGenerationSettleActive) return autoFollowBottomLocked
        if (!isGenerating) return false
        return autoFollowBottomLocked || isUserAtBottom || isNearBottom()
    }

    private fun scheduleAutoScrollToBottom() {
        val rv = _binding?.messagesRecyclerView ?: return
        if (autoScrollPosted) return
        autoScrollPosted = true
        rv.removeCallbacks(autoScrollRunnable)
        rv.postDelayed(autoScrollRunnable, autoScrollDebounceMs)
    }

    private fun followBottomAfterLayout() {
        val rv = _binding?.messagesRecyclerView ?: return
        rv.post {
            if (_binding == null || !isAdded) return@post
            followBottomForFrames(rv, autoFollowMaxFrames, rv.computeVerticalScrollRange())
        }
    }

    private fun followBottomForFrames(rv: RecyclerView, framesRemaining: Int, previousRange: Int) {
        if (_binding == null || !isAdded) return
        // settle フェーズ中は isGenerating==false でも追従を継続する。
        if ((!isGenerating && !postGenerationSettleActive) || userScrolledAwayDuringGeneration) return
        if (userIsDraggingMessages) {
            Log.d(TAG, "AUTOSCROLL_STOP: user drag in progress")
            return
        }
        if (!autoFollowBottomLocked && !isNearBottom(rv)) return

        scrollRemainingDistanceToBottom(rv)
        isUserAtBottom = !rv.canScrollVertically(1)
        updateScrollToBottomButtonVisibility()

        if (framesRemaining <= 0) return
        rv.postOnAnimation {
            if (_binding == null || !isAdded) return@postOnAnimation
            val currentRange = rv.computeVerticalScrollRange()
            val stillNotAtBottom = rv.canScrollVertically(1)
            if (stillNotAtBottom || currentRange != previousRange) {
                followBottomForFrames(rv, framesRemaining - 1, currentRange)
            }
        }
    }

    private fun forceBottomForFrames(rv: RecyclerView, framesRemaining: Int, previousRange: Int) {
        if (_binding == null || !isAdded) return

        scrollRemainingDistanceToBottom(rv)
        isUserAtBottom = !rv.canScrollVertically(1)
        updateScrollToBottomButtonVisibility()

        if (framesRemaining <= 0) return
        rv.postOnAnimation {
            if (_binding == null || !isAdded) return@postOnAnimation
            val currentRange = rv.computeVerticalScrollRange()
            val stillNotAtBottom = rv.canScrollVertically(1)
            if (stillNotAtBottom || currentRange != previousRange) {
                forceBottomForFrames(rv, framesRemaining - 1, currentRange)
            }
        }
    }

    private fun scrollRemainingDistanceToBottom(rv: RecyclerView): Boolean {
        val distanceToBottom = (
            rv.computeVerticalScrollRange() -
                rv.computeVerticalScrollOffset() -
                rv.computeVerticalScrollExtent()
            ).coerceAtLeast(0)
        if (distanceToBottom <= 0) return false
        rv.scrollBy(0, distanceToBottom)
        return true
    }

    private fun isNearBottom(rv: RecyclerView = binding.messagesRecyclerView): Boolean {
        val distanceToBottom = (
            rv.computeVerticalScrollRange() -
                rv.computeVerticalScrollOffset() -
                rv.computeVerticalScrollExtent()
            ).coerceAtLeast(0)
        return distanceToBottom <= autoFollowBottomThresholdPx
    }

    private fun scrollToBottom(position: Int) {
        if (position < 0) return
        userScrolledAwayDuringGeneration = false
        autoFollowBottomLocked = true
        scrollToBottomImmediate()
    }

    private fun isAtBottom(): Boolean {
        val rv = _binding?.messagesRecyclerView ?: return true
        return !rv.canScrollVertically(1)
    }

    private fun updateScrollToBottomButtonVisibility() {
        if (_binding == null || !isAdded) return
 // バグ修正: 生成中に自動スクロールフラグが ON のとき（=末尾追従中）は、
        //   ストリーミングで新トークンが挿入されるたびに一瞬底から離れて下矢印が
        //   チカチカ表示されてしまう。追従が有効な間は、そもそもボタンを出さない。
        //   生成完了直後の settle フェーズ中も同じ扱いにする（間もなく確実に底に着地するため）。
        val followingBottom = (isGenerating && !userScrolledAwayDuringGeneration &&
            (autoFollowBottomLocked || isUserAtBottom || isNearBottom()))
        if (followingBottom || postGenerationSettleActive) {
            scrollToBottomVisible = false
            return
        }
        scrollToBottomVisible = !isAtBottom()
    }

    private fun captureScrollAnchorIfNeeded(): ScrollAnchor? {
        if (isUserAtBottom) return null
        val rv = binding.messagesRecyclerView
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION) return null
        val firstView = lm.findViewByPosition(firstVisible) ?: return null
        return ScrollAnchor(
            position = firstVisible,
            offset = firstView.top - rv.paddingTop
        )
    }

    private fun restoreScrollAnchor(anchor: ScrollAnchor) {
        val rv = _binding?.messagesRecyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        rv.post {
            if (_binding == null || !isAdded) return@post
            lm.scrollToPositionWithOffset(anchor.position, anchor.offset)
            updateScrollToBottomButtonVisibility()
        }
    }

    private fun preserveScrollIfNeeded(block: () -> Unit) {
        val anchor = captureScrollAnchorIfNeeded()
        block()
        if (anchor != null) {
            restoreScrollAnchor(anchor)
        }
    }

    private fun setupModelDisplay() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val options = buildDownloadedModelOptions()
            withContext(Dispatchers.Main) {
                modelOptions = options
                updateModelNameText(viewModel.selectedModel.value)
            }
        }
    }

    private fun updateModelNameText(modelKey: String) {
        val selected = modelOptions.firstOrNull { it.key == modelKey }
        val label = selected?.label
            ?: if (com.nezumi_ai.data.inference.cloud.CloudModelId.isCloud(modelKey)) {
                com.nezumi_ai.data.inference.cloud.CloudModelId.displayLabel(modelKey)
            } else {
                modelKey.takeIf { it.isNotBlank() }
            }
            ?: getString(R.string.model_not_downloaded)
        binding.modelName.text = modelDisplaySuffix(label)
    }

    private fun buildDownloadedModelOptions(): List<ModelOption> {
        val options = mutableListOf<ModelOption>()
        // Gemma 3n 2B / 4B は新規の選択肢から除外する（ダウンロードカードを提供しないため、
                // チャットのセレクターも Gemma 4 以降に絞る）。
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA4_2B)) {
            options += ModelOption("Gemma4-2B", "Gemma 4 2B")
        }
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA4_4B)) {
            options += ModelOption("Gemma4-4B", "Gemma 4 4B")
        }
        // 既存ダウンロード済みの Gemma 3n ファイルが残っている場合だけ、
        // 見えなくならないよう末尾に追記する（ダウンロードフローは剥がす）。
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA3N_2B)) {
            options += ModelOption("Gemma3n-2B", "Gemma 3n 2B")
        }
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA3N_4B)) {
            options += ModelOption("Gemma3n-4B", "Gemma 3n 4B")
        }
        ModelFileManager.listImportedTaskModels(requireContext()).forEach { imported ->
            val label = ImportedModelCapabilityStore.resolveDisplayName(
                requireContext(), imported.path, imported.shortDisplayName
            )
            options += ModelOption(imported.path, label)
        }
        // 構成済みクラウドモデルもチャットのモデル表示・選択肢に含める
        com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry.listForContext(requireContext()).forEach { modelId ->
            if (!com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry.isConfiguredForContext(requireContext(), modelId)) {
                return@forEach
            }
            options += ModelOption(
                modelId,
                com.nezumi_ai.data.inference.cloud.CloudModelId.displayLabel(modelId)
            )
        }
        return options
    }

    private data class ModelOption(
        val key: String,
        val label: String
    )

    private fun renderSendButtonState() {
        // NPE 修正: processPickedVideo 等のコルーチン完了時に Fragment のビューが
        // 既に破棄されていると binding (_binding!!) が NPE を投げてクラッシュする。
        // ビューが無いなら描画すべきボタン自体が存在しないので、安全に何もしない。
        val b = _binding ?: return
        b.sendButton.setImageResource(
            if (isGenerating) R.drawable.ic_stop else R.drawable.ic_send
        )
        // 動画のフレーム/音声抽出中は、まだ送信できる添付物が確定していないため送信不可にする。
        // 生成停止ボタンとしての利用（isGenerating時）は動画抽出中とは独立に常に有効のままにする。
        b.sendButton.isEnabled =
            isGenerating || (!isModelLoadingNow && !isExtractingVideo && !isConvertingDocument)
    }

    private fun updateMediaAvailability(modelKey: String) {
        val caps = ImportedModelCapabilityStore.resolveForModel(requireContext(), modelKey)
        imageInputEnabled = caps.imageEnabled
        audioInputEnabled = caps.audioEnabled

        // 非対応メディアは選択状態を破棄し、送信対象から除外
        if (!imageInputEnabled) {
            selectedImageUrisList = emptyList()  // Phase 11: 複数画像対応
            selectedTextFiles = emptyList()
            selectedVideoUri = null
            selectedVideoDurationMs = 0L
            selectedVideoFrameCount = 0
            selectedTextFiles = emptyList()
        }
        if (!audioInputEnabled) {
            selectedAudioUri = null
        }
        updateMediaPreview()
        binding.mediaMenuButton.visibility = View.VISIBLE
        // マイクボタンは入力バーに常設。モデルが音声入力に非対応なら非表示にする。
        binding.micButton.visibility = if (audioInputEnabled) View.VISIBLE else View.GONE
    }

    private fun renderCompressButtonState() {
        val enabled = !isModelLoadingNow && !isGenerating && contextUsageCharsNow > 0
        compressButtonVisible = BuildConfig.CONTEXT_COMPRESSION_ENABLED && contextCompressionEnabled && !isCompressingNow
        compressButtonEnabled = enabled
        compressButtonText = ""
        // シンキングON/OFFはチャット生成中でも切り替え可能にする（次回送信から反映）
 // Bug fix(#Thinking-Header):
        //   旧実装は `thinkingToggleEnabled = !isModelLoadingNow && thinkingToggleVisible` だったが、
        //   この式だと thinkingToggleVisible が一旦 false になると enabled も false に固定され、
        //   シンキング生成フェーズで modelSupportsThinking の再評価が遅れるタイミングで
        //   ヘッダーの Switch 自体が消失して見えなくなっていた。
        //   enabled を visible と切り離し、ヘッダーの表示自体は常に保ちつつ、
        //   押下可否のみロードステータスで制御する。
        thinkingToggleEnabled = !isModelLoadingNow
    }

    private fun updateThinkingToggleVisibility() {
        val modelSupportsThinking = settingsRepository.modelSupportsGemmaThinking(currentModelKey, requireContext())
        thinkingToggleVisible = modelSupportsThinking
        renderCompressButtonState()
    }

    private fun refreshCurrentBackendType() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val backend = settingsRepository.getBackendForModel(currentModelKey)
            withContext(Dispatchers.Main) {
                currentBackendType = backend.uppercase()
            }
        }
    }

    private fun startResponseTypingAnimation() {
        if (responseTypingAnimationJob?.isActive == true) return
        responseTypingVisible = true
        responseTypingAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            var dotCount = 0
            while (true) {
                val dots = ".".repeat(dotCount)
                val base = if (isCompressingNow) {
                    ""
                } else {
                    getString(R.string.response_generating)
                }
                responseTypingText = base + dots
                dotCount = (dotCount + 1) % 4
                delay(350)
            }
        }
    }

    private fun stopResponseTypingAnimation() {
        responseTypingAnimationJob?.cancel()
        responseTypingAnimationJob = null
        responseTypingVisible = false
        responseTypingText = getString(R.string.response_generating)
    }

    private fun setupComposeIndicators() {
        binding.responseTypingCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.responseTypingCompose.setContent {
            ResponseTypingIndicator()
        }

        binding.modelLoadingComposeOverlay.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.modelLoadingComposeOverlay.setContent {
            ModelLoadingOverlay()
        }

        binding.contextMeterCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.contextMeterCompose.setContent {
            ContextMeterSection()
            ContextRawDialog()
            ImageGenConfirmationDialog()
        }

        binding.scrollToBottomCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.scrollToBottomCompose.setContent {
            ScrollToBottomSection()
        }

        binding.headerActionsCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.headerActionsCompose.setContent {
            HeaderActionsSection()
        }

        binding.mediaPreviewCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.mediaPreviewCompose.setContent {
            NezumiComposeTheme {
                MediaPreviewBar(
                    hasImage = selectedImageUrisList.isNotEmpty(),
                    hasAudio = selectedAudioUri != null,
                    imageUris = selectedImageUrisList,
                    onClearImage = { selectedImageUrisList = emptyList() },
                    onRemoveImage = { index ->
                        if (index in selectedImageUrisList.indices) {
                            selectedImageUrisList = selectedImageUrisList.filterIndexed { i, _ -> i != index }
                        }
                    },
                    audioUri = selectedAudioUri,
                    onClearAudio = { selectedAudioUri = null },
                    textFiles = selectedTextFiles,
                    onRemoveTextFile = { index ->
                        if (index in selectedTextFiles.indices) {
                            selectedTextFiles = selectedTextFiles.filterIndexed { i, _ -> i != index }
                        }
                    },
                    onOpenTextFile = { entry ->
                        com.nezumi_ai.presentation.ui.component.TextFileViewerDialog.show(
                            requireContext(), entry
                        )
                    },
                    videoUri = selectedVideoUri,
                    onClearVideo = {
                        // 動画を外すときは、動画由来のフレーム・音声も一緒にクリアするのが自然。
                        selectedVideoUri = null
                        selectedVideoDurationMs = 0L
                        selectedVideoFrameCount = 0
                        selectedImageUrisList = emptyList()
                        selectedAudioUri = null
                    },
                    isExtractingVideo = isExtractingVideo,
                    isConvertingDocument = isConvertingDocument,
                    convertingDocumentName = convertingDocumentName,
                    onOpenViewer = { selectedKey ->
                        val bundle = com.nezumi_ai.presentation.ui.component.MediaViewerDialog.MediaBundle(
                            imageUris = selectedImageUrisList,
                            videoUri = selectedVideoUri,
                            audioUri = selectedAudioUri,
                            title = if (selectedVideoUri != null) requireContext().getString(R.string.multimodal_video_frame_audio_title) else requireContext().getString(R.string.multimodal_media_preview_title),
                            initialIndex = if (selectedKey.startsWith("image:")) {
                                selectedKey.removePrefix("image:").toIntOrNull() ?: 0
                            } else 0
                        )
                        com.nezumi_ai.presentation.ui.component.MediaViewerDialog.show(
                            requireContext(),
                            bundle
                        )
                    }
                )
            }
        }

        binding.emptyStateCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.emptyStateCompose.setContent {
            EmptyStateScreen()
        }

        binding.toolCallProgressCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.toolCallProgressCompose.setContent {
            this@ChatFragment.NezumiComposeTheme {
                ToolCallProgressBar(
                    state = currentToolCallState,
                    imageGenProgress = currentImageGenProgress
                )
            }
        }
    }

    /**
     * デフォルトプリセットのモデルが未ダウンロードのときに出すモーダル。
     * - [firstAvailableLabel] が非 null: 「<label> を使う」でデフォルトプリセットの
     *   モデルを付け替える。
     * - null: 利用可能モデルが無い旨を表示し、モデル管理画面への導線だけ出す。
     */
    private fun showDefaultModelMissingDialog(firstAvailableLabel: String?) {
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val composeView = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@ChatFragment)
            setViewTreeSavedStateRegistryOwner(this@ChatFragment)
            setContent {
                NezumiComposeTheme {
                    DefaultModelMissingDialog(
                        firstAvailableLabel = firstAvailableLabel,
                        onUseFirst = {
                            dialog.dismiss()
                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    presetRepository.reassignDefaultPresetToFirstAvailableModel()
                                }
                            }
                        },
                        onOpenSettings = {
                            dialog.dismiss()
                            runCatching {
                                findNavController().navigate(R.id.modelSettingsFragment)
                            }
                        },
                        onLater = { dialog.dismiss() }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    @Composable
    private fun DefaultModelMissingDialog(
        firstAvailableLabel: String?,
        onUseFirst: () -> Unit,
        onOpenSettings: () -> Unit,
        onLater: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onLater,
            title = { Text(stringResource(id = R.string.default_model_missing_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            id = if (firstAvailableLabel != null) R.string.default_model_missing_message
                            else R.string.default_model_missing_no_models
                        )
                    )
                }
            },
            confirmButton = {
                if (firstAvailableLabel != null) {
                    TextButton(onClick = onUseFirst) {
                        Text(stringResource(id = R.string.default_model_missing_use_first, firstAvailableLabel))
                    }
                } else {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(id = R.string.default_model_missing_open_settings))
                    }
                }
            },
            dismissButton = {
                Row {
                    if (firstAvailableLabel != null) {
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(id = R.string.default_model_missing_open_settings))
                        }
                    }
                    TextButton(onClick = onLater) {
                        Text(stringResource(id = R.string.default_model_missing_later))
                    }
                }
            }
        )
    }

    /**
     * バグ修正: モデル削除で現在のプリセットが未選択状態になっていないか確認し、
     * なっていればモデル再選択ダイアログを出す。
     * 同じプリセットに対しては、ダイアログを閉じる/選択するまで再表示しない
     * ([presetModelUnselectedDialogShownForPresetId] でガード)。
     */
    private fun checkAndShowPresetModelUnselectedDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val preset = withContext(Dispatchers.IO) { presetRepository.getCurrentPreset() }
            if (preset == null || preset.modelId.isNotBlank()) {
                return@launch
            }
            if (presetModelUnselectedDialogShownForPresetId == preset.id) {
                return@launch
            }
            presetModelUnselectedDialogShownForPresetId = preset.id
            val options = withContext(Dispatchers.IO) {
                com.nezumi_ai.data.preset.PresetModelCatalog.downloadedModels(requireContext())
            }
            showPresetModelUnselectedDialog(preset, options)
        }
    }

    /**
     * 「モデルが削除されて未選択状態になったプリセット」向けのモデル再選択モーダル。
     * 利用可能なモデル一覧から選んでもらい、そのプリセットに割り当てる。
     */
    private fun showPresetModelUnselectedDialog(
        preset: com.nezumi_ai.data.database.entity.PresetEntity,
        options: List<com.nezumi_ai.data.preset.PresetModelOption>
    ) {
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val composeView = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@ChatFragment)
            setViewTreeSavedStateRegistryOwner(this@ChatFragment)
            setContent {
                NezumiComposeTheme {
                    PresetModelUnselectedDialog(
                        presetName = "${preset.icon} ${preset.name}".trim(),
                        options = options,
                        onConfirm = { selectedModelId ->
                            dialog.dismiss()
                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    presetRepository.assignModelToPreset(preset.id, selectedModelId)
                                }
                                presetModelUnselectedDialogShownForPresetId = null
                                refreshPresetHeader()
                                viewModel.preloadActivePresetModel()
                            }
                        },
                        onOpenSettings = {
                            dialog.dismiss()
                            runCatching {
                                findNavController().navigate(R.id.modelSettingsFragment)
                            }
                        },
                        onLater = { dialog.dismiss() }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    @Composable
    private fun PresetModelUnselectedDialog(
        presetName: String,
        options: List<com.nezumi_ai.data.preset.PresetModelOption>,
        onConfirm: (String) -> Unit,
        onOpenSettings: () -> Unit,
        onLater: () -> Unit
    ) {
        var selectedId by remember(options) { mutableStateOf(options.firstOrNull()?.id ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onLater,
            title = { Text(stringResource(id = R.string.preset_model_unselected_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            id = R.string.preset_model_unselected_message,
                            presetName
                        )
                    )
                    if (options.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(id = R.string.preset_model_unselected_no_models))
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            options.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedId = option.id },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedId == option.id,
                                        onClick = { selectedId = option.id }
                                    )
                                    Text(option.label)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (options.isNotEmpty()) {
                    TextButton(
                        onClick = { onConfirm(selectedId) },
                        enabled = selectedId.isNotBlank()
                    ) {
                        Text(stringResource(id = R.string.preset_model_unselected_confirm))
                    }
                } else {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(id = R.string.preset_model_unselected_open_settings))
                    }
                }
            },
            dismissButton = {
                Row {
                    if (options.isNotEmpty()) {
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(id = R.string.preset_model_unselected_open_settings))
                        }
                    }
                    TextButton(onClick = onLater) {
                        Text(stringResource(id = R.string.preset_model_unselected_later))
                    }
                }
            }
        )
    }

    private fun showMemoryErrorDialog(error: ChatViewModel.MemoryErrorInfo) {
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val composeView = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@ChatFragment)
            setViewTreeSavedStateRegistryOwner(this@ChatFragment)
            setContent {
                NezumiComposeTheme {
                    MemoryErrorDialog(
                        error = error,
                        onDismiss = {
                            dialog.dismiss()
                            viewModel.dismissMemoryError()
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /**
     * ユーザーが設定で選んだGPUバックエンドが端末で実際には使えず、CPUへ
     * 自動フォールバックした場合に表示するダイアログ。
     *
     * ユーザーに選ばせずに黙って別のバックエンドで動かし続けることは避け、
     * 「CPUで続行する」か「中止して設定を見直す」かを必ず選んでもらう。
     * setCancelable(false) にして、背景タップやバックキーでうやむやにできない
     * ようにする（ダイアログを消すには必ずどちらかのボタンを押す必要がある）。
     */
    private fun showGpuBackendFallbackDialog(info: ChatViewModel.GpuBackendFallbackInfo) {
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val composeView = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@ChatFragment)
            setViewTreeSavedStateRegistryOwner(this@ChatFragment)
            setContent {
                NezumiComposeTheme {
                    GpuBackendFallbackDialog(
                        info = info,
                        onContinueOnCpu = {
                            dialog.dismiss()
                            viewModel.acknowledgeGpuBackendFallback()
                        },
                        onCancel = {
                            dialog.dismiss()
                            viewModel.cancelDueToGpuBackendFallback()
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    @Composable
    private fun GpuBackendFallbackDialog(
        info: ChatViewModel.GpuBackendFallbackInfo,
        onContinueOnCpu: () -> Unit,
        onCancel: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { /* 明示的な選択を必須にするため何もしない */ },
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.ic_errnezumi),
                    contentDescription = stringResource(id = R.string.chat_error_icon_description),
                    modifier = Modifier.size(128.dp)
                )
            },
            title = { Text(stringResource(id = R.string.chat_gpu_backend_fallback_title)) },
            text = {
                Text(
                    stringResource(
                        id = R.string.chat_gpu_backend_fallback_body,
                        info.requestedBackend,
                        info.actualBackend
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onContinueOnCpu) {
                    Text(stringResource(id = R.string.chat_gpu_backend_fallback_continue, info.actualBackend))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onCancel) {
                    Text(stringResource(id = R.string.chat_gpu_backend_fallback_cancel))
                }
            }
        )
    }

    @Composable
    private fun MemoryErrorDialog(
        error: ChatViewModel.MemoryErrorInfo,
        onDismiss: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.ic_errnezumi),
                    contentDescription = stringResource(id = R.string.chat_error_icon_description),
                    modifier = Modifier.size(128.dp)
                )
            },
            title = { Text(stringResource(id = R.string.chat_memory_error_title)) },
            text = {
                Column {
                    Text(stringResource(id = R.string.chat_memory_error_body))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(id = R.string.chat_memory_error_usage, error.usedMB, error.totalMB, error.usedPercent))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(id = R.string.chat_memory_error_advice),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.chat_memory_error_close)) }
            }
        )
    }

    private fun showMemoryWarningDialog(warning: ChatViewModel.MemoryWarningInfo) {
        // Compose UIダイアログをDialogでラップして表示
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val composeView = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            // ViewTreeLifecycleOwnerを明示的に設定
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@ChatFragment)
            setViewTreeSavedStateRegistryOwner(this@ChatFragment)

            setContent {
                NezumiComposeTheme {
                    MemoryWarningDialog(
                        warning = warning,
                        onConfirm = {
                            dialog.dismiss()
                            val scope = if (view != null && isAdded) {
                                try {
                                    viewLifecycleOwner.lifecycleScope
                                } catch (e: Exception) {
                                    MainScope()
                                }
                            } else {
                                MainScope()
                            }
                            scope.launch {
                                try {
                                    viewModel.proceedWithModelLoad(viewModel.selectedModel.value)
                                } catch (e: Exception) {
                                    Log.e("ChatFragment", "Error in memory warning dialog continue button", e)
                                }
                            }
                        },
                        onDismiss = {
                            dialog.dismiss()
                            viewModel.cancelMemoryWarningAndGoHome()
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    @Composable
    private fun MemoryWarningDialog(
        warning: ChatViewModel.MemoryWarningInfo,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.ic_wnezumi),
                    contentDescription = stringResource(id = R.string.warning_icon_description),
                    modifier = Modifier.size(128.dp)
                )
            },
            title = {
                Text(stringResource(id = R.string.chat_memory_warning_title))
            },
            text = {
                Column {
                    Text(stringResource(id = R.string.chat_memory_warning_body, warning.modelName))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(id = R.string.chat_memory_warning_device_info, warning.usedMemoryMB, warning.totalMemoryMB))
                    Text(stringResource(id = R.string.chat_memory_warning_usage, warning.usedPercent))
                    Text(
                        if (warning.lowMemoryFlag) stringResource(id = R.string.chat_memory_low_state) else stringResource(id = R.string.chat_memory_normal_state),
                        color = if (warning.lowMemoryFlag) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(id = R.string.chat_memory_warning_continue))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(id = R.string.chat_memory_warning_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.chat_memory_warning_cancel))
                }
            }
        )
    }

    private fun showEmbeddingDownloadDialog() {
        if (embeddingDownloadDialog?.isShowing == true) return
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val messageView = TextView(context).apply {
            text = context.getString(R.string.embedding_download_progress_message)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            progress = 0
        }
        container.addView(messageView)
        container.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.embedding_download_dialog_title))
            .setView(container)
            .setNegativeButton(context.getString(R.string.embedding_download_cancel)) { _, _ ->
                viewModel.cancelEmbeddingDownload()
            }
            .setCancelable(false)
            .create()

        dialog.show()
        embeddingDownloadDialog = dialog
        embeddingDownloadProgressTextView = messageView
        embeddingDownloadProgressBar = progressBar
    }

    private fun updateEmbeddingDownloadDialog(progress: ChatViewModel.EmbeddingDownloadProgress?) {
        if (embeddingDownloadDialog?.isShowing != true) return
        if (progress == null) {
            embeddingDownloadProgressTextView?.text = getString(R.string.embedding_download_preparing)
            embeddingDownloadProgressBar?.isIndeterminate = true
            return
        }
        embeddingDownloadProgressTextView?.text = "${progress.fileName}: ${progress.downloaded} / ${if (progress.total > 0) progress.total else "?"}"
        if (progress.total > 0) {
            embeddingDownloadProgressBar?.isIndeterminate = false
            embeddingDownloadProgressBar?.progress = ((progress.downloaded.toDouble() / progress.total.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else {
            embeddingDownloadProgressBar?.isIndeterminate = true
        }
    }

    private fun dismissEmbeddingDownloadDialog() {
        embeddingDownloadDialog?.dismiss()
        embeddingDownloadDialog = null
        embeddingDownloadProgressTextView = null
        embeddingDownloadProgressBar = null
    }

    private fun showCpuCompatibilityWarningDialog(warning: ChatViewModel.CpuCompatibilityWarningInfo) {
        val alertDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.cpu_compat_warning_title))
            .setIcon(R.drawable.ic_nezumi_ai)
            .setMessage(
                getString(
                    R.string.model_load_warning_confirm,
                    warning.modelName,
                    warning.message.replace("", "").trim() + "\n\n"
                )
            )
            .setPositiveButton(getString(R.string.cpu_compat_warning_continue)) { _, _ ->
                val scope = if (view != null && isAdded) {
                    try {
                        viewLifecycleOwner.lifecycleScope
                    } catch (e: Exception) {
                        MainScope()
                    }
                } else {
                    MainScope()
                }
                scope.launch {
                    try {
                        viewModel.proceedWithCpuCompatibilityWarning(viewModel.selectedModel.value)
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "Error in CPU compatibility warning continue button", e)
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel_generic)) { _, _ ->
                viewModel.cancelCpuCompatibilityWarning()
            }
            .setCancelable(false)
            .create()
        alertDialog.show()
    }

    private fun showModelErrorDialog(message: String) {
        // Parse message to extract title, body, and details
        val lines = message.split("\n\n")
        val title = lines.getOrNull(0) ?: getString(R.string.chat_error_generic_title)
        val body = lines.getOrNull(1) ?: lines.getOrNull(0) ?: message
        val detail = lines.getOrNull(2)

        val dialog = android.app.Dialog(requireContext()).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        val composeView = ComposeView(requireContext()).apply {
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@ChatFragment)
            setViewTreeSavedStateRegistryOwner(this@ChatFragment)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NezumiComposeTheme {
                    ErrorModalDialogContent(
                        title = title,
                        message = body,
                        detail = detail,
                        onDismiss = {
                            viewModel.dismissModelErrorDialogMessage()
                            dialog.dismiss()
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @Composable
    private fun NezumiComposeTheme(content: @Composable () -> Unit) {
        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
        val primary = colorResource(id = R.color.primary)
        val onPrimary = colorResource(id = R.color.nezumi_on_primary)
        val primaryContainer = colorResource(id = R.color.nezumi_primary_container)
        val onPrimaryContainer = colorResource(id = R.color.nezumi_on_primary_container)
        val surface = colorResource(id = R.color.surface_card)
        val onSurface = colorResource(id = R.color.text_primary)
        val onSurfaceVariant = colorResource(id = R.color.text_secondary)
        val bg = colorResource(id = R.color.bg_chat)
        val colorScheme = if (isDark) {
            androidx.compose.material3.darkColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
                background = bg, onBackground = onSurface,
                surface = surface, onSurface = onSurface,
                surfaceVariant = surface, onSurfaceVariant = onSurfaceVariant
            )
        } else {
            androidx.compose.material3.lightColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
                background = bg, onBackground = onSurface,
                surface = surface, onSurface = onSurface,
                surfaceVariant = surface, onSurfaceVariant = onSurfaceVariant
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
    private fun EmptyStateScreen() {
        if (!messagesIsEmpty) return

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_chat)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_nezumi_ai),
                contentDescription = "Nezumi AI Logo",
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.brand_name_display),
                style = MaterialTheme.typography.titleLarge,
                color = colorResource(id = R.color.text_primary),
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    @Composable
    private fun ResponseTypingIndicator() {
        if (!responseTypingVisible) return
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            SvgSpinner(
                modifier = Modifier.padding(end = 8.dp).size(24.dp)
            )
            Text(
                text = responseTypingText,
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    @Composable
    private fun ModelLoadingOverlay() {
        if (!modelLoadingOverlayVisible) return
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.loading_overlay))
                // オーバーレイの空白部分をタップしたときに、下のボタンやメッセージ一覧へ
                // タッチが透過しないよう、ここでタップを消費する。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SvgSpinner(modifier = Modifier.size(48.dp))
                Text(
                    text = modelLoadingText,
                    color = colorResource(id = R.color.text_primary),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    @Composable
    private fun ContextMeterSection() {
 // 全般タブの「コンテキストメーターを表示」フラグが OFF のときは何も描画しない。
        if (!contextMeterVisible) return
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorResource(id = R.color.surface_card))
                .clickable {
                    // メーターをタップしたら raw コンテキストをモーダルで表示する。
                    // メーターの値は完全に正確ではないため、実際に何が入っているのかを
                    // ユーザーが確認できるようにする。
                    contextRawText = viewModel.contextRawPrompt.value
                    contextRawDialogVisible = true
                }
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = contextMeterText,
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall
            )
            LinearProgressIndicator(
                progress = { contextMeterProgress },
                modifier = Modifier.fillMaxWidth(),
                color = colorResource(id = R.color.primary),
                trackColor = colorResource(id = R.color.context_meter_track)
            )
            Text(
                text = stringResource(id = R.string.raw_context_open_hint),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    @Composable
    private fun ContextRawDialog() {
        if (!contextRawDialogVisible) return
        // バグ修正 (ライトモード対応):
        //   AlertDialog の containerColor / 各 contentColor を明示指定していなかったため、
        //   ライトモードで背景が白のまま、テキストも白で同化して見えなくなるケースがあった。
        //   raw_context_dialog_bg / raw_context_dialog_text リソースで
        //   ライト: 白背景 + 黒文字 / ダーク: 紺背景 + 白文字 を保証する。
        val dialogBg = colorResource(id = R.color.raw_context_dialog_bg)
        val dialogText = colorResource(id = R.color.raw_context_dialog_text)
        val buttonText = colorResource(id = R.color.primary)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { contextRawDialogVisible = false },
            containerColor = dialogBg,
            titleContentColor = dialogText,
            textContentColor = dialogText,
            confirmButton = {
                TextButton(onClick = { contextRawDialogVisible = false }) {
                    Text(stringResource(id = R.string.raw_context_close), color = buttonText)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clip = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clip?.setPrimaryClip(ClipData.newPlainText("raw_context", contextRawText))
                    Toast.makeText(requireContext(), getString(R.string.raw_context_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(id = R.string.raw_context_copy), color = buttonText)
                }
            },
            title = { Text(stringResource(id = R.string.raw_context_title), color = dialogText) },
            text = {
                val scrollState = androidx.compose.foundation.rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = if (contextRawText.isBlank())
                            stringResource(id = R.string.raw_context_empty)
                        else contextRawText,
                        style = MaterialTheme.typography.bodySmall,
                        // バグ修正: ダイアログ専用のテキスト色を直接適用して
                        //   背景とのコントラストを保証する。
                        color = dialogText
                    )
                }
            }
        )
    }

    /**
     * 画像生成ツール呼び出し直前に表示する確認ダイアログ。
     *
     * ViewModel の confirmationRequest StateFlow を collect して、null でない間だけ
     * AlertDialog を表示する。プロンプト・モデル・ステップ数をその場で変更できるようにしている。
     * 「はい、生成する」で onConfirmGenerateImage、キャンセルで onCancelGenerateImage を呼ぶ。
     */
    @Composable
    private fun ImageGenConfirmationDialog() {
        val request by viewModel.confirmationRequest.collectAsState()
        val req = request ?: return

        // ダイアログを開くたびに編集状態をリセットする。request の identity (プロンプト文字列 +
        // defaultModelName + defaultSteps の組) が変わったときだけ初期値を入れ直したいので、
        // remember のキーに request 自体を渡す。
        var editedPrompt by remember(req) { mutableStateOf(req.prompt) }
        var selectedModel by remember(req) { mutableStateOf(req.defaultModelName) }
        var steps by remember(req) { mutableStateOf(req.defaultSteps.coerceIn(req.minSteps, req.maxSteps)) }
        var modelDropdownOpen by remember(req) { mutableStateOf(false) }

        val bg = colorResource(id = R.color.image_gen_confirm_bg)
        val titleColor = colorResource(id = R.color.image_gen_confirm_title_text)
        val subtitleColor = colorResource(id = R.color.image_gen_confirm_subtitle_text)
        val iconBg = colorResource(id = R.color.image_gen_confirm_icon_bg)
        val iconTint = colorResource(id = R.color.image_gen_confirm_icon_tint)
        val boxBg = colorResource(id = R.color.image_gen_confirm_box_bg)
        val labelColor = colorResource(id = R.color.image_gen_confirm_label_text)
        val rowBg = colorResource(id = R.color.image_gen_confirm_row_bg)
        val stepperBtnBg = colorResource(id = R.color.image_gen_confirm_stepper_btn_bg)
        val accent = colorResource(id = R.color.image_gen_confirm_accent)
        val accentSoft = colorResource(id = R.color.image_gen_confirm_accent_soft)

        AlertDialog(
            onDismissRequest = { viewModel.onCancelGenerateImage() },
            containerColor = bg,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(iconBg, shape = RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("◈", fontSize = 15.sp, color = iconTint, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.image_gen_confirm_title),
                            color = titleColor,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(id = R.string.image_gen_confirm_subtitle),
                            color = subtitleColor,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // --- プロンプト編集ボックス ---
                    Text(
                        text = stringResource(id = R.string.image_gen_confirm_prompt_label),
                        color = labelColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(boxBg, shape = RoundedCornerShape(12.dp))
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = editedPrompt,
                            onValueChange = { editedPrompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp, max = 140.dp)
                                .padding(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = titleColor,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(id = R.string.image_gen_confirm_settings_label),
                        color = labelColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // --- モデル選択行 ---
                    if (req.availableModels.isNotEmpty()) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(rowBg, shape = RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .clickable { modelDropdownOpen = !modelDropdownOpen },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(id = R.string.image_gen_confirm_model_label),
                                    color = subtitleColor,
                                    fontSize = 12.5.sp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = (selectedModel ?: req.defaultModelName ?: "—").let {
                                            if (it.length > MODEL_NAME_DISPLAY_CHARS) it.take(MODEL_NAME_DISPLAY_CHARS) + "…" else it
                                        },
                                        color = titleColor,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (modelDropdownOpen) "▴" else "▾",
                                        color = labelColor,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (modelDropdownOpen) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 46.dp)
                                        .background(bg, shape = RoundedCornerShape(12.dp))
                                        .padding(4.dp)
                                ) {
                                    req.availableModels.forEach { name ->
                                        val isSelected = name == (selectedModel ?: req.defaultModelName)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) accentSoft else Color.Transparent,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    selectedModel = name
                                                    modelDropdownOpen = false
                                                }
                                                .padding(horizontal = 10.dp, vertical = 9.dp)
                                        ) {
                                            Text(
                                                text = name,
                                                color = titleColor,
                                                fontSize = 12.5.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // --- ステップ数行 ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg, shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.image_gen_confirm_steps_label),
                            color = subtitleColor,
                            fontSize = 12.5.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(stepperBtnBg, shape = RoundedCornerShape(8.dp))
                                    .clickable {
                                        steps = (steps - 1).coerceIn(req.minSteps, req.maxSteps)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("−", color = titleColor, fontSize = 15.sp)
                            }
                            Text(
                                text = steps.toString(),
                                color = titleColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp)
                                    .widthIn(min = 22.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(stepperBtnBg, shape = RoundedCornerShape(8.dp))
                                    .clickable {
                                        steps = (steps + 1).coerceIn(req.minSteps, req.maxSteps)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+", color = titleColor, fontSize = 15.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stepsQualityHint(steps),
                                color = labelColor,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onConfirmGenerateImage(
                        editedPrompt.ifBlank { req.prompt },
                        selectedModel,
                        steps
                    )
                }) {
                    Text(stringResource(id = R.string.image_gen_confirm_yes), color = accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onCancelGenerateImage() }) {
                    Text(stringResource(id = R.string.image_gen_confirm_no), color = subtitleColor)
                }
            }
        )
    }

    /** ステップ数に応じた品質の目安テキストを返す。 */
    private fun stepsQualityHint(steps: Int): String = when {
        steps <= 12 -> getString(R.string.image_gen_confirm_steps_low)
        steps <= 22 -> getString(R.string.image_gen_confirm_steps_fast)
        steps <= 34 -> getString(R.string.image_gen_confirm_steps_std)
        steps <= 48 -> getString(R.string.image_gen_confirm_steps_high)
        else -> getString(R.string.image_gen_confirm_steps_max)
    }

    @Composable
    private fun ScrollToBottomSection() {
        if (!scrollToBottomVisible) return
        val bottomPadding = if (responseTypingVisible) 44.dp else 6.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.Center
        ) {
 // 「下に戻る」ボタン：太い白い矢印 + 黒縁取りで見やすくする。
            //   Compose には Text の outline 描画が直接ないので、
            //   同位置に「太い黒い矢印（stroke）」を下に、「白い矢印」を上に重ねて描く。
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable {
                        val lastIndex = adapter.itemCount - 1
                        if (lastIndex >= 0) {
                            scrollToBottom(lastIndex)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val iconTint = if (isSystemInDarkTheme()) Color.White else Color.Black
                Image(
                    painter = painterResource(id = R.drawable.arrow_downward_alt_24),
                    contentDescription = getString(R.string.scroll_to_bottom),
                    modifier = Modifier.size(28.dp),
                    colorFilter = ColorFilter.tint(iconTint)
                )
            }
        }
    }

    @Composable
    private fun HeaderActionsSection() {
 // Bug fix(#Thinking-Header):
        //   旧実装ではシンキング生成中に
        //   (compressButtonVisible=false + thinkingToggleVisible の一時的な false)
        //   となり、Column が完全に空になってヘッダー UI が消失して見えていた。
        //   シンキングトグルはモデルがシンキングをサポートする限り常時表示し、
        //   生成中は押下可否 (enabled) だけで制御する。コンテナにも
        //   最低幅 (heightIn) を与え、内容が一瞬空になってもレイアウトごと消失しないようにする。
        Column(
            modifier = Modifier.heightIn(min = 32.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (compressButtonVisible) {
                OutlinedButton(
                    onClick = { viewModel.compressContextManually() },
                    enabled = compressButtonEnabled
                ) {
                    Text(text = compressButtonText)
                }
            }
            if (thinkingToggleVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_thinking_label),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Switch(
                        checked = !thinkingToggleChecked,
                        onCheckedChange = { checked ->
                            viewModel.setChatSessionDisableThinking(!checked)
                        },
                        // Bug fix(#46):
                        //   旧実装は `enabled = thinkingToggleEnabled && !isGenerating` だったため、
                        //   生成停止直後に isGenerating フラグの更新順序次第で Switch が
                        //   触れない状態にロックされるケースがあった。
                        //   さらに ViewModel 側の setChatSessionDisableThinking は
                        //   「設定値のみ更新、次回送信時から反映」の設計なので、生成中でも
                        //   切り替えを受け付けて問題ない。ここでは !isGenerating を外し、
                        //   モデルロード中でない限り常時切り替え可能にする。
                        enabled = thinkingToggleEnabled,
                        colors = nezumiSwitchColors()
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding?.messagesRecyclerView?.removeCallbacks(autoScrollRunnable)
        autoScrollPosted = false
        responseTypingAnimationJob?.cancel()
        responseTypingAnimationJob = null
        recordingAnimationJob?.cancel()
        recordingAnimationJob = null

        // 生成中の場合はキャンセル
        viewModel.stopGeneration()

        // 録音中の場合は停止
        if (isRecordingAudio) {
            stopAudioRecording()
        }

        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroyView()
        _binding = null
    }

    /**
     * モデルが動画フレームを扱える Gemma 系 (検索可能な mediapipe LiteRT-LM) かどうか。
     * 既存の判定と同じ 4 つのモデルキーに一元化する。
     */
    private fun isGemmaVideoCapableModel(): Boolean {
        val key = currentModelKey
        return key.equals("Gemma4-2B", ignoreCase = true) ||
            key.equals("Gemma4-4B", ignoreCase = true)
    }

    /**
     * + ボタンから開くボトムシート。 UI モックのを役割に BottomSheetDialog で実装し、
     * 「画像」「カメラ」「ファイル」の 3 タイルと 「キャンセル」を見せる。
     *   - 画像   → imagePickerLauncher (ギャラリー直行)
     *   - カメラ → launchCamera()
     *   - ファイル → genericFilePickerLauncher (画像 / 動画(gemmaのみ) / 音声)
     */
    private fun showAttachmentActionSheet() {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.sheet_attachment_options, null, false)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.opt_image).setOnClickListener {
            dialog.dismiss()
            if (!imageInputEnabled) {
                Toast.makeText(ctx, getString(R.string.multimodal_image_disabled), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            imagePickerLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
        view.findViewById<View>(R.id.opt_camera).setOnClickListener {
            dialog.dismiss()
            launchCamera()
        }
        view.findViewById<View>(R.id.opt_file).setOnClickListener {
            dialog.dismiss()
            // 画像 / 動画(gemmaのみ) / 音声 を履ける MIME リストを作る。
            val mimes = mutableListOf<String>()
            if (imageInputEnabled) mimes += "image/*"
            if (imageInputEnabled && isGemmaVideoCapableModel()) mimes += "video/*"
            if (audioInputEnabled) mimes += "audio/*"
            // テキスト系ファイル (md / js / ts / cs / log / py / txt など)。
            //   拡張子だけで MIME が application/octet-stream になるものは、後段の
            //   handlePickedGenericFile() で拡張子フォールバック判定する。
            mimes += listOf("text/*", "application/json", "application/octet-stream")
            // ドキュメント (PDF / Word / Excel / PowerPoint)。
            //   バイナリ本文はモデルに直接渡せないため、送信時に Chaquopy 経由の
            //   MarkItDown で Markdown 変換し、その本文を <txtfile> としてモデルに渡す。
            mimes += listOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
            if (mimes.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.multimodal_file_disabled), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                genericFilePickerLauncher.launch(mimes.toTypedArray())
            } catch (e: Throwable) {
                Log.e("ChatFragment", "Error launching file picker", e)
                Toast.makeText(ctx, getString(R.string.file_picker_open_failed), Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<View>(R.id.opt_cancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /**
     * OpenDocument で選ばれた任意のファイルを、MIME (フォールバックで拡張子) を見て
     * 画像 / 動画 / 音声 に振り分ける。
     */
    private fun handlePickedGenericFile(uri: Uri) {
        val cr = requireContext().contentResolver
        val mime = (cr.getType(uri) ?: "").lowercase()
        val fallbackExt = try {
            uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        } catch (_: Throwable) { "" }

        val isImage = mime.startsWith("image/") ||
            fallbackExt in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        val isVideo = mime.startsWith("video/") ||
            fallbackExt in listOf("mp4", "mov", "m4v", "webm", "3gp", "mkv", "avi")
        val isAudio = mime.startsWith("audio/") ||
            fallbackExt in listOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "amr")
        // プレーンテキストとして読めるものをテキスト添付として受け付ける。
        //   md / js / ts / cs / log / txt / py 以外にも、ソースコード・設定・データ交換系の
        //   プレーンテキスト拡張子を広めに拾う (MIME が text/* のものも含む)。
        // SAF の content:// URI では lastPathSegment に拡張子が無いことがあるため、
        // 表示名 (DISPLAY_NAME) 側の拡張子でも判定する。
        val displayNameExt = extractDisplayName(uri.toString(), fallback = "")
            .substringAfterLast('.', "")
            .lowercase()
        val isText = mime.startsWith("text/") ||
            mime == "application/json" ||
            fallbackExt in TEXT_FILE_EXTENSIONS ||
            displayNameExt in TEXT_FILE_EXTENSIONS
        // ドキュメント (PDF / Word / Excel / PowerPoint) 判定。
        //   表示名の拡張子も見るのは isText と同じ理由 (SAF 経由だと lastPathSegment に
        //   拡張子が載らないことがあるため)。
        val isDocument = mime == "application/pdf" ||
            mime == "application/msword" ||
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mime == "application/vnd.ms-excel" ||
            mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            mime == "application/vnd.ms-powerpoint" ||
            mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            com.nezumi_ai.data.media.TextFileAttachmentEncoding.isDocumentFile(
                extractDisplayName(uri.toString(), fallback = "")
            )

        when {
            isImage -> {
                if (!imageInputEnabled) {
                    Toast.makeText(requireContext(), getString(R.string.multimodal_image_disabled), Toast.LENGTH_SHORT).show()
                    return
                }
                if (selectedImageUrisList.size >= MAX_SELECTABLE_IMAGES) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.multimodal_image_max_reached, MAX_SELECTABLE_IMAGES),
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                selectedImageUrisList = selectedImageUrisList + uri.toString()
                updateMediaPreview()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.multimodal_image_added, selectedImageUrisList.size, MAX_SELECTABLE_IMAGES),
                    Toast.LENGTH_SHORT
                ).show()
            }
            isVideo -> {
                if (!imageInputEnabled) {
                    Toast.makeText(requireContext(), getString(R.string.multimodal_video_disabled), Toast.LENGTH_SHORT).show()
                    return
                }
                if (!isGemmaVideoCapableModel()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.multimodal_video_gemma3n_only),
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                processPickedVideo(uri)
            }
            isAudio -> {
                if (!audioInputEnabled) {
                    Toast.makeText(requireContext(), getString(R.string.multimodal_audio_disabled), Toast.LENGTH_SHORT).show()
                    return
                }
                selectedAudioUri = uri.toString()
                updateMediaPreview()
                Toast.makeText(requireContext(), getString(R.string.multimodal_audio_added), Toast.LENGTH_SHORT).show()
            }
            isDocument -> {
                // ドキュメント (PDF/Word/Excel/PowerPoint) はピック時点で
                // Markdown に変換する (動画のフレーム抽出と同じ思想)。
                // 変換が済んでから送信可能になり、変換後の .md はビュワーで閲覧できる。
                val displayName = extractDisplayName(uri.toString(), fallback = "document.pdf")
                processPickedDocument(uri, displayName)
            }
            isText -> {
                val displayName = extractDisplayName(uri.toString(), fallback = "text.txt")
                val entry = com.nezumi_ai.data.media.TextFileAttachmentEncoding.TextFileEntry(
                    name = displayName,
                    uri = uri.toString()
                )
                if (selectedTextFiles.any { it.uri == entry.uri }) {
                    Toast.makeText(requireContext(), getString(R.string.attachment_already_added), Toast.LENGTH_SHORT).show()
                    return
                }
                selectedTextFiles = selectedTextFiles + entry
                updateMediaPreview()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.txtfile_added_toast, displayName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            else -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.unsupported_file_format, mime),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun launchCamera() {
        if (!imageInputEnabled) {
            Toast.makeText(requireContext(), getString(R.string.multimodal_image_disabled), Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchCameraInternal()
    }

    private fun launchCameraInternal() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            // #10 fix: ACTION_IMAGE_CAPTURE 'data' extra only returns thumbnail (128-240px).
            // Use MediaStore.EXTRA_OUTPUT with a URI to capture full-size image.
            val cameraDir = java.io.File(requireContext().cacheDir, "camera").also { it.mkdirs() }
            val imageFile = java.io.File(cameraDir, "IMG_${System.currentTimeMillis()}.jpg")
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                imageFile
            )
            cameraImageUri = fileUri
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri)
            cameraIntent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            cameraIntent.clipData = ClipData.newUri(
                requireContext().contentResolver,
                "ImageCapture",
                fileUri
            )
            cameraLauncher.launch(cameraIntent)
        } catch (e: Exception) {
            Log.e("ChatFragment", "Camera app not found", e)
            Toast.makeText(requireContext(), getString(R.string.camera_app_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteFromClipboard() {
        if (!imageInputEnabled) {
            Toast.makeText(requireContext(), getString(R.string.multimodal_image_disabled), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            val primaryClip = clipboard.primaryClip

            if (primaryClip != null && primaryClip.itemCount > 0) {
                val item = primaryClip.getItemAt(0)

                // Phase 11: 複数画像対応（最大5枚まで）
                // URIが直接利用可能な場合
                if (item.uri != null) {
                    val uri = item.uri
 // 5枚制限に達している場合は、キャッシュファイルを作らずに返す
                    if (selectedImageUrisList.size >= 5) {
                        Toast.makeText(requireContext(), "Max 5 images allowed", Toast.LENGTH_SHORT).show()
                        return
                    }
                    // キャッシュディレクトリにコピー (use ブロックで IS/OS リークを防ぐ)
                    try {
                        val cacheDir = java.io.File(requireContext().cacheDir, "clipboard")
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        val cachedFile = java.io.File(cacheDir, "IMG_${System.currentTimeMillis()}.jpg")
                        val inputStream = requireContext().contentResolver.openInputStream(uri)
                        if (inputStream == null) {
                            Toast.makeText(requireContext(), getString(R.string.clipboard_image_fetch_failed), Toast.LENGTH_SHORT).show()
                            return
                        }
                        inputStream.use { input ->
                            java.io.FileOutputStream(cachedFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        // FileProviderでURIを取得
                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.fileprovider",
                            cachedFile
                        )
                        selectedImageUrisList = selectedImageUrisList + fileUri.toString()
                        updateMediaPreview()
                        Toast.makeText(requireContext(), getString(R.string.clipboard_image_pasted, selectedImageUrisList.size, 5), Toast.LENGTH_SHORT).show()
                        Log.d("ChatFragment", "Image pasted from clipboard: ${cachedFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "Error processing clipboard URI", e)
                        Toast.makeText(requireContext(), getString(R.string.clipboard_image_fetch_failed), Toast.LENGTH_SHORT).show()
                    }
                } else if (item.text != null) {
                    // テキストがコピーされている場合（テキストURLなど）
                    val text = item.text.toString()
                    if (text.startsWith("content://") || text.startsWith("file://")) {
                        try {
                            val uri = Uri.parse(text)
                            // 複数画像リストに追加（最大5枚まで）
                            if (selectedImageUrisList.size < 5) {
                                selectedImageUrisList = selectedImageUrisList + uri.toString()
                                updateMediaPreview()
                                Toast.makeText(requireContext(), getString(R.string.clipboard_uri_pasted, selectedImageUrisList.size, 5), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Max 5 images allowed", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("ChatFragment", "Invalid URI in clipboard", e)
                            Toast.makeText(requireContext(), getString(R.string.clipboard_invalid_uri), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.clipboard_no_valid_data), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.clipboard_is_empty), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Error accessing clipboard", e)
            Toast.makeText(requireContext(), getString(R.string.clipboard_access_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchAudioRecording() {
        if (!audioInputEnabled) {
            Toast.makeText(requireContext(), getString(R.string.multimodal_audio_disabled), Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        startAudioRecording()
    }

    @Suppress("DEPRECATION")
    private fun startAudioRecording() {
        try {
            // 録音開始
            isRecordingAudio = true

            // 録音ファイルの作成
            val recordingDir = java.io.File(requireContext().cacheDir, "recordings")
            if (!recordingDir.exists()) {
                recordingDir.mkdirs()
            }
            recordingFile = java.io.File(recordingDir, "REC_${System.currentTimeMillis()}.m4a")

            // MediaRecorderの初期化
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(requireContext())
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(recordingFile?.absolutePath)
                // 上限 30 秒。長時間録音による端末側 OOM / STT レイテンシ悪化を回避する。
                setMaxDuration(MAX_RECORDING_DURATION_MS)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        // メインスレッドで UI 後処理も含めて停止する。
                        _binding?.root?.post {
                            if (isRecordingAudio) {
                                stopAudioRecording()
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.recording_limit_reached, (MAX_RECORDING_DURATION_MS / 1000).toInt()),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                prepare()
                start()
            }

            // 録音 UI をインラインに切り替える
            //  - テキスト入力を隠して録音バーを見せる
            //  - + ボタンを一時的に隠して誤タップを防ぐ
            //  - マイクボタンは「停止」へ
            showInlineRecordingBar()
            binding.messageInput.isEnabled = false
            binding.mediaMenuButton.isEnabled = false

            // 録音アニメーションを開始
            startRecordingAmplitudeAnimation()
        } catch (e: Exception) {
            Log.e("ChatFragment", "Error starting audio recording", e)
            Toast.makeText(requireContext(), getString(R.string.recording_start_failed), Toast.LENGTH_SHORT).show()
            isRecordingAudio = false
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopAudioRecording() {
        try {
            if (mediaRecorder != null && isRecordingAudio) {
                mediaRecorder?.apply {
                    try {
                        stop()
                        release()
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "Error stopping audio recording", e)
                    }
                }
                mediaRecorder = null
                isRecordingAudio = false

                // アニメーション停止
                recordingAnimationJob?.cancel()
                recordingAnimationJob = null

                // hintを元に戻す（cancelするとアニメJob内の後処理が走らないため明示的に戻す）
                _binding?.messageInput?.hint = getString(R.string.chat_input_hint)

                // インライン録音バーを閉じて通常の入力 UI に戻す
                hideInlineRecordingBar()

                if (_binding != null) {
                    renderSendButtonState()
                }
                _binding?.messageInput?.isEnabled = true
                _binding?.mediaMenuButton?.isEnabled = true

                // キャンセル時は録音ファイルを破棄する。
                if (discardRecordingOnStop) {
                    try { recordingFile?.delete() } catch (_: Throwable) {}
                    recordingFile = null
                    discardRecordingOnStop = false
                    return
                }

                // 録音ファイルをコンテキストに追加
                if (recordingFile != null && recordingFile!!.exists()) {
                    try {
                        val recordingUri = androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.fileprovider",
                            recordingFile!!
                        )
                        selectedAudioUri = recordingUri.toString()
                        updateMediaPreview()
                        Toast.makeText(requireContext(), getString(R.string.multimodal_audio_added), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "Error creating FileProvider URI for recording", e)
                        Toast.makeText(requireContext(), getString(R.string.recording_audio_process_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Error stopping audio recording", e)
            Toast.makeText(requireContext(), getString(R.string.recording_stop_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * モーダルの代わりに EditText に重ねて表示させるインライン録音バーを開く。
     * バーの実体は fragment_chat.xml の @+id/inline_record_bar で、
     * EditText と同じ FrameLayout に入っているので visibility だけで切り替わる。
     */
    private fun showInlineRecordingBar() {
        val b = _binding ?: return
        b.messageInput.visibility = View.INVISIBLE
        b.inlineRecordBar.visibility = View.VISIBLE
        b.inlineRecordTime.text = "0:00"
        b.micButton.isSelected = true
        b.micButton.setImageResource(R.drawable.ic_stop)
        // 録音中アイコンを白にするため tint をリセット。
        b.micButton.imageTintList = null
        b.micButton.contentDescription = requireContext().getString(R.string.audio_stop_and_send)
        // 録音リストを保持して、アニメの少ないフレームでも参照できるようにする。
        recordingWaveBars = listOf(
            b.inlineWave1, b.inlineWave2, b.inlineWave3, b.inlineWave4,
            b.inlineWave5, b.inlineWave6, b.inlineWave7, b.inlineWave8
        )
        recordingStatusTextView = b.inlineRecordTime
    }

    private fun hideInlineRecordingBar() {
        val b = _binding ?: return
        b.messageInput.visibility = View.VISIBLE
        b.inlineRecordBar.visibility = View.GONE
        b.micButton.isSelected = false
        b.micButton.setImageResource(R.drawable.ic_mic)
        // 平常時のアイコン色に戻す。
        b.micButton.imageTintList = ColorStateList.valueOf(
            requireContext().getColor(R.color.text_secondary)
        )
        b.micButton.contentDescription = requireContext().getString(R.string.audio_input)
        recordingWaveBars = emptyList()
        recordingStatusTextView = null
    }

    /** 録音を破棄して入力に戻す（インラインバーの「削除」ボタン向け） */
    private fun cancelAudioRecording() {
        if (!isRecordingAudio) {
            // 録音中ではないがバーが残っている場合に備えて閉じる。
            hideInlineRecordingBar()
            return
        }
        discardRecordingOnStop = true
        stopAudioRecording()
    }

    private fun startRecordingAmplitudeAnimation() {
        recordingAnimationJob?.cancel()

        recordingAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            var dotCount = 0
            val startedAt = System.currentTimeMillis()
            while (isRecordingAudio && mediaRecorder != null) {
                try {
                    // ドット数を循環（1個 → 2個 → 3個 → 1個）
                    dotCount = (dotCount % 3) + 1
                    val dots = ".".repeat(dotCount)
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    val remainSec = ((MAX_RECORDING_DURATION_MS - elapsedMs).coerceAtLeast(0L) / 1000L).toInt()

                    withContext(Dispatchers.Main) {
                        // インラインバーではタイマーを mm:ss で見せる。
                        val recSec = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                        val mm = recSec / 60
                        val ss = recSec % 60
                        _binding?.inlineRecordTime?.text = String.format("%d:%02d", mm, ss)
                        val density = requireContext().resources.displayMetrics.density
                        recordingWaveBars.forEachIndexed { index, bar ->
                            // インラインバーは 22dp 高さ。 6dp 〜 21dp の間でバーを揺らす。
                            val heightDp = 6 + ((dotCount + index) % 6) * 3
                            bar.layoutParams = bar.layoutParams.apply {
                                height = (heightDp * density).toInt()
                            }
                            bar.requestLayout()
                        }
                    }

                    delay(500) // 500msごとに更新
                } catch (e: Exception) {
                    Log.d("ChatFragment", "Recording animation error", e)
                }
            }

            // アニメーション終了時にプレースホルダーを元に戻す
            withContext(Dispatchers.Main) {
                _binding?.messageInput?.hint = getString(R.string.chat_input_hint)
            }
        }
    }

    /**
     * 生成を停止します。スクリーンがオフになった場合に外部から呼ばれます。
     */
    fun stopGeneration() {
        try {
            viewModel.stopGeneration()
            Log.d("ChatFragment", "Generation stopped on screen off")
        } catch (e: Exception) {
            Log.w("ChatFragment", "Failed to stop generation", e)
        }
    }

    private class CpuUsageSampler {
        private var lastProcJiffies: Long? = null
        private var lastTotalJiffies: Long? = null

        fun sampleProcessCpuPercent(): Int {
            val total = readTotalCpuJiffies() ?: return 0
            val proc = readProcessCpuJiffies() ?: return 0

            val prevProc = lastProcJiffies
            val prevTotal = lastTotalJiffies
            lastProcJiffies = proc
            lastTotalJiffies = total

            if (prevProc == null || prevTotal == null) return 0
            val procDelta = (proc - prevProc).coerceAtLeast(0L)
            val totalDelta = (total - prevTotal).coerceAtLeast(1L)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val percent = (procDelta * 100.0 * cores.toDouble()) / totalDelta.toDouble()
            return percent.toInt().coerceIn(0, 100)
        }

        private fun readTotalCpuJiffies(): Long? {
            return runCatching {
                val line = java.io.File("/proc/stat").useLines { lines ->
                    lines.firstOrNull { it.startsWith("cpu ") }
                } ?: return null
                line.trim().split(Regex("\\s+"))
                    .drop(1)
                    .mapNotNull { it.toLongOrNull() }
                    .sum()
            }.getOrNull()
        }

        private fun readProcessCpuJiffies(): Long? {
            return runCatching {
                val stat = java.io.File("/proc/self/stat").readText()
                val end = stat.lastIndexOf(')')
                if (end <= 0) return null
                val tail = stat.substring(end + 2).trim()
                val parts = tail.split(Regex("\\s+"))
                if (parts.size <= 13) return null
                val utime = parts[11].toLongOrNull() ?: return null
                val stime = parts[12].toLongOrNull() ?: return null
                utime + stime
            }.getOrNull()
        }
    }
}
