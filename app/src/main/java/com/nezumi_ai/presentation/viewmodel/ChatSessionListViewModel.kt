package com.nezumi_ai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatSessionListViewModel(private val repository: ChatSessionRepository) : ViewModel() {
    
    val sessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())
    
    fun createNewSession(name: String) {
        viewModelScope.launch {
            repository.createSession(name)
        }
    }
    
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }
}
