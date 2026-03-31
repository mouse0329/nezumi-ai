package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.SettingsRepository

class ChatSessionListViewModelFactory(private val repository: ChatSessionRepository) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatSessionListViewModel(repository) as T
    }
}

class ChatViewModelFactory(
    private val appContext: Context,
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(
            appContext,
            sessionRepository,
            messageRepository,
            settingsRepository
        ) as T
    }
}

class SettingsViewModelFactory(private val repository: SettingsRepository) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(repository) as T
    }
}
