package com.nezumi_ai.presentation.ui.fragment

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
import com.nezumi_ai.databinding.FragmentChatBinding
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.stripGemmaTokens
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.presentation.viewmodel.ChatViewModel
import com.nezumi_ai.presentation.viewmodel.ChatViewModelFactory
import com.nezumi_ai.presentation.ui.adapter.MessageAdapter
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.presentation.ui.composable.ToolCallProgressBar
import com.nezumi_ai.presentation.ui.composable.MediaPreviewBar
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlin.math.max

class ChatFragment : Fragment(R.layout.fragment_chat) {
    
    companion object {
        private const val TAG = "ChatFragment"
    }
    
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: ChatViewModel
    private lateinit var adapter: MessageAdapter
    private val args: ChatFragmentArgs by navArgs()
    private var modelOptions: List<ModelOption> = emptyList()
    private var responseTypingAnimationJob: Job? = null
    private lateinit var settingsRepository: SettingsRepository
    private var isGenerating = false
    private var isModelLoadingNow = false
    private var currentBackendType = "CPU"
    private var currentModelKey = "E2B"
    private var isCompressingNow = false
    private var responseTypingVisible by mutableStateOf(false)
    private var responseTypingText by mutableStateOf("")
    private var modelLoadingOverlayVisible by mutableStateOf(false)
    private var modelLoadingText by mutableStateOf("")
    private var contextMeterText by mutableStateOf("")
    private var contextMeterProgress by mutableStateOf(0f)
    private var contextUsageCharsNow by mutableStateOf(0)
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
    private var gemmaThinkingGloballyEnabled = false
    private var messagesIsEmpty by mutableStateOf(true)
    private var isUserAtBottom = true
    private var wasImeVisible = false
    private var autoScrollPosted = false
    private val autoScrollDebounceMs = 48L
    private val autoFollowMaxFrames = 18
    private val immediateScrollMaxFrames = 10
    private val autoFollowBottomThresholdPx = 120
    private var lastKnownScrollRange = 0
    private var pendingInitialScrollToBottom = true
    // 生成中にユーザーが意図的に上スクロールしたときだけ true。
    // 最下部に戻るか送信するとリセット。
    private var userScrolledAwayDuringGeneration = false
    // 自動追従中は、テーブル列追加などの大きな再レイアウトで底判定が一瞬外れても維持する。
    // ユーザーが明示的に上へドラッグした時だけ false にする。
    private var autoFollowBottomLocked = true

    private data class ScrollAnchor(val position: Int, val offset: Int)

    private val autoScrollRunnable = Runnable {
        autoScrollPosted = false
        followBottomAfterLayout()
    }
    
    // Phase 11: 複数画像対応（Compose State管理で UI 再構成を自動化）
    private var selectedImageUrisList by mutableStateOf<List<String>>(emptyList())
    private var selectedAudioUri by mutableStateOf<String?>(null)  // State管理化
    private var cameraImageUri: Uri? = null
    private var imageInputEnabled = true
    private var audioInputEnabled = true
    
