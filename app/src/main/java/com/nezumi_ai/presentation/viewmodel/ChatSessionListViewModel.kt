package com.nezumi_ai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.model.GroupedChatSessions
import com.nezumi_ai.data.model.groupSessionsByDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatSessionListViewModel(private val repository: ChatSessionRepository) : ViewModel() {
    
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
}
