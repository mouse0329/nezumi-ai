package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

class ChatFragment : Fragment(R.layout.fragment_chat) {
    
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: ChatViewModel
    private lateinit var adapter: MessageAdapter
    private val args: ChatFragmentArgs by navArgs()
    private var modelOptions: List<ModelOption> = emptyList()
    private var responseTypingAnimationJob: Job? = null
    private var isGenerating = false
    private var isModelLoadingNow = false
    
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
        val settingsRepository = SettingsRepository(database.settingsDao())
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
        }
        
        // セッションID取得（Navigation argsから）
        val sessionId = args.sessionId
        viewModel.setCurrentSession(sessionId)
        
        // 戻るボタン
        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }
        
        // メッセージの監視
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                adapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    scrollToBottom(messages.size - 1)
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
                viewModel.sendMessage(message)
                binding.messageInput.text.clear()
                val lastIndex = adapter.itemCount - 1
                if (lastIndex >= 0) {
                    scrollToBottom(lastIndex)
                }
            }
        }

        // ローディング状態の監視
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                isGenerating = isLoading
                renderSendButtonState()
                if (isLoading) {
                    startResponseTypingAnimation()
                } else {
                    stopResponseTypingAnimation()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedModel.collect { model ->
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
                binding.modelDropdown.isEnabled = !loading && modelOptions.isNotEmpty()
                renderSendButtonState()
                binding.messageInput.isEnabled = !loading
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupModelDropdown()
    }

    override fun onStop() {
        super.onStop()
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
        binding.modelDropdown.isEnabled = true
        binding.modelDropdownLayout.isEnabled = true
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

    private fun renderSendButtonState() {
        // 生成中は常に「停止(四角)」を優先表示
        binding.sendButton.text =
            if (isGenerating) getString(R.string.stop_icon) else getString(R.string.send_icon)
        // モデルロード中のみ操作不可
        binding.sendButton.isEnabled = !isModelLoadingNow
    }

    private fun startResponseTypingAnimation() {
        if (responseTypingAnimationJob?.isActive == true) return
        binding.responseTypingIndicator.visibility = View.VISIBLE
        responseTypingAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            var dotCount = 0
            while (true) {
                val dots = ".".repeat(dotCount)
                binding.responseTypingText.text = getString(R.string.response_generating) + dots
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
        super.onDestroyView()
        _binding = null
    }
}
