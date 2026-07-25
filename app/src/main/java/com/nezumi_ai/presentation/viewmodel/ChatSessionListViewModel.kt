package com.nezumi_ai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.repository.ChatChunkRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.model.GroupedChatSessions
import com.nezumi_ai.data.model.groupSessionsByDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatSessionListViewModel(
    private val repository: ChatSessionRepository,
    private val chatChunkRepository: ChatChunkRepository? = null
) : ViewModel() {
    
    val sessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())
    
    val groupedSessions: Flow<List<GroupedChatSessions>> = repository.getAllSessions()
        .map { sessions -> groupSessionsByDate(sessions) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())
    
    fun createNewSession(name: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val sessionId = repository.createSession(name)
            onCreated?.invoke(sessionId)
        }
    }
    
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    /**
     * セッション削除と同時に、そのセッションの全メッセージの添付ファイル (画像 / 音声 / 動画) を
     * MessageMediaStore 経由で掃除する。cleanupAttachments は呼び出し側で
     * Context をキャプチャして渡す想定。
     */
    fun deleteSession(
        sessionId: Long,
        cleanupAttachments: (imageUri: String?, audioUri: String?) -> Unit
    ) {
        viewModelScope.launch {
            repository.deleteSessionWithAttachments(sessionId, cleanupAttachments)
        }
    }
    
    fun togglePinSession(sessionId: Long) {
        viewModelScope.launch {
            repository.togglePinSession(sessionId)
        }
    }

    fun renameSession(sessionId: Long, newName: String) {
        viewModelScope.launch {
            repository.updateSessionName(sessionId, newName)
        }
    }

    suspend fun getSessionById(sessionId: Long) = repository.getSessionById(sessionId)
    // ── 履歴検索 ─────────────────────────────────────────────────────────────

    data class SearchResult(
        val sessionId: Long,
        val sessionName: String,
        val messageId: Long,
        val chunkId: Long,
        val chunkText: String,
        val score: Float
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300L) // debounce
            _isSearching.value = true
            try {
                val repo = chatChunkRepository ?: return@launch
                val sessions = repository.getAllSessionsOnce()
                val sessionMap = sessions.associateBy { it.id }
                val raw = repo.search(query, sessionId = null, topK = 30)
                _searchResults.value = raw.mapNotNull { result ->
                    val session = sessionMap[result.chunk.sessionId] ?: return@mapNotNull null
                    SearchResult(
                        sessionId = result.chunk.sessionId,
                        sessionName = session.name,
                        messageId = result.chunk.messageId,
                        chunkId = result.chunk.id,
                        chunkText = result.chunk.chunkText,
                        score = result.score
                    )
                }
            } finally {
                _isSearching.value = false
            }
        }
    }


}
