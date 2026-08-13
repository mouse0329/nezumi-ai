package com.nezumi_ai.presentation.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nezumi_ai.data.repository.ChatChunkRepository
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.data.repository.SettingsRepository

class ChatSessionListViewModelFactory(
    private val repository: ChatSessionRepository,
    private val chatChunkRepository: ChatChunkRepository? = null,
    private val appContext: Context? = null
) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatSessionListViewModel(repository, chatChunkRepository, appContext) as T
    }
}

class ChatViewModelFactory(
    private val appContext: Context,
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val presetRepository: PresetRepository? = null,
    private val memoryRepository: MemoryRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(
            appContext,
            sessionRepository,
            messageRepository,
            settingsRepository,
            presetRepository,
            memoryRepository
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

class ImageGenViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ImageGenViewModel(application) as T
    }
}
