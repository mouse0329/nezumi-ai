package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.database.entity.SettingsEntity
import com.nezumi_ai.data.inference.ModelFileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    
    val settings: Flow<SettingsEntity?> = repository.getSettings()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, null)
    
    private val _modelRedownloadStatus = MutableStateFlow<String?>(null)
    val modelRedownloadStatus: StateFlow<String?> = _modelRedownloadStatus.asStateFlow()
    
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
    
    /**
     * モデルを削除して再ダウンロードを強制する（0.12.4対応の最新版を取得）
     */
    fun redownloadModel(context: Context, modelName: String) {
        viewModelScope.launch {
            _modelRedownloadStatus.value = "削除中..."
            val model = ModelFileManager.resolveModelName(modelName)
            val deleted = ModelFileManager.deleteModel(context, model)
            if (deleted) {
                _modelRedownloadStatus.value = "削除完了。次回起動時に最新版をダウンロードします。"
            } else {
                _modelRedownloadStatus.value = "削除に失敗しました。"
            }
        }
    }
    
    fun clearRedownloadStatus() {
        _modelRedownloadStatus.value = null
    }
}
