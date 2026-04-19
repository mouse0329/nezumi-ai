/**
 * LiteRT-LM エンジンを統合したUIレイヤーの実装例
 * ViewModel + Flow 購読パターン
 *
 * このファイルは ChatViewModel に追加すべきメソッドとベストプラクティスを示しています。
 * 実装日: 2026-04-13
 */

// ====================================
// 1. UI 状態管理の強化版
// ====================================

/**
 * チャット画面の統合的な UI 状態
 * Thinking チャンネル、Tool Calling 進捗、ストリーミング状態を一元管理
 */
data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val isThinking: Boolean = false,
    val currentThinkingText: String = "",
    val currentGeneratingText: String = "",
    /** ツール実行中の状態（ツール名と実行状態） */
    val toolCallInProgress: ToolCallState? = null,
    val isLoading: Boolean = false
)

/**
 * ツール実行の進捗状態
 */
data class ToolCallState(
    val toolName: String,
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    val startTime: Long = System.currentTimeMillis()
) {
    /** 実行経過時間（ミリ秒） */
    fun elapsedMs(): Long = System.currentTimeMillis() - startTime
}

enum class ToolCallStatus {
    PENDING,    // 実行待機中
    EXECUTING,  // 実行中
    SUCCESS,    // 成功
    ERROR       // エラー
}

// ====================================
// 2. ChatViewModel に追加すべきメソッド
// ====================================

// 【追加: ViewModel クラス内に以下メソッドを追加】

/**
 * onCleared() の実装例
 * ViewModel がクリアされる際に、モデルを確実にアンロード
 * 
 * これにより以下が保証される：
 * - KVキャッシュのメモリを確実に解放
 * - モデルが不正に再利用される事態を防止
 * - バックグラウンド時のメモリリークを防止
 */
override fun onCleared() {
    Log.d(TAG, "ChatViewModel.onCleared() called")
    
    // 推論をキャンセル
    stopGeneration()
    
    // モデルリソースをアンロード
    viewModelScope.launch {
        try {
            modelManager?.let {
                val result = it.unloadModel()
                if (result.isSuccess) {
                    Log.d(TAG, "Model unloaded successfully in onCleared()")
                } else {
                    Log.w(TAG, "Failed to unload model: ${result.exceptionOrNull()?.message}")
                }
            }
        } catch (e: CancellationException) {
            // ViewModelScope がキャンセルされた場合
            Log.d(TAG, "onCleared coroutine cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model in onCleared()", e)
        }
    }
    
    // WakeLock をリリース
    releaseScreenWakeLock()
    
    // Collection ジョブをキャンセル
    messagesCollectionJob?.cancel()
    
    super.onCleared()
}

/**
 * Tool Calling フィードバックの強化版
 * ツール実行中の詳細な進捗状態を UI に送出
 * 
 * 使用例：
 *   toolCallProgressStateFlow に流れる ToolCallState を監視して UI에 「アラーム設定中...」などを表示
 */
private val _toolCallProgressState = MutableStateFlow<ToolCallState?>(null)
val toolCallProgressState: StateFlow<ToolCallState?> = _toolCallProgressState.asStateFlow()

/**
 * Tool Call チャンクをデコード＆UI更新
 * generateAIResponse内で、toolCallChunk を受け取ったときに呼び出す
 */
private suspend fun handleToolCallChunk(toolCallChunk: String) {
    Log.d(TAG, "Tool call detected: $toolCallChunk")
    
    // ツール名をパース (複数ツール対応)
    val toolNames = toolCallChunk.split(",").map { it.trim() }
    
    for (toolName in toolNames) {
        // UI に「ツール実行中」状態を通知
        val toolState = ToolCallState(
            toolName = toolName,
            status = ToolCallStatus.EXECUTING
        )
        _toolCallProgressState.value = toolState
        
        // UI にメッセージを送出
        val executingMsg = when (toolName) {
            "set_alarm" -> "⏰ アラームを設定中..."
            "send_message" -> "💬 メッセージを送信中..."
            "search" -> "🔍 検索中..."
            else -> "🔧 $toolName を実行中..."
        }
        _uiMessage.emit(executingMsg)
    }
}

/**
 * Tool Result チャンクをデコード＆UI更新
 * generateAIResponse内で、toolResultChunk を受け取ったときに呼び出す
 */
private suspend fun handleToolResultChunk(toolResultChunk: String) {
    Log.d(TAG, "Tool result received: $toolResultChunk")
    
    // フォーマット: "tool_name:status"
    val parts = toolResultChunk.split(":", limit = 2)
    if (parts.size < 2) {
        Log.w(TAG, "Invalid tool result format: $toolResultChunk")
        return
    }
    
    val toolName = parts[0].trim()
    val statusStr = parts[1].trim()
    
    val status = when (statusStr) {
        "success" -> ToolCallStatus.SUCCESS
        "error" -> ToolCallStatus.ERROR
        else -> ToolCallStatus.PENDING
    }
    
    val toolState = ToolCallState(
        toolName = toolName,
        status = status
    )
    _toolCallProgressState.value = toolState
    
    // UI にメッセージを送出
    val resultMsg = when {
        status == ToolCallStatus.SUCCESS -> "✅ $toolName: 成功"
        status == ToolCallStatus.ERROR -> "❌ $toolName: 失敗"
        else -> "⏳ $toolName: ${toolState.elapsedMs()}ms"
    }
    _uiMessage.emit(resultMsg)
}

/**
 * Thinking チャンネルの UI/UX 改善についての注記
 * 
 * 折りたたみ表示対応：UI層と README の実装例を参照してください
 */

