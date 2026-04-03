package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.database.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    
    val settings: Flow<SettingsEntity?> = repository.getSettings()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, null)
    
    fun updateModel(model: String) {
        viewModelScope.launch {
            repository.updateModel(model)
        }
    }
    
    fun updateBackend(backend: String) {
        viewModelScope.launch {
            repository.updateBackend(backend)
        }
    }
    
    fun initializeIfNeeded(context: Context) {
        viewModelScope.launch {
            repository.initializeSettingsIfNeeded(context.applicationContext)
        }
    }
}
