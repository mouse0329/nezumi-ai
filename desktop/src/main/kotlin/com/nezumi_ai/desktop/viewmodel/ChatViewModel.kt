package com.nezumi_ai.desktop.viewmodel

import com.nezumi_ai.shared.model.ChatMessage
import com.nezumi_ai.desktop.inference.DesktopLlmServices
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class ChatViewModel private constructor() {
    companion object {
        @Volatile
        private var instance: ChatViewModel? = null
        
        fun getInstance(): ChatViewModel {
            return instance ?: synchronized(this) {
                instance ?: ChatViewModel().also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val llamaEngine get() = DesktopLlmServices.llamaEngine
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    
    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    fun setSelectedModel(modelPath: String) {
        _selectedModel.value = modelPath
        _isModelLoaded.value = false
    }
    
    fun sendMessage(text: String) {
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            sessionId = "default",
            content = text,
            isUser = true,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMessage
        
        _isGenerating.value = true
        
        scope.launch {
            try {
                // モデルが未ロードの場合、自動的にロードする
                if (!_isModelLoaded.value) {
                    val modelPath = _selectedModel.value
                    if (modelPath.isEmpty()) {
                        val errorMessage = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            sessionId = "default",
                            content = """
                                ⚠️ モデルが選択されていません
                                
                                Settings タブから以下の手順でモデルを設定してください：
                                1. モデルをダウンロード
                                2. ダウンロード済みモデルから選択して「読込」をクリック
                            """.trimIndent(),
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        )
                        _messages.value = _messages.value + errorMessage
                        return@launch
                    }
                    
                    // モデルをロード
                    val loadSuccess = llamaEngine.initialize(
                        modelPath = modelPath,
                        nGpuLayers = 0 // TODO: GPU設定を反映
                    )
                    
                    if (!loadSuccess) {
                        val errorMessage = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            sessionId = "default",
                            content = "❌ モデルのロードに失敗しました。Settingsでモデルパスを確認してください。",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        )
                        _messages.value = _messages.value + errorMessage
                        return@launch
                    }
                    
                    _isModelLoaded.value = true
                }
                
                val aiMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = "default",
                    content = "",
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
                _messages.value = _messages.value + aiMessage
                
                var accumulatedContent = ""
                llamaEngine.generate(text).collect { token ->
                    accumulatedContent += token
                    val updatedMessage = aiMessage.copy(content = accumulatedContent)
                    _messages.value = _messages.value.dropLast(1) + updatedMessage
                }
            } catch (e: IllegalStateException) {
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = "default",
                    content = """
                        ⚠️ モデルがロードされていません
                        
                        Settings タブから以下の手順でモデルをロードしてください：
                        1. Model Path に .gguf ファイルのパスを入力
                        2. 「Load Model」ボタンをクリック
                        
                        llama.cpp のセットアップが必要な場合は、desktop/LLAMA_SETUP.md を参照してください。
                    """.trimIndent(),
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
                _messages.value = _messages.value.dropLast(1) + errorMessage
            } catch (e: Exception) {
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = "default",
                    content = "❌ エラーが発生しました: ${e.message}",
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
                _messages.value = _messages.value.dropLast(1) + errorMessage
            } finally {
                _isGenerating.value = false
            }
        }
    }
    
    fun clearMessages() {
        _messages.value = emptyList()
    }
}