/**
 * generateAIResponse の改善版スニペット
 * 以下を generateAIResponse 内の Flow.collect に統合する
 * 
 * 現在：
 *   } else if (toolCallChunk != null) {
 *       _uiMessage.emit("ツール実行: $toolCallChunk")
 *   } else if (toolResultChunk != null) {
 *       _uiMessage.emit("ツール結果: $toolResultChunk")
 *   }
 * 
 * 改善後：
 */
suspend fun improvedFlowCollectLogic(chunk: String) {
    val finalFromModel = InferenceStreamProtocol.decodeFinal(chunk)
    val thinkDelta = InferenceStreamProtocol.decodeThinkChunk(chunk)
    val toolCallChunk = InferenceStreamProtocol.decodeToolCallChunk(chunk)
    val toolResultChunk = InferenceStreamProtocol.decodeToolResultChunk(chunk)
    
    when {
        finalFromModel != null -> {
            Log.d(TAG, "FINAL received: length=${finalFromModel.length}")
            // answerBuilder.clear()
            // answerBuilder.append(finalFromModel)
        }
        thinkDelta != null -> {
            // 現在の実装で問題ないが、UI では <details> タグで折りたたみ対応を推奨
            // thinkingBuilder に追加される
        }
        toolCallChunk != null -> {
            // ツール実行中の詳細な進捗状態を UI に通知
            handleToolCallChunk(toolCallChunk)
        }
        toolResultChunk != null -> {
            // ツール実行完了の詳細な結果を UI に通知
            handleToolResultChunk(toolResultChunk)
        }
        else -> {
            // 通常のテキストチャンク
        }
    }
}

/**
 * セッション遷移時の KVキャッシュ管理
 * setCurrentSession() 内で既に実装されているが、補足として記述
 */
fun setCurrentSessionWithCacheManagement(sessionId: Long) {
    Log.d(TAG, "Session transition: sessionId=$sessionId")
    
    // 前の推論をキャンセル（新セッションでの古いキャッシュ影響を防止）
    stopGeneration()
    
    // 前の Conversation をリセット
    // これにより新セッションで新しい Conversation が getOrCreateConversation() で作成される
    // （LiteRtLmEngine の lastSessionId が変わるため）
    Log.d(TAG, "Setting up new session context with fresh KVCache")
}

// ====================================
// 3. UI レイヤー（Compose または XML）での使用例
// ====================================

/*
--- Jetpack Compose での実装例 ---

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiMessage by viewModel.uiMessage.collectAsState(initial = "")
    val toolCallState by viewModel.toolCallProgressState.collectAsState()
    val isThinking by viewModel.isLoading.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize()) {
        // メッセージ表示エリア
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(viewModel.messages.value) { message ->
                ChatMessageComposable(message)
            }
        }
        
        // Thinking プロセスの表示（折りたたみ対応）
        if (isThinking) {
            ExpandableThinkingBox(thinking = message.thinkingContent)
        }
        
        // Tool Call 進捗表示
        ToolCallProgressBar(toolCallState)
        
        // ステータスメッセージ
        if (uiMessage.isNotEmpty()) {
            StatusMessage(uiMessage)
        }
        
        // 入力エリア
        ChatInputField(
            value = viewModel.inputText.value,
            onValueChange = { viewModel.updateInputText(it) },
            onSend = { 
                viewModel.sendMessage(it)
                viewModel.updateInputText("")
            }
        )
    }
}

@Composable
fun ExpandableThinkingBox(thinking: String?) {
    if (thinking.isNullOrBlank()) return
    
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.Blue
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🧠 思考プロセス",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = thinking,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
fun ToolCallProgressBar(toolCallState: ToolCallState?) {
    if (toolCallState == null) return
    
    val backgroundColor = when (toolCallState.status) {
        ToolCallStatus.EXECUTING -> Color(0xFFFFF59D) // 黄色（実行中）
        ToolCallStatus.SUCCESS -> Color(0xFFC8E6C9)   // 緑（成功）
        ToolCallStatus.ERROR -> Color(0xFFFFCDD2)      // 赤（エラー）
        ToolCallStatus.PENDING -> Color(0xFFBBDEFB)    // 青（待機中）
    }
    
    val statusIcon = when (toolCallState.status) {
        ToolCallStatus.EXECUTING -> "⏳"
        ToolCallStatus.SUCCESS -> "✅"
        ToolCallStatus.ERROR -> "❌"
        ToolCallStatus.PENDING -> "⏰"
    }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = statusIcon, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${toolCallState.toolName}: ${toolCallState.status.name}",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${toolCallState.elapsedMs()}ms",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
*/

// ====================================
// 4. XML/Fragment での使用例（従来のアプローチ）
// ====================================

/*
--- Fragment + DataBinding での実装例 ---

class ChatFragment : Fragment() {
    private lateinit var viewModel: ChatViewModel
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Thinking チャンネルの監視と UI 更新
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { messages ->
                    updateChatMessages(messages)
                }
            }
        }
        
        // Tool Call 進捗の監視
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toolCallProgressState.collect { toolCallState ->
                    if (toolCallState != null) {
                        showToolCallProgress(toolCallState)
                    } else {
                        hideToolCallProgress()
                    }
                }
            }
        }
        
        // ステータスメッセージの監視
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiMessage.collect { message ->
                    showStatusMessage(message)
                }
            }
        }
    }
    
    private fun showToolCallProgress(toolCallState: ToolCallState) {
        binding.toolCallProgressView.apply {
            text = "${toolCallState.toolName}: ${toolCallState.status.name} (${toolCallState.elapsedMs()}ms)"
            visibility = View.VISIBLE
        }
    }
    
    private fun hideToolCallProgress() {
        binding.toolCallProgressView.visibility = View.GONE
    }
}
*/
