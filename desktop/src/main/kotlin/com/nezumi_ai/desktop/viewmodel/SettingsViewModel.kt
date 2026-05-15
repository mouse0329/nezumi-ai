package com.nezumi_ai.desktop.viewmodel

import com.nezumi_ai.desktop.data.DesktopSettingsStore
import com.nezumi_ai.desktop.inference.DesktopLlmServices
import com.nezumi_ai.desktop.inference.LlamaDownloader
import com.nezumi_ai.desktop.inference.ModelDownloader
import com.nezumi_ai.desktop.inference.jna.LlamaCppLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class SettingsViewModel private constructor() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        @Volatile
        private var instance: SettingsViewModel? = null

        fun getInstance(): SettingsViewModel =
            instance ?: synchronized(this) {
                instance ?: SettingsViewModel().also { instance = it }
            }
    }
    
    private val _modelPath = MutableStateFlow("")
    val modelPath: StateFlow<String> = _modelPath.asStateFlow()
    
    private val _backend = MutableStateFlow("CPU")
    val backend: StateFlow<String> = _backend.asStateFlow()
    
    private val _nGpuLayers = MutableStateFlow(0)
    val nGpuLayers: StateFlow<Int> = _nGpuLayers.asStateFlow()
    
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    
    private val _isLibraryAvailable = MutableStateFlow(false)
    val isLibraryAvailable: StateFlow<Boolean> = _isLibraryAvailable.asStateFlow()
    
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()
    
    private val _availableModels = MutableStateFlow<List<ModelDownloader.ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelDownloader.ModelInfo>> = _availableModels.asStateFlow()
    
    private val _downloadedModels = MutableStateFlow<List<File>>(emptyList())
    val downloadedModels: StateFlow<List<File>> = _downloadedModels.asStateFlow()
    
    private val llamaEngine get() = DesktopLlmServices.llamaEngine
    
    init {
        // llama.cppライブラリの確認
        _isLibraryAvailable.value = LlamaDownloader.isLibraryAvailable()

        if (_isLibraryAvailable.value) {
            _statusMessage.value = "✓ llama.cpp library found"
        } else {
            _statusMessage.value = "⚠ llama.cpp library not found. Click 'Download llama.cpp' to install."
        }

        // 利用可能なモデルリスト
        _availableModels.value = ModelDownloader.RECOMMENDED_MODELS

        // ダウンロード済みモデルを検索
        refreshDownloadedModels()

        val persisted = DesktopSettingsStore.load()
        if (persisted != null) {
            _backend.value = persisted.backendLabel
            _nGpuLayers.value = when (persisted.backendLabel) {
                "GPU (CUDA)", "Metal (macOS)" -> 32
                else -> 0
            }
            val path = persisted.lastModelPath
            if (path.isNotBlank() && File(path).exists()) {
                _modelPath.value = path
            } else if (_downloadedModels.value.isNotEmpty()) {
                _modelPath.value = _downloadedModels.value.first().absolutePath
            }
        } else if (_downloadedModels.value.isNotEmpty()) {
            _modelPath.value = _downloadedModels.value.first().absolutePath
        }
    }
    
    fun refreshDownloadedModels() {
        _downloadedModels.value = ModelDownloader.listDownloadedModels()
    }
    
    fun downloadLlamaCpp() {
        if (_isDownloading.value) return
        
        _isDownloading.value = true
        _statusMessage.value = "Downloading llama.cpp..."
        
        scope.launch {
            val result = LlamaDownloader.downloadLibrary { progress ->
                _downloadProgress.value = progress
            }
            
            result.onSuccess { path ->
                LlamaCppLibrary.invalidate()
                _isLibraryAvailable.value = true
                _statusMessage.value = "✓ llama.cpp installed successfully: $path"
                _downloadProgress.value = 0
            }.onFailure { error ->
                _statusMessage.value = "Error: ${error.message}"
                _downloadProgress.value = 0
            }
            
            _isDownloading.value = false
        }
    }
    
    fun downloadModel(modelInfo: ModelDownloader.ModelInfo) {
        if (_isDownloading.value) return
        
        _isDownloading.value = true
        _statusMessage.value = "Downloading ${modelInfo.displayName}..."
        
        scope.launch {
            val result = ModelDownloader.downloadModel(modelInfo) { downloaded, total ->
                if (total > 0) {
                    _downloadProgress.value = ((downloaded * 100) / total).toInt()
                }
            }
            
            result.onSuccess { path ->
                _modelPath.value = path
                _statusMessage.value = "✓ Model downloaded: ${modelInfo.displayName}"
                _downloadProgress.value = 0
                refreshDownloadedModels()
            }.onFailure { error ->
                _statusMessage.value = "Error: ${error.message}"
                _downloadProgress.value = 0
            }
            
            _isDownloading.value = false
        }
    }
    
    fun updateModelPath(path: String) {
        _modelPath.value = path
        _isModelLoaded.value = false
        _statusMessage.value = ""
    }
    
    fun updateBackend(backend: String) {
        _backend.value = backend
        _nGpuLayers.value = when (backend) {
            "GPU (CUDA)" -> 32
            "Metal (macOS)" -> 32
            else -> 0
        }
    }
    
    fun updateGpuLayers(layers: Int) {
        _nGpuLayers.value = layers
    }
    
    fun loadModel() {
        if (_modelPath.value.isEmpty()) {
            _statusMessage.value = "Error: Model path is empty"
            return
        }
        
        val file = File(_modelPath.value)
        if (!file.exists()) {
            _statusMessage.value = "Error: Model file not found"
            return
        }
        
        _statusMessage.value = "Loading model..."
        
        scope.launch {
            val success = llamaEngine.initialize(
                modelPath = _modelPath.value,
                nGpuLayers = _nGpuLayers.value
            )
            
            withContext(Dispatchers.Main) {
                if (success) {
                    _isModelLoaded.value = true
                    _statusMessage.value = "✓ Model loaded successfully"
                } else {
                    _isModelLoaded.value = false
                    _statusMessage.value = "⚠ Failed to load model (running in mock mode)"
                }
            }
        }
    }
}
