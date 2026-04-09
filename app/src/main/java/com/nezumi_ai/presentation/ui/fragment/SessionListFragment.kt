package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModelFactory
import com.nezumi_ai.presentation.ui.screen.SessionListRoute
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SessionListFragment : Fragment() {

    companion object {
        private const val TAG = "SessionListFragment"
    }

    private lateinit var viewModel: ChatSessionListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViewModel()
    }

    private fun initViewModel() {
        try {
            val database = NezumiAiDatabase.getInstance(requireContext())
            val repository = ChatSessionRepository(database.chatSessionDao())
            val factory = ChatSessionListViewModelFactory(repository)
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
                SessionListRoute(
                    viewModel = viewModel,
                    onOpenSettings = {
                        findNavController().navigate(R.id.action_sessionListFragment_to_settingsFragment)
                    },
                    onCreateSession = {
                        viewModel.createNewSession("新しいチャット")
                    },
                    onSessionClick = ::navigateToChat,
                    onDeleteSession = ::confirmDeleteSession
                )
            }
        }
    }

    private fun navigateToChat(sessionId: Long) {
        val action = SessionListFragmentDirections.actionSessionListFragmentToChatFragment(sessionId)
        findNavController().navigate(action)
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
}
