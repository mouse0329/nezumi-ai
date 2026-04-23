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
import android.view.animation.LinearInterpolator
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        contextMeterText = getString(R.string.context_meter_format, 0, 4096)
        compressButtonText = getString(R.string.compress_context)
        thinkingToggleText = getString(R.string.chat_thinking_follow_settings)
        setupComposeIndicators()
        
        // ViewModel初期化
        val database = NezumiAiDatabase.getInstance(requireContext())
        val sessionRepository = ChatSessionRepository(database.chatSessionDao())
        val messageRepository = MessageRepository(database.messageDao())
        settingsRepository = SettingsRepository(database.settingsDao(), database.chatSessionDao())
        val factory = ChatViewModelFactory(
            requireContext().applicationContext,
            sessionRepository,
            messageRepository,
            settingsRepository
        )
        viewModel = ViewModelProvider(this, factory).get(ChatViewModel::class.java)
        setupModelDropdown()
        
        // RecyclerView設定
        adapter = MessageAdapter { message ->
            viewModel.revokePromptFromMessage(message.id)
        }
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
            adapter = this@ChatFragment.adapter
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateScrollToBottomButtonVisibility()
                }
            })
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
        
        // セッションID取得（Navigation argsから）
        val sessionId = args.sessionId
        viewModel.setCurrentSession(sessionId)
        
        // Tool Call State の監視
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.toolCallState.collect { state ->
                currentToolCallState = state
            }
        }
        
        // 戻るボタン
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }
        
        // メッセージの監視（プレビューメディアを含む）
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.messages,
                viewModel.pendingMediaMessage
            ) { messages, pendingMedia ->
                if (pendingMedia != null) {
                    messages + listOf(pendingMedia)
                } else {
                    messages
                }
            }.collect { displayMessages ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "ChatFragment",
                        "DISPLAY_MESSAGES: count=${displayMessages.size} messages=${displayMessages.map { "${it.role}:${it.content}" }}"
                    )
                }
                val wasAtBottom = isAtBottom()
                adapter.submitList(displayMessages) {
                    if (displayMessages.isNotEmpty() && wasAtBottom) {
                        scrollToBottom(displayMessages.size - 1)
                    }
                    updateScrollToBottomButtonVisibility()
                }
            }
        }
        
        // 送信ボタン
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
                viewModel.clearPendingMediaPreview()
            }
        }

        // クリップボードペースト時の画像自動処理
        binding.messageInput.onClipboardImagePaste = {
            pasteFromClipboard()
        }

        // メディアメニューボタン
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
                    R.id.menu_select_image -> {
                        imagePickerLauncher.launch("image/*")
                        true
                    }
                    R.id.menu_camera -> {
                        launchCamera()
                        true
                    }
                    R.id.menu_clipboard_paste -> {
                        pasteFromClipboard()
                        true
                    }
                    R.id.menu_select_audio -> {
                        audioPickerLauncher.launch("audio/*")
                        true
                    }
                    R.id.menu_record_audio -> {
                        launchAudioRecording()
                        true
                    }
                    else -> false
                }
            }
            popupMenu.show()
        }

        // ローディング状態の監視
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                isGenerating = isLoading
                renderSendButtonState()
                renderModelDropdownState()
                if (isLoading) {
                    startResponseTypingAnimation()
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
                // 実行ツール情報は長めに表示（3秒）、その他は短め（2秒）
                val duration = if (message.startsWith("🔧 実行ツール")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(requireContext(), message, duration).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.navigationEvent.collect { event ->
                when (event) {
                    ChatViewModel.NavigationEvent.BACK_TO_HOME -> {
                        Log.i("ChatFragment", "Memory shortage detected - navigating back to home")
                        findNavController().popBackStack()
                    }
                    ChatViewModel.NavigationEvent.CLEAR_CHAT -> {
                        // 将来の拡張用
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.contextUsageChars,
                viewModel.contextWindowSize
            ) { used, max ->
                Pair(used, max)
            }.collect { (used, max) ->
                contextUsageCharsNow = used
                contextMeterText = getString(R.string.context_meter_format, used, max)
                contextMeterProgress =
                    (((used.toLong() * 1000L) / max.toLong()).toInt().coerceIn(0, 1000) / 1000f)
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
                if (warning != null) {
                    showMemoryWarningDialog(warning)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isCompressing.collect { compressing ->
                isCompressingNow = compressing
                // 圧縮中は入力フィールドを無効化
                binding.messageInput.isEnabled = !compressing
                binding.sendButton.isEnabled = !compressing
                renderCompressButtonState()
                renderSendButtonState()
                if (compressing) {
                    startResponseTypingAnimation()
                } else {
                    stopResponseTypingAnimation()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.modelLoadingStatus.collect { status ->
                if (status.isNotEmpty()) {
                    modelLoadingText = status
                }
            }
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
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun scrollToBottom(position: Int) {
        if (position < 0) return
        binding.messagesRecyclerView.post {
            binding.messagesRecyclerView.scrollToPosition(position)
            updateScrollToBottomButtonVisibility()
        }
    }

    private fun isAtBottom(): Boolean {
        val lm = binding.messagesRecyclerView.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = lm.findLastVisibleItemPosition()
        return lastVisible >= adapter.itemCount - 2
    }

    private fun updateScrollToBottomButtonVisibility() {
        scrollToBottomVisible = !isAtBottom()
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
            ToolCallProgressBar(state = currentToolCallState)
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
                    if (index in selectedImageUrisList.indices) {
                        selectedImageUrisList = selectedImageUrisList.filterIndexed { i, _ -> i != index }
                        updateMediaPreview()
                    }
                },
                audioUri = selectedAudioUri,  // Phase 12: 音声URI を渡す
                onClearAudio = { selectedAudioUri = null }
            )
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
                        val config = settingsRepository.getInferenceConfig()
                        viewModel.proceedWithModelLoad(
                            viewModel.selectedModel.value,
                            config
                        )
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
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
                        viewModel.clearPendingMediaPreview()
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
