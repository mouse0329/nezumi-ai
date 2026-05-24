package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModelFactory
import com.nezumi_ai.presentation.ui.screen.HistorySearchModal
import com.nezumi_ai.presentation.ui.screen.SessionListScreen
import kotlinx.coroutines.launch

class SessionListFragment : Fragment() {

    companion object {
        private const val TAG = "SessionListFragment"
    }

    private lateinit var viewModel: ChatSessionListViewModel
    private var currentSessionIdState = androidx.compose.runtime.mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViewModel()
    }

    private fun initViewModel() {
        try {
            val database = NezumiAiDatabase.getInstance(requireContext())
            val settingsRepository = com.nezumi_ai.data.repository.SettingsRepository(database.settingsDao(), database.chatSessionDao())
            val messageRepository = com.nezumi_ai.data.repository.MessageRepository(database.messageDao())
            val repository = ChatSessionRepository(database.chatSessionDao(), settingsRepository, messageRepository)
            val chatChunkRepository = com.nezumi_ai.data.repository.ChatChunkRepository(
                database.chatChunkDao(), requireContext()
            )
            val factory = ChatSessionListViewModelFactory(repository, chatChunkRepository)
            viewModel = ViewModelProvider(this, factory).get(ChatSessionListViewModel::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize session list screen", t)
            context?.let {
                Toast.makeText(it, "画面の初期化に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (!::viewModel.isInitialized) {
            initViewModel()
        }
        
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val currentSessionId = currentSessionIdState.longValue.takeIf { it != -1L }
                var showSearch by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                SessionListScreen(
                    viewModel = viewModel,
                    onOpenSettings = {
                        (requireActivity() as com.nezumi_ai.MainActivity).openDrawer()
                    },
                    onSessionClick = ::navigateToChat,
                    onSearchClick = { showSearch = true },
                    currentSessionId = currentSessionId
                )

                if (showSearch) {
                    HistorySearchModal(
                        viewModel = viewModel,
                        onResultClick = { sessionId, messageId ->
                            showSearch = false
                            navigateToChatWithScroll(sessionId, messageId)
                        },
                        onDismiss = { showSearch = false }
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        val prefs = requireContext().getSharedPreferences("nezumi_ai_prefs", android.content.Context.MODE_PRIVATE)
        val sessionId = prefs.getLong("current_session_id", -1L)
        currentSessionIdState.longValue = sessionId
        android.util.Log.d(TAG, "onResume: currentSessionId=$sessionId")
    }

    private fun navigateToChat(sessionId: Long) {
        val action = SessionListFragmentDirections.actionSessionListFragmentToChatFragment(sessionId)
        findNavController().navigate(action)
    }

    private fun navigateToChatWithScroll(sessionId: Long, messageId: Long) {
        val action = SessionListFragmentDirections
            .actionSessionListFragmentToChatFragment(sessionId)
            .also {
                // scrollToMessageId はDirectionsの引数として渡す
            }
        val bundle = android.os.Bundle().apply {
            putLong("sessionId", sessionId)
            putLong("scrollToMessageId", messageId)
        }
        findNavController().navigate(R.id.chatFragment, bundle)
    }

    private fun confirmDeleteSession(sessionId: Long) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("チャットを削除")
            .setMessage("このチャットとメッセージを削除します。よろしいですか？")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ ->
                viewModel.deleteSession(sessionId)
                Toast.makeText(requireContext(), "チャットを削除しました", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showRenameSessionDialog(sessionId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val session = viewModel.getSessionById(sessionId)
            val input = TextInputEditText(requireContext()).apply {
                setText(session?.name ?: "")
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSelection(text?.length ?: 0)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("チャット名を変更")
                .setView(input)
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("保存") { _, _ ->
                    val newName = input.text?.toString()?.trim().orEmpty()
                    if (newName.isBlank()) return@setPositiveButton
                    viewModel.renameSession(sessionId, newName)
                }
                .show()
        }
    }
}