    // 音声録音関連
    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingAudio = false
    private var recordingAnimationJob: Job? = null
    private var recordingFile: java.io.File? = null
    
    
    // Phase 11: 複数画像選択
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!imageInputEnabled) {
            Toast.makeText(requireContext(), "このモデルでは画像入力は無効です", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (uris.isNotEmpty()) {
            val newUris = uris.take(5).map { it.toString() }  // 最大5枚まで
            selectedImageUrisList = (selectedImageUrisList + newUris).take(5)
            Toast.makeText(requireContext(), "${newUris.size}個の画像を選択しました (${selectedImageUrisList.size}/5)", Toast.LENGTH_SHORT).show()
            updateMediaPreview()
        }
    }
    
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val extras = result.data?.extras
            @Suppress("DEPRECATION")
            val bitmap = extras?.getParcelable<android.graphics.Bitmap>("data")
            
            if (bitmap != null) {
                try {
                    // Bitmapをファイルに保存
                    val cameraDir = java.io.File(requireContext().cacheDir, "camera")
                    if (!cameraDir.exists()) {
                        cameraDir.mkdirs()
                    }
                    
                    val imageFile = java.io.File(cameraDir, "IMG_${System.currentTimeMillis()}.jpg")
                    val fos = java.io.FileOutputStream(imageFile)
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
                    fos.close()
                    
                    // FileProviderでURIを取得
                    cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(),
                        "com.nezumi_ai.fileprovider",
                        imageFile
                    )
                    
                    // 複数画像対応：リストに追加
                    if (selectedImageUrisList.size < 5) {
                        selectedImageUrisList = selectedImageUrisList + cameraImageUri.toString()
                        Log.d("ChatFragment", "Camera image added: ${imageFile.absolutePath}")
                        Toast.makeText(requireContext(), "写真を撮影しました (${selectedImageUrisList.size}/5)", Toast.LENGTH_SHORT).show()
                        updateMediaPreview()
                    } else {
                        Toast.makeText(requireContext(), "最大5枚までしか選択できません", Toast.LENGTH_SHORT).show()
                    }
                    
                    bitmap.recycle()
                } catch (e: Exception) {
                    Log.e("ChatFragment", "Error saving camera bitmap", e)
                    Toast.makeText(requireContext(), "画像の保存に失敗しました", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "画像データを取得できませんでした", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d("ChatFragment", "Camera cancelled by user")
        }
    }
    
    private val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (!audioInputEnabled) {
            Toast.makeText(requireContext(), "このモデルでは音声入力は無効です", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (uri != null) {
            selectedAudioUri = uri.toString()
            Toast.makeText(requireContext(), "音声を選択しました", Toast.LENGTH_SHORT).show()
            updateMediaPreview()
        }
    }
    
    // 権限リクエストランチャー
    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCameraInternal()
        } else {
            Toast.makeText(requireContext(), "カメラの権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val recordPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startAudioRecording()
        } else {
            Toast.makeText(requireContext(), "マイクの権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMediaPreview() {
        // Phase 11: 複数画像対応
        if (selectedImageUrisList.isEmpty() && selectedAudioUri.isNullOrEmpty()) {
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
        compressButtonText = getString(R.string.compress_context)
        thinkingToggleText = getString(R.string.chat_thinking_follow_settings)
        setupComposeIndicators()
        
        // ViewModel初期化
        val database = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository(database.settingsDao(), database.chatSessionDao())
        val sessionRepository = ChatSessionRepository(database.chatSessionDao(), settingsRepository)
        val messageRepository = MessageRepository(database.messageDao())
        val factory = ChatViewModelFactory(
            requireContext().applicationContext,
            sessionRepository,
            messageRepository,
            settingsRepository
        )
        viewModel = ViewModelProvider(this, factory).get(ChatViewModel::class.java)
        setupModelDropdown()
        
        // RecyclerView設定（adapterの初期化をStateFlowのcollect前に移動）
        adapter = MessageAdapter(
            onUserPromptRevoke = { message ->
                viewModel.revokePromptFromMessage(message.id)
            },
            onAiMessageLayoutChanged = {
                if (shouldAutoFollowBottom()) {
                    scheduleAutoScrollToBottom()
                }
            },
            lifecycleOwner = viewLifecycleOwner,
            viewModelStoreOwner = this
        )
        binding.messagesRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = false
        }
        binding.messagesRecyclerView.adapter = adapter
        (binding.messagesRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        
        // ★ バグ修正: RecyclerView のスクロール状態をリアルタイム監視
        binding.messagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                // ★ ユーザーが手動でドラッグしたことを検出
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    // ドラッグ中は自動追従をロック解除
                    autoFollowBottomLocked = false
                    userScrolledAwayDuringGeneration = true
                    Log.d(TAG, "USER_SCROLL: Manual drag detected - autoFollowBottomLocked=false, userScrolledAwayDuringGeneration=true")
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // ★ スクロールが止まった後に判定を更新
                    // ユーザーが底部に手動スクロールで戻ったら、自動追従を再開
                    if (isGenerating && isNearBottom(recyclerView)) {
                        autoFollowBottomLocked = true
                        userScrolledAwayDuringGeneration = false
                        Log.d(TAG, "USER_SCROLL_BACK_TO_BOTTOM: Re-enabling auto-follow")
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // ★ スクロール中に底部判定を更新（自動スクロルループ抜け出し判定用）
                isUserAtBottom = !recyclerView.canScrollVertically(1)
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
        
        // Observe incognito mode and apply security settings
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isIncognitoMode.collect { isIncognito ->
                applyIncognitoModeSettings(isIncognito)
                updateIncognitoModeIndicator(isIncognito)
            }
        }

        // nav args から incognito フラグを適用
        if (args.isIncognito) {
            viewModel.setIncognitoMode(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.getSettings().collect { settings ->
                gemmaThinkingGloballyEnabled = settings?.gemmaThinkingEnabled == true
                contextCompressionEnabled = settings?.contextCompressionEnabled == true
                adapter.setThinkingVisible(gemmaThinkingGloballyEnabled)
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
                        Log.d("ChatFragment", "Restoring previous session: $savedSessionId")
                        // ★ setCurrentSession は suspend 関数に変更されたため、直接 await する
                        viewModel.setCurrentSession(savedSessionId)
                    } else {
                        Log.d("ChatFragment", "No saved session found. Creating new session.")
                        val database = NezumiAiDatabase.getInstance(requireContext())
                        val sessionRepository = ChatSessionRepository(
                            database.chatSessionDao(),
                            settingsRepository,
                            MessageRepository(database.messageDao())
                        )
                        val newSessionId = sessionRepository.createSession("新しいチャット")
                        settingsRepository.saveCurrentSessionId(newSessionId)
                        // ★ setCurrentSession は suspend 関数に変更されたため、直接 await する
                        viewModel.setCurrentSession(newSessionId)
                    }
                } catch (e: Exception) {
                    Log.e("ChatFragment", "Failed to handle session", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "セッション処理に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    settingsRepository.saveCurrentSessionId(sessionId)
                    // ★ setCurrentSession は suspend 関数に変更されたため、直接 await する
                    viewModel.setCurrentSession(sessionId)
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
                val filteredMessages = displayMessages.map { msg ->
                    msg.copy(content = msg.content.stripGemmaTokens())
                }
                messagesIsEmpty = filteredMessages.isEmpty()
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
            val message = binding.messageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                val imagesToSend = if (imageInputEnabled) selectedImageUrisList else emptyList()
                val audioToSend = if (audioInputEnabled) selectedAudioUri else null
                userScrolledAwayDuringGeneration = false
                autoFollowBottomLocked = true
                viewModel.sendMessageWithMedia(message, imagesToSend, audioToSend)
                binding.messageInput.text?.clear()
                selectedImageUrisList = emptyList()
                selectedAudioUri = null
                updateMediaPreview()
            }
        }

        binding.messageInput.onClipboardImagePaste = {
            pasteFromClipboard()
        }

        binding.mediaMenuButton.setOnClickListener { view ->
            if (!imageInputEnabled && !audioInputEnabled) {
                Toast.makeText(requireContext(), "このモデルは画像・音声入力に対応していません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val popupMenu = PopupMenu(requireContext(), view)
            popupMenu.menuInflater.inflate(R.menu.menu_media_select, popupMenu.menu)
            if (!imageInputEnabled) {
                popupMenu.menu.findItem(R.id.menu_select_image)?.isVisible = false
                popupMenu.menu.findItem(R.id.menu_camera)?.isVisible = false
                popupMenu.menu.findItem(R.id.menu_clipboard_paste)?.isVisible = false
            }
            if (!audioInputEnabled) {
                popupMenu.menu.findItem(R.id.menu_select_audio)?.isVisible = false
                popupMenu.menu.findItem(R.id.menu_record_audio)?.isVisible = false
            }
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_select_image -> { imagePickerLauncher.launch("image/*"); true }
                    R.id.menu_camera -> { launchCamera(); true }
                    R.id.menu_clipboard_paste -> { pasteFromClipboard(); true }
                    R.id.menu_select_audio -> { audioPickerLauncher.launch("audio/*"); true }
                    R.id.menu_record_audio -> { launchAudioRecording(); true }
                    else -> false
                }
            }
            popupMenu.show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                isGenerating = isLoading
                renderSendButtonState()
                renderModelDropdownState()
                if (isLoading) {
                    userScrolledAwayDuringGeneration = false
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isCompressing.collect { compressing ->
                isCompressingNow = compressing
                renderCompressButtonState()
                if (isGenerating) {
                    responseTypingText =
                        if (isCompressingNow) getString(R.string.response_compressing)
                        else getString(R.string.response_generating)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedModel.collect { model ->
                currentModelKey = model
                refreshCurrentBackendType()
                updateMediaAvailability(model)
                updateThinkingToggleVisibility()
                val selected = modelOptions.firstOrNull { it.key == model } ?: return@collect
                if (binding.modelDropdown.text?.toString() != selected.label) {
                    binding.modelDropdown.setText(selected.label, false)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sessionTitle.collect { title ->
                binding.chatTitle.text = title
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiMessage.collect { message ->
                val duration = if (message.startsWith("🔧 実行ツール")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
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
                viewModel.contextWindowCapacityChars
            ) { used, maxChars ->
                Pair(used, maxChars)
            }.collect { (used, maxChars) ->
                contextUsageCharsNow = used
                contextMeterText = getString(R.string.context_meter_format, used, maxChars)
                contextMeterProgress =
                    (((used.toLong() * 1000L) / maxChars.toLong()).toInt().coerceIn(0, 1000) / 1000f)
                renderCompressButtonState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isModelLoading.collect { loading ->
                isModelLoadingNow = loading
                modelLoadingOverlayVisible = loading
                binding.backButton.isEnabled = !loading
                renderModelDropdownState()
                renderSendButtonState()
                renderCompressButtonState()
                binding.messageInput.isEnabled = !loading
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.memoryWarning.collect { warning ->
                if (warning != null) showMemoryWarningDialog(warning)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isCompressing.collect { compressing ->
                isCompressingNow = compressing
                binding.messageInput.isEnabled = !compressing
                binding.sendButton.isEnabled = !compressing
                renderCompressButtonState()
                renderSendButtonState()
                if (compressing) startResponseTypingAnimation() else stopResponseTypingAnimation()
            }
        }

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
        } else {
            requireActivity().window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
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
        val imeOptions = if (disable) {
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            0
        }
        
        // Find all EditText views and update IME options
        updateEditTextImeOptions(binding.root, imeOptions, disable)
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
        setupModelDropdown()
        refreshCurrentBackendType()
        updateMediaAvailability(currentModelKey)
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
        if (!isGenerating) return false
        if (userScrolledAwayDuringGeneration) return false
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
        if (!isGenerating || userScrolledAwayDuringGeneration) return
        // ★ バグ修正: ドラッグ中だけでなく、SCROLL_STATE_SETTLING(inertia scroll中)も判定
        if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            Log.d(TAG, "AUTOSCROLL_STOP: ScrollState=${rv.scrollState} - user interaction detected")
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
        scrollToBottomImmediate()
    }

    private fun isAtBottom(): Boolean {
        val rv = binding.messagesRecyclerView
        return !rv.canScrollVertically(1)
    }

    private fun updateScrollToBottomButtonVisibility() {
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

    private fun setupModelDropdown() {
        modelOptions = buildDownloadedModelOptions()
        if (modelOptions.isEmpty()) {
            binding.modelDropdown.setText(getString(R.string.model_not_downloaded), false)
            binding.modelDropdown.isEnabled = false
            binding.modelDropdownLayout.isEnabled = false
            return
        }

        val labels = modelOptions.map { it.label }
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            labels
        )
        binding.modelDropdown.setAdapter(spinnerAdapter)
        renderModelDropdownState()
        syncSelectedModelLabel()
        binding.modelDropdown.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                val selected = modelOptions.getOrNull(position) ?: return@OnItemClickListener
                viewModel.switchModel(selected.key)
            }
    }

    private fun buildDownloadedModelOptions(): List<ModelOption> {
        val options = mutableListOf<ModelOption>()
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA3N_2B)) {
            options += ModelOption("Gemma3n-2B", "Gemma 3n 2B")
        }
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA3N_4B)) {
            options += ModelOption("Gemma3n-4B", "Gemma 3n 4B")
        }
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA4_2B)) {
            options += ModelOption("Gemma4-2B", "Gemma 4 2B")
        }
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.GEMMA4_4B)) {
            options += ModelOption("Gemma4-4B", "Gemma 4 4B")
        }
        ModelFileManager.listImportedTaskModels(requireContext()).forEach { imported ->
            options += ModelOption(imported.path, imported.name)
        }
        return options
    }

    private data class ModelOption(
        val key: String,
        val label: String
    )

    private fun syncSelectedModelLabel() {
        val selected = modelOptions.firstOrNull { it.key == viewModel.selectedModel.value }
            ?: modelOptions.firstOrNull()
            ?: return
        binding.modelDropdown.setText(selected.label, false)
    }

    private fun renderSendButtonState() {
        // 生成中は常に「停止(四角)」を優先表示
        binding.sendButton.text =
            if (isGenerating) getString(R.string.stop_icon) else getString(R.string.send_icon)
        // モデルロード中のみ操作不可
        binding.sendButton.isEnabled = !isModelLoadingNow
    }

    private fun updateMediaAvailability(modelKey: String) {
        val caps = ImportedModelCapabilityStore.resolveForModel(requireContext(), modelKey)
        imageInputEnabled = caps.imageEnabled
        audioInputEnabled = caps.audioEnabled

        // 非対応メディアは選択状態を破棄し、送信対象から除外
        if (!imageInputEnabled) {
            selectedImageUrisList = emptyList()  // Phase 11: 複数画像対応
        }
        if (!audioInputEnabled) {
            selectedAudioUri = null
        }
        updateMediaPreview()
        binding.mediaMenuButton.visibility =
            if (imageInputEnabled || audioInputEnabled) View.VISIBLE else View.GONE
    }

    private fun renderModelDropdownState() {
        val enabled = !isModelLoadingNow && !isGenerating && modelOptions.isNotEmpty()
        binding.modelDropdown.isEnabled = enabled
        binding.modelDropdownLayout.isEnabled = enabled
    }

    private fun renderCompressButtonState() {
        val enabled = !isModelLoadingNow && !isGenerating && contextUsageCharsNow > 0
        compressButtonVisible = contextCompressionEnabled && !isCompressingNow
        compressButtonEnabled = enabled
        compressButtonText =
            if (isCompressingNow) getString(R.string.compress_context_busy)
            else getString(R.string.compress_context)
        // シンキングON/OFFはチャット生成中でも切り替え可能にする（次回送信から反映）
        thinkingToggleEnabled = !isModelLoadingNow && thinkingToggleVisible
    }

    private fun updateThinkingToggleVisibility() {
        val modelSupportsThinking = settingsRepository.modelSupportsGemmaThinking(currentModelKey)
        thinkingToggleVisible = modelSupportsThinking && gemmaThinkingGloballyEnabled
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
                    getString(R.string.response_compressing)
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

        binding.toolCallProgressCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.toolCallProgressCompose.setContent {
            // 空描画（ツール実行ポップアップを表示しない）
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
            MediaPreviewBar(
                hasImage = selectedImageUrisList.isNotEmpty(),
                hasAudio = selectedAudioUri != null,
                imageUris = selectedImageUrisList,  // Phase 11: 複数画像URI を渡す
                onClearImage = { selectedImageUrisList = emptyList() },
                onRemoveImage = { index ->  // Phase 11: 個別削除機能
                    // ★ バグ修正: selectedImageUrisList 状態更新のみで十分
                    // updateMediaPreview() を呼ぶと Recomposition 競合が発生し、画像プレビューがおかしくなる
                    if (index in selectedImageUrisList.indices) {
                        selectedImageUrisList = selectedImageUrisList.filterIndexed { i, _ -> i != index }
                        // updateMediaPreview() は呼ばない（状態更新で自動 Recomposition される）
                    }
                },
                audioUri = selectedAudioUri,  // Phase 12: 音声URI を渡す
                onClearAudio = { selectedAudioUri = null }
            )
        }

        binding.emptyStateCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.emptyStateCompose.setContent {
            EmptyStateScreen()
        }
    }

    private fun showMemoryWarningDialog(warning: ChatViewModel.MemoryWarningInfo) {
        val systemMemInfo = com.nezumi_ai.data.inference.MemoryObserver.getSystemMemoryInfo(requireContext())
        val alertDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("⚠️ メモリ警告")
            .setMessage(
                "モデル「${warning.modelName}」のロードは高メモリ使用率になる可能性があります。\n\n" +
                "━━━ デバイスメモリ ━━━\n" +
                "スマホ本体: ${systemMemInfo.usedMemoryMB}MB / ${systemMemInfo.totalMemoryMB}MB\n" +
                "使用率: ${systemMemInfo.usedPercent}%\n" +
                "${if (systemMemInfo.lowMemoryFlag) "⚠️ デバイスがメモリ不足状態です" else "✓ 正常"}\n\n" +
                "━━━ アプリメモリ ━━━\n" +
                "現在: ${warning.currentUsageMB}MB / ${warning.maxMB}MB (${warning.currentUsagePercent}%)\n" +
                "予想: ${warning.predictedUsagePercent}%\n\n" +
                "ロードを続行しますか？"
            )
            .setPositiveButton("続行") { _, _ ->
                // Fragment View が存在するなら viewLifecycleOwner を使用、破棄されているなら main dispatcher で実行
                val scope = if (view != null && isAdded) {
                    try {
                        viewLifecycleOwner.lifecycleScope
                    } catch (e: Exception) {
                        MainScope()  // Fallback
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
            }
            .setNegativeButton("キャンセル") { _, _ ->
                viewModel.cancelMemoryWarningAndGoHome()
            }
            .setCancelable(false)
            .create()
        alertDialog.show()
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
                painter = painterResource(id = R.drawable.ic_app_icon_72),
                contentDescription = "Nezumi AI Logo",
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "ネズミAI",
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
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp),
                color = colorResource(id = R.color.primary)
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
                .background(colorResource(id = R.color.loading_overlay)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = colorResource(id = R.color.primary))
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorResource(id = R.color.surface_card))
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
        }
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
            TextButton(onClick = {
                val lastIndex = adapter.itemCount - 1
                if (lastIndex >= 0) {
                    scrollToBottom(lastIndex)
                }
            }) {
                Text(text = getString(R.string.scroll_to_bottom_icon))
            }
        }
    }

    @Composable
    private fun HeaderActionsSection() {
        Column(
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
                        text = "シンキング",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Switch(
                        checked = !thinkingToggleChecked,
                        onCheckedChange = { checked ->
                            viewModel.setChatSessionDisableThinking(!checked)
                        },
                        enabled = thinkingToggleEnabled
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
        
        super.onDestroyView()
        _binding = null
    }

    private fun launchCamera() {
        if (!imageInputEnabled) {
            Toast.makeText(requireContext(), "このモデルでは画像入力は無効です", Toast.LENGTH_SHORT).show()
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
            cameraLauncher.launch(cameraIntent)
        } catch (e: Exception) {
            Log.e("ChatFragment", "Camera app not found", e)
            Toast.makeText(requireContext(), "カメラアプリが見つかりません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteFromClipboard() {
        if (!imageInputEnabled) {
            Toast.makeText(requireContext(), "このモデルでは画像入力は無効です", Toast.LENGTH_SHORT).show()
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
                    // キャッシュディレクトリにコピー
                    try {
                        val inputStream = requireContext().contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val cacheDir = java.io.File(requireContext().cacheDir, "clipboard")
                            if (!cacheDir.exists()) {
                                cacheDir.mkdirs()
                            }
                            
                            val cachedFile = java.io.File(cacheDir, "IMG_${System.currentTimeMillis()}.jpg")
                            val outputStream = java.io.FileOutputStream(cachedFile)
                            
                            inputStream.copyTo(outputStream)
                            inputStream.close()
                            outputStream.close()
                            
                            // FileProviderでURIを取得
                            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                requireContext(),
                                "com.nezumi_ai.fileprovider",
                                cachedFile
                            )
                            
                            // 複数画像リストに追加（最大5枚まで）
                            if (selectedImageUrisList.size < 5) {
                                selectedImageUrisList = selectedImageUrisList + fileUri.toString()
                                updateMediaPreview()
                                Toast.makeText(requireContext(), "クリップボードから画像を貼り付けました (${selectedImageUrisList.size}/5)", Toast.LENGTH_SHORT).show()
                                Log.d("ChatFragment", "Image pasted from clipboard: ${cachedFile.absolutePath}")
                            } else {
                                Toast.makeText(requireContext(), "最大5枚までしか選択できません", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "Error processing clipboard URI", e)
                        Toast.makeText(requireContext(), "クリップボードから画像を取得できませんでした", Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(requireContext(), "クリップボードからURIを貼り付けました (${selectedImageUrisList.size}/5)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "最大5枚までしか選択できません", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("ChatFragment", "Invalid URI in clipboard", e)
                            Toast.makeText(requireContext(), "無効なURIです", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), "クリップボードに画像またはURIがありません", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "クリップボードに有効なデータがありません", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "クリップボードが空です", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Error accessing clipboard", e)
            Toast.makeText(requireContext(), "クリップボードのアクセスに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchAudioRecording() {
        if (!audioInputEnabled) {
            Toast.makeText(requireContext(), "このモデルでは音声入力は無効です", Toast.LENGTH_SHORT).show()
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
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(recordingFile?.absolutePath)
                prepare()
                start()
            }
            
            // 送信ボタンを停止ボタンに変更
            binding.sendButton.text = "停止"
            binding.sendButton.setOnClickListener {
                stopAudioRecording()
            }
            
            // 音量アニメーションを開始
            startRecordingAmplitudeAnimation()
            
            Toast.makeText(requireContext(), "録音開始しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ChatFragment", "Error starting audio recording", e)
            Toast.makeText(requireContext(), "録音の開始に失敗しました", Toast.LENGTH_SHORT).show()
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
                binding.messageInput.hint = "メッセージを入力..."
                
                // 送信ボタンを戻す
                binding.sendButton.text = getString(R.string.send)
                binding.sendButton.setOnClickListener {
                    if (viewModel.isLoading.value) {
                        viewModel.stopGeneration()
                        return@setOnClickListener
                    }
                    val message = binding.messageInput.text.toString().trim()
                    if (message.isNotEmpty()) {
                        // Phase 11: 複数画像対応
                        val imagesToSend = if (imageInputEnabled) selectedImageUrisList else emptyList()
                        val audioToSend = if (audioInputEnabled) selectedAudioUri else null
                        viewModel.sendMessageWithMedia(message, imagesToSend, audioToSend)
                        binding.messageInput.text?.clear()
                        selectedImageUrisList = emptyList()
                        selectedAudioUri = null
                        updateMediaPreview()
                    }
                }
                
                // 録音ファイルをコンテキストに追加
                if (recordingFile != null && recordingFile!!.exists()) {
                    try {
                        val recordingUri = androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            "com.nezumi_ai.fileprovider",
                            recordingFile!!
                        )
                        selectedAudioUri = recordingUri.toString()
                        updateMediaPreview()
                        Toast.makeText(requireContext(), "音声を追加しました", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "Error creating FileProvider URI for recording", e)
                        Toast.makeText(requireContext(), "音声ファイルの処理に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Error stopping audio recording", e)
            Toast.makeText(requireContext(), "録音の停止に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startRecordingAmplitudeAnimation() {
        recordingAnimationJob?.cancel()
        
        recordingAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            var dotCount = 0
            while (isRecordingAudio && mediaRecorder != null) {
                try {
                    // ドット数を循環（1個 → 2個 → 3個 → 1個）
                    dotCount = (dotCount % 3) + 1
                    val dots = ".".repeat(dotCount)
                    
                    withContext(Dispatchers.Main) {
                        // プレースホルダーテキストをドット進捗表示に変更
                        binding.messageInput.hint = "録音中$dots"
                    }
                    
                    delay(500) // 500msごとにドット更新
                } catch (e: Exception) {
                    Log.d("ChatFragment", "Recording animation error", e)
                }
            }
            
            // アニメーション終了時にプレースホルダーを元に戻す
            withContext(Dispatchers.Main) {
                binding.messageInput.hint = "メッセージを入力..."
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
