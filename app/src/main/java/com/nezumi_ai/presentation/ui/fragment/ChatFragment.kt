package com.nezumi_ai.presentation.ui.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var usageMonitorJob: Job? = null
    private lateinit var settingsRepository: SettingsRepository
    private val cpuUsageSampler = CpuUsageSampler()
    private var isGenerating = false
    private var isModelLoadingNow = false
    private var resourceMonitorEnabled = false
    private var currentBackendType = "CPU"
    private var currentModelKey = "E2B"
    private var isCompressingNow = false
    private var selectedImageUri: String? = null
    private var selectedAudioUri: String? = null
    
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri.toString()
            Toast.makeText(requireContext(), "画像を選択しました", Toast.LENGTH_SHORT).show()
            updateMediaPreview()
        }
    }
    
    private val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedAudioUri = uri.toString()
            Toast.makeText(requireContext(), "音声を選択しました", Toast.LENGTH_SHORT).show()
            updateMediaPreview()
        }
    }

    private fun updateMediaPreview() {
        val previewContainer = binding.mediaPreviewContainer
        previewContainer.removeAllViews()

        if (selectedImageUri.isNullOrEmpty() && selectedAudioUri.isNullOrEmpty()) {
            binding.mediaPreviewScroll.visibility = View.GONE
            viewModel.clearPendingMediaPreview()
            return
        }

        binding.mediaPreviewScroll.visibility = View.VISIBLE
        
        // ViewModelのプレビューメッセージを更新（チャット欄に表示）
        viewModel.updatePendingMediaPreview(selectedImageUri, selectedAudioUri)

        // 画像プレビュー
        if (!selectedImageUri.isNullOrEmpty()) {
            val imageFrame = createMediaPreviewItem(
                "🖼",
                selectedImageUri!!.substringAfterLast("/").take(15),
                { removeMedia("image") }
            )
            previewContainer.addView(imageFrame)
        }

        // 音声プレビュー
        if (!selectedAudioUri.isNullOrEmpty()) {
            val audioFrame = createMediaPreviewItem(
                "🎤",
                selectedAudioUri!!.substringAfterLast("/").take(15),
                { removeMedia("audio") }
            )
            previewContainer.addView(audioFrame)
        }
    }

    private fun createMediaPreviewItem(
        icon: String,
        filename: String,
        onRemove: () -> Unit
    ): android.widget.FrameLayout {
        val frame = android.widget.FrameLayout(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(100, 100).apply {
                marginEnd = 4
            }
            setOnClickListener { onRemove() }
        }

        val container = android.widget.LinearLayout(requireContext()).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#F0D9CC"))
            isClickable = true
        }

        val iconText = android.widget.TextView(requireContext()).apply {
            text = icon
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }
        container.addView(iconText)

        val nameText = android.widget.TextView(requireContext()).apply {
            text = filename
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setPadding(4, 4, 4, 4)
        }
        container.addView(nameText)

        frame.addView(container)

        // 削除ボタン（右上のX）
        val deleteBtn = android.widget.TextView(requireContext()).apply {
            text = "✕"
            textSize = 16f
            setTextColor(android.graphics.Color.RED)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                24, 24,
                android.view.Gravity.TOP or android.view.Gravity.END
            )
            setOnClickListener { onRemove() }
        }
        frame.addView(deleteBtn)

        return frame
    }

    private fun removeMedia(type: String) {
        when (type) {
            "image" -> selectedImageUri = null
            "audio" -> selectedAudioUri = null
        }
        updateMediaPreview()
        Toast.makeText(
            requireContext(),
            "${if (type == "image") "画像" else "音声"}を削除しました",
            Toast.LENGTH_SHORT
        ).show()
    }
    
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
        
        // ViewModel初期化
        val database = NezumiAiDatabase.getInstance(requireContext())
        val sessionRepository = ChatSessionRepository(database.chatSessionDao())
        val messageRepository = MessageRepository(database.messageDao())
        settingsRepository = SettingsRepository(database.settingsDao())
        val factory = ChatViewModelFactory(
            requireContext().applicationContext,
            sessionRepository,
            messageRepository,
            settingsRepository
        )
        viewModel = ViewModelProvider(this, factory).get(ChatViewModel::class.java)
        setupModelDropdown()
        refreshResourceMonitorSetting()
        
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
        binding.scrollToBottomButton.setOnClickListener {
            val lastIndex = adapter.itemCount - 1
            if (lastIndex >= 0) {
                scrollToBottom(lastIndex)
            }
        }
        
        // セッションID取得（Navigation argsから）
        val sessionId = args.sessionId
        viewModel.setCurrentSession(sessionId)
        
        // 戻るボタン
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.compressContextButton.setOnClickListener {
            viewModel.compressContextManually()
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
                viewModel.sendMessageWithMedia(message, selectedImageUri, selectedAudioUri)
                binding.messageInput.text.clear()
                selectedImageUri = null
                selectedAudioUri = null
                updateMediaPreview()
                viewModel.clearPendingMediaPreview()
            }
        }

        // メディアメニューボタン
        binding.mediaMenuButton.setOnClickListener { view ->
            val popupMenu = PopupMenu(requireContext(), view)
            popupMenu.menuInflater.inflate(R.menu.menu_media_select, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_select_image -> {
                        imagePickerLauncher.launch("image/*")
                        true
                    }
                    R.id.menu_select_audio -> {
                        audioPickerLauncher.launch("audio/*")
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
            viewModel.isCompressing.collect { compressing ->
                isCompressingNow = compressing
                renderCompressButtonState()
                if (isGenerating) {
                    binding.responseTypingText.text =
                        if (isCompressingNow) getString(R.string.response_compressing)
                        else getString(R.string.response_generating)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedModel.collect { model ->
                currentModelKey = model
                refreshCurrentBackendType()
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
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.contextUsageChars.collect { used ->
                val max = ChatViewModel.CONTEXT_WINDOW_CHARS
                binding.contextMeterText.text =
                    getString(R.string.context_meter_format, used, max)
                binding.contextMeterProgress.progress =
                    ((used.toLong() * 1000L) / max.toLong()).toInt().coerceIn(0, 1000)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isModelLoading.collect { loading ->
                isModelLoadingNow = loading
                binding.modelLoadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
                binding.backButton.isEnabled = !loading
                renderModelDropdownState()
                renderSendButtonState()
                renderCompressButtonState()
                binding.messageInput.isEnabled = !loading
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupModelDropdown()
        refreshResourceMonitorSetting()
        refreshCurrentBackendType()
    }

    override fun onStop() {
        super.onStop()
        stopUsageMonitor()
        if (isGenerating) {
            viewModel.stopGeneration()
            stopResponseTypingAnimation()
        }
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
        binding.scrollToBottomButton.visibility = if (isAtBottom()) View.GONE else View.VISIBLE
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
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.E2B)) {
            options += ModelOption("E2B", getString(R.string.model_e2b_label))
        }
        if (ModelFileManager.isDownloaded(requireContext(), ModelFileManager.LocalModel.E4B)) {
            options += ModelOption("E4B", getString(R.string.model_e4b_label))
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

    private fun renderModelDropdownState() {
        val enabled = !isModelLoadingNow && !isGenerating && modelOptions.isNotEmpty()
        binding.modelDropdown.isEnabled = enabled
        binding.modelDropdownLayout.isEnabled = enabled
    }

    private fun renderCompressButtonState() {
        val enabled = !isModelLoadingNow && !isGenerating
        binding.compressContextButton.isEnabled = enabled
        binding.compressContextButton.text =
            if (isCompressingNow) getString(R.string.compress_context_busy)
            else getString(R.string.compress_context)
    }

    private fun refreshResourceMonitorSetting() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val enabled = settingsRepository.isResourceMonitorEnabled()
            withContext(Dispatchers.Main) {
                resourceMonitorEnabled = enabled
                binding.resourceUsageText.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) {
                    startUsageMonitor()
                } else {
                    stopUsageMonitor()
                }
            }
        }
    }

    private fun refreshCurrentBackendType() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val backend = settingsRepository.getBackendForModel(currentModelKey)
            withContext(Dispatchers.Main) {
                currentBackendType = backend.uppercase()
                if (resourceMonitorEnabled) {
                    updateResourceUsageText(
                        cpuPercent = 0,
                        gpuPercent = if (isGenerating && currentBackendType == "GPU") 100 else 0,
                        npuPercent = if (isGenerating && currentBackendType == "NPU") 100 else 0
                    )
                }
            }
        }
    }

    private fun startUsageMonitor() {
        if (usageMonitorJob?.isActive == true) return
        usageMonitorJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && resourceMonitorEnabled) {
                val cpu = withContext(Dispatchers.IO) {
                    cpuUsageSampler.sampleProcessCpuPercent()
                }
                val gpu = if (isGenerating && currentBackendType == "GPU") 100 else 0
                val npu = if (isGenerating && currentBackendType == "NPU") 100 else 0
                updateResourceUsageText(cpu, gpu, npu)
                delay(1000L)
            }
        }
    }

    private fun stopUsageMonitor() {
        usageMonitorJob?.cancel()
        usageMonitorJob = null
    }

    private fun updateResourceUsageText(cpuPercent: Int, gpuPercent: Int, npuPercent: Int) {
        binding.resourceUsageText.text = getString(
            R.string.resource_usage_format,
            cpuPercent.coerceIn(0, 100),
            gpuPercent.coerceIn(0, 100),
            npuPercent.coerceIn(0, 100)
        )
    }

    private fun startResponseTypingAnimation() {
        if (responseTypingAnimationJob?.isActive == true) return
        binding.responseTypingIndicator.visibility = View.VISIBLE
        responseTypingAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            var dotCount = 0
            while (true) {
                val dots = ".".repeat(dotCount)
                val base = if (isCompressingNow) {
                    getString(R.string.response_compressing)
                } else {
                    getString(R.string.response_generating)
                }
                binding.responseTypingText.text = base + dots
                dotCount = (dotCount + 1) % 4
                delay(350)
            }
        }
    }

    private fun stopResponseTypingAnimation() {
        responseTypingAnimationJob?.cancel()
        responseTypingAnimationJob = null
        binding.responseTypingIndicator.visibility = View.GONE
        binding.responseTypingText.text = getString(R.string.response_generating)
    }
    
    override fun onDestroyView() {
        responseTypingAnimationJob?.cancel()
        responseTypingAnimationJob = null
        stopUsageMonitor()
        super.onDestroyView()
        _binding = null
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
