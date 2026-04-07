package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.nezumi_ai.R
import com.nezumi_ai.databinding.FragmentSessionListBinding
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModelFactory
import com.nezumi_ai.presentation.ui.adapter.SessionAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class SessionListFragment : Fragment(R.layout.fragment_session_list) {
    
    companion object {
        private const val TAG = "SessionListFragment"
    }

    private var _binding: FragmentSessionListBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: ChatSessionListViewModel
    private lateinit var adapter: SessionAdapter
    private var previousSessionCount = 0
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionListBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Unhandled coroutine error", throwable)
            if (isAdded) {
                Toast.makeText(requireContext(), "初期化に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
        applyStatusBarInset()

        try {
            // ViewModel初期化
            val database = NezumiAiDatabase.getInstance(requireContext())
            val repository = ChatSessionRepository(database.chatSessionDao())
            val factory = ChatSessionListViewModelFactory(repository)
            viewModel = ViewModelProvider(this, factory).get(ChatSessionListViewModel::class.java)

            // RecyclerView設定
            adapter = SessionAdapter(
                onSessionClick = { sessionId ->
                    navigateToChat(sessionId)
                },
                onDeleteClick = { sessionId ->
                    confirmDeleteSession(sessionId)
                }
            )
            binding.sessionRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@SessionListFragment.adapter
            }

            // 新規セッション作成ボタン
            binding.newSessionButton.setOnClickListener {
                viewModel.createNewSession("新しいチャット")
            }
            binding.createFirstSessionButton.setOnClickListener {
                viewModel.createNewSession("新しいチャット")
            }
            binding.settingsButton.setOnClickListener {
                findNavController().navigate(R.id.action_sessionListFragment_to_settingsFragment)
            }

            // Sessions更新の監視
            viewLifecycleOwner.lifecycleScope.launch(handler) {
                viewModel.sessions
                    .catch { e ->
                        Log.e(TAG, "Failed to collect sessions", e)
                        emit(emptyList())
                    }
                    .collect { sessions ->
                        val shouldScrollToTop = sessions.size > previousSessionCount
                        adapter.submitList(sessions) {
                            if (shouldScrollToTop && sessions.isNotEmpty()) {
                                binding.sessionRecyclerView.scrollToPosition(0)
                            }
                        }
                        // 空状態の表示制御
                        if (sessions.isEmpty()) {
                            binding.emptyStateContainer.visibility = View.VISIBLE
                        } else {
                            binding.emptyStateContainer.visibility = View.GONE
                        }
                        previousSessionCount = sessions.size
                    }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize session list screen", t)
            Toast.makeText(requireContext(), "画面の初期化に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyStatusBarInset() {
        val initialTop = binding.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = initialTop + topInset)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
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
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
