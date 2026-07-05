package com.nezumi_ai.presentation.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
import com.nezumi_ai.databinding.ItemMessageUserBinding
import com.nezumi_ai.databinding.ItemMessageAiBinding
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.stripGemmaTokens
import com.nezumi_ai.data.media.MessageMediaStore
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.presentation.ui.component.ImageViewerDialog
import com.nezumi_ai.presentation.ui.component.ToolResultCardView
import com.nezumi_ai.presentation.ui.composable.PersistedToolCallIndicators
import com.nezumi_ai.presentation.ui.composable.StreamingToolCallIndicator
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val onUserPromptRevoke: (MessageEntity) -> Unit = {},
    private val onAiMessageLayoutChanged: () -> Unit = {},
    private val onAiMessageSpeak: (MessageEntity, String) -> Unit = { _, _ -> },
    private val lifecycleOwner: LifecycleOwner? = null,
    private val viewModelStoreOwner: ViewModelStoreOwner? = null
) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    /**
     * 生成中フラグ。true の間は「取り消しボタン」を非表示にする。
     * Bug fix: 生成中に取り消しボタンが表示されると、推論中の KV キャッシュと
     * メッセージストアの整合が崩れるため、UI 上で一切押させないようにする。
     */
    private var isGenerating: Boolean = false

    // ★ Thinking 表示仕様：
    //   - 設定の Thinking スイッチ (OFF/ON) に依存しない。
    //     OFF / Instant モード中でもモデルが思考を出したら隠さず表示する。
    //   - 生成中/生成後を問わず、Thinking ブロックは常に展開したまま表示する。
    //   - そのため、旧「展開状態を覚える」集合は互換のため残すが現在は使わない。
    private val thinkingExpandedByMessageId = mutableSetOf<Long>()
    private var speakingMessageId: Long? = null
    private var streamingMessageId: Long? = null
    private var streamingToolCallState: ToolCallState? = null

    private enum class ContentRenderMode {
        Placeholder,
        Markdown
    }

    /**
     * 生成状態をセットし、UI を再描画して取り消しボタンの表示/非表示を切り替える。
     */
    fun setIsGenerating(generating: Boolean) {
        if (isGenerating == generating) return
        isGenerating = generating
        // ユーザーメッセージの取り消しボタン表示を全体で再評価させるため、
        // リスト全体を invalidate する。件数は一般的に多くないため cost は軽い。
        notifyDataSetChanged()
    }

    fun setSpeakingMessageId(messageId: Long?) {
        val oldId = speakingMessageId
        if (oldId == messageId) return
        speakingMessageId = messageId
        notifyMessageChanged(oldId)
        notifyMessageChanged(messageId)
    }

    fun setStreamingToolCallState(messageId: Long?, state: ToolCallState?) {
        val oldId = streamingMessageId
        streamingMessageId = messageId
        streamingToolCallState = state
        if (oldId != messageId) {
            notifyMessageChanged(oldId)
        }
        notifyMessageChanged(messageId)
    }

    private fun notifyMessageChanged(messageId: Long?) {
        if (messageId == null) return
        val index = currentList.indexOfFirst { it.id == messageId }
        if (index >= 0) notifyItemChanged(index)
    }
    
    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_AI = 1
        
        fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

        private fun copyAllToClipboard(context: Context, content: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("message", content))
                Toast.makeText(
                    context,
                    context.getString(R.string.copied_to_clipboard),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun loadImageIntoView(imageView: ImageView, uri: String) {
            try {
                val loadUri = MessageMediaStore.toUri(uri)
                if (loadUri?.scheme == "file") {
                    val path = loadUri.path
                    if (path != null && java.io.File(path).exists()) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                        } else {
                            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    } else {
                        imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                } else if (loadUri != null) {
                    imageView.setImageURI(loadUri)
                } else {
                    imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } catch (e: Exception) {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

        // Phase 11: 複数画像プレビュー用ヘルパー関数（送信前と統一）
        // Phase 14: file:// URI に対応して画像読み込み
        fun setupMultipleImagePreview(imageUris: List<String>, container: LinearLayout, context: Context) {
            container.removeAllViews()
            for (uri in imageUris) {
                // CardView を使用して角丸・ボーダー実現
                val cardView = androidx.cardview.widget.CardView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                        setMargins(8, 8, 8, 8)  // 16dp spacing (両側8dp)
                    }
                    radius = 12f  // 角丸
                    cardElevation = 4f  // 影
                    setCardBackgroundColor(android.graphics.Color.WHITE)
                }
                
                val imageView = ImageView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(250, 250)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.message_image)
                }
                
                loadImageIntoView(imageView, uri)
                
                // タップしてモーダルで大きく表示
                imageView.setOnClickListener {
                    ImageViewerDialog.show(context, uri)
                }
                
                cardView.addView(imageView)
                container.addView(cardView)
            }
        }
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_AI
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ItemMessageUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            UserMessageViewHolder(binding, onUserPromptRevoke) { isGenerating }
        } else {
            val binding = ItemMessageAiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            AiMessageViewHolder(binding, onAiMessageLayoutChanged, lifecycleOwner, viewModelStoreOwner)
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserMessageViewHolder -> holder.bind(getItem(position))
            is AiMessageViewHolder -> holder.bind(getItem(position))
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // Recycler 再利用時は必ずキャッシュをクリアして再描画を保証する。
        // テーブルが描画されないケースを避けるため、安全側で毎回リセットする。
        if (holder is AiMessageViewHolder) {
            holder.clearCache()
        }
    }

    
    class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onUserPromptRevoke: (MessageEntity) -> Unit,
        private val isGeneratingProvider: () -> Boolean = { false }
    ) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        
        fun bind(message: MessageEntity) {
            if (BuildConfig.DEBUG) {
                Log.d("MessageAdapter", "BIND_USER_MESSAGE: id=${message.id} content='${message.content}'")
            }
            binding.apply {
                userMessageText.text = message.content
                userMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                
                // Media handling
                if (!message.imageUri.isNullOrEmpty() || !message.audioUri.isNullOrEmpty()) {
                    mediaContainer.visibility = View.VISIBLE
                    
                    if (!message.imageUri.isNullOrEmpty()) {
                        // Phase 11: 複数画像対応
                        val imageUris = message.imageUri!!.split(",").filter { it.isNotBlank() }
                        if (imageUris.size > 1) {
                            // 複数画像：HorizontalScrollView で表示
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            userImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(imageUris, imageContainer, binding.root.context)
                        } else {
                            // 単一画像：従来通り表示
                            imageScrollView.visibility = View.GONE
                            singleImageContainer.visibility = View.VISIBLE
                            userImagePreview.visibility = View.VISIBLE
                            audioPlaybackContainer.visibility = View.GONE
                            try {
                                loadImageIntoView(userImagePreview, message.imageUri!!)
                            } catch (e: Exception) {
                                userImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                            userImagePreview.setOnClickListener {
                                ImageViewerDialog.show(binding.root.context, message.imageUri!!)
                            }
                        }
                    }
                    
                    if (!message.audioUri.isNullOrEmpty()) {
                        // Show audio player
                        imageScrollView.visibility = View.GONE
                        singleImageContainer.visibility = View.GONE
                        userImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(message.audioUri, userAudioPlayButton, userAudioDuration)
                    }
                } else {
                    mediaContainer.visibility = View.GONE
                    userImagePreview.visibility = View.GONE
                }
                
                copyMessageButton.setOnClickListener {
                    copyAllToClipboard(binding.root.context, message.content)
                }
                // Bug fix: 生成中に取り消しボタンが見えてしまう不具合への対処。
                // bind のたびにジェネレート状態を参照して可視性を制御する。
                val generating = isGeneratingProvider()
                revokePromptButton.visibility = if (generating) View.GONE else View.VISIBLE
                revokePromptButton.isEnabled = !generating
                revokePromptButton.setOnClickListener {
                    if (isGeneratingProvider()) return@setOnClickListener
                    onUserPromptRevoke(message)
                }
            }
        }
        
        private fun setupAudioPlayback(audioUri: String, playButton: View, durationText: View) {
            try {
                val audioUriObj = MessageMediaStore.toUri(audioUri) ?: return
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(
                        binding.root.context,
                        audioUriObj
                    )
                    setOnPreparedListener { mp ->
                        val duration = mp.duration / 1000
                        val minutes = duration / 60
                        val seconds = duration % 60
                        (durationText as? android.widget.TextView)?.text = String.format("%d:%02d", minutes, seconds)
                    }
                    prepareAsync()
                }
                
                playButton.setOnClickListener {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        (it as? android.widget.Button)?.text = "▶"
                    } else {
                        mediaPlayer?.start()
                        (it as? android.widget.Button)?.text = "⏸"
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(binding.root.context, "音声の再生に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    inner class AiMessageViewHolder(
        private val binding: ItemMessageAiBinding,
        private val onAiMessageLayoutChanged: () -> Unit,
        private val lifecycleOwner: LifecycleOwner? = null,
        private val viewModelStoreOwner: ViewModelStoreOwner? = null
    ) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        // 前回レンダリングしたcontentをキャッシュ。同じ内容なら再レンダリングしない。
        private var lastRenderedContent: String? = null
        private var lastRenderedThinking: String? = null
        private var lastRenderedContentMode: ContentRenderMode? = null

        fun clearCache() {
            lastRenderedContent = null
            lastRenderedThinking = null
            lastRenderedContentMode = null
        }

        init {
            lifecycleOwner?.let { owner ->
                binding.root.setViewTreeLifecycleOwner(owner)
                (owner as? SavedStateRegistryOwner)?.let {
                    binding.root.setViewTreeSavedStateRegistryOwner(it)
                }
            }
            (viewModelStoreOwner as? androidx.lifecycle.ViewModelStoreOwner)?.let {
                binding.root.setViewTreeViewModelStoreOwner(it)
            }
            binding.aiMessageText.movementMethod = LinkMovementMethod.getInstance()
            binding.aiMessageMarkdownCompose.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            binding.aiThinkingMarkdownCompose.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            binding.aiStreamingToolCallCompose.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            binding.aiMessageMarkdownCompose.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    onAiMessageLayoutChanged()
                }
            }
            // ★ Scroll fix: aiThinkingMarkdownCompose の layoutChangeListener は
            //   Thinking ブロックの展開/折りたたみやトークン追加のたびに冗長発火し、
            //   自動追従スクロールを不安定化させる原因になっていたため削除。
            //   本文 (aiMessageMarkdownCompose / aiMessageText) の高さ変化だけで
            //   下端追従判定は十分機能する。
            binding.aiMessageText.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    onAiMessageLayoutChanged()
                }
            }
        }

        fun bind(message: MessageEntity) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "MessageAdapter",
                    "BIND_AI_MESSAGE: id=${message.id} content='${message.content.take(50)}'..."
                )
            }
            binding.apply {
                // ★ v5.1 Thinking 表示仕様：
                //   - 設定スイッチの ON/OFF に一切依存せず、thinkingContent が非空であれば表示。
                //     (OFF のときに誤って生成されたものもバックグラウンド実行のるつぼとして「隠さず」出す)
                //   - 生成中は常に展開、閉じトグルは表示しない。
                //   - 生成完了後は閉じトグルを表示し、ユーザーが閉じた ID を記録して保持。
                val thinking = message.thinkingContent?.stripGemmaTokens()
                val hasThinking = !thinking.isNullOrBlank()
                if (hasThinking) {
                    aiThinkingBlock.visibility = View.VISIBLE
                    if (thinking != lastRenderedThinking) {
                        renderThinkingMarkdown(thinking)
                        lastRenderedThinking = thinking
                    }

                    aiThinkingBody.visibility = View.VISIBLE
                    aiThinkingMarkdownCompose.visibility = View.VISIBLE
                    aiThinkingToggleRow.visibility = View.GONE
                    aiThinkingToggleRow.setOnClickListener(null)
                } else {
                    aiThinkingBlock.visibility = View.GONE
                    aiThinkingBody.visibility = View.GONE
                    aiThinkingMarkdownCompose.visibility = View.GONE
                    lastRenderedThinking = null
                }

                val visibleContent = message.content.stripGemmaTokens()
                val visibleThinking = thinking

                val persistedToolCards = if (!message.isStreaming && !message.toolResultsJson.isNullOrBlank()) {
                    ToolResultCard.listFromJsonArray(message.toolResultsJson)
                } else {
                    emptyList()
                }
                val showStreamingToolCall = message.isStreaming &&
                    message.id == streamingMessageId &&
                    streamingToolCallState != null &&
                    streamingToolCallState !is ToolCallState.Done
                when {
                    showStreamingToolCall -> {
                        aiStreamingToolCallCompose.visibility = View.VISIBLE
                        val toolState = streamingToolCallState!!
                        aiStreamingToolCallCompose.setContent {
                            NezumiComposeTheme {
                                StreamingToolCallIndicator(state = toolState)
                            }
                        }

                        // 画像生成中の場合は、画像コンテナを表示して領域を確保
                        val toolName = when (toolState) {
                            is ToolCallState.Executing -> toolState.toolName
                            is ToolCallState.Result -> toolState.toolName
                            else -> null
                        }
                        if (toolName == "generate_image") {
                            mediaContainer.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.VISIBLE
                            aiImagePreview.visibility = View.VISIBLE
                            aiImagePreview.setImageResource(R.drawable.ic_image)
                            aiImagePreview.alpha = 0.3f
                        }
                    }
                    persistedToolCards.isNotEmpty() -> {
                        aiStreamingToolCallCompose.visibility = View.VISIBLE
                        aiStreamingToolCallCompose.setContent {
                            NezumiComposeTheme {
                                PersistedToolCallIndicators(cards = persistedToolCards)
                            }
                        }
                        aiImagePreview.alpha = 1.0f
                        aiImagePreview.setOnClickListener(null)
                    }
                    else -> {
                        aiStreamingToolCallCompose.visibility = View.GONE
                        aiImagePreview.alpha = 1.0f
                        aiImagePreview.setOnClickListener(null)
                    }
                }

                when {
                    message.isStreaming && visibleContent.isBlank() -> {
                        renderPlaceholder(visibleThinking)
                    }
                    else -> {
                        renderMarkdown(visibleContent)
                    }
                }

                aiMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                
                // Media handling
                if (!message.imageUri.isNullOrEmpty() || !message.audioUri.isNullOrEmpty()) {
                    mediaContainer.visibility = View.VISIBLE
                    
                    if (!message.imageUri.isNullOrEmpty()) {
                        // Phase 11: 複数画像対応
                        val imageUris = message.imageUri!!.split(",").filter { it.isNotBlank() }
                        if (imageUris.size > 1) {
                            // 複数画像：HorizontalScrollView で表示
                            imageScrollView.visibility = View.VISIBLE
                            singleImageContainer.visibility = View.GONE
                            aiImagePreview.visibility = View.GONE
                            audioPlaybackContainer.visibility = View.GONE
                            setupMultipleImagePreview(imageUris, imageContainer, binding.root.context)
                        } else {
                            // 単一画像：従来通り表示
                            imageScrollView.visibility = View.GONE
                            singleImageContainer.visibility = View.VISIBLE
                            aiImagePreview.visibility = View.VISIBLE
                            audioPlaybackContainer.visibility = View.GONE
                            try {
                                loadImageIntoView(aiImagePreview, message.imageUri!!)
                            } catch (e: Exception) {
                                aiImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                            aiImagePreview.setOnClickListener {
                                ImageViewerDialog.show(binding.root.context, message.imageUri!!)
                            }
                        }
                    }
                    
                    if (!message.audioUri.isNullOrEmpty()) {
                        // Show audio player
                        imageScrollView.visibility = View.GONE
                        singleImageContainer.visibility = View.GONE
                        aiImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(message.audioUri, aiAudioPlayButton, aiAudioDuration)
                    }
                } else {
                    mediaContainer.visibility = View.GONE
                    aiImagePreview.visibility = View.GONE
                }
                
                toolResultsContainer.removeAllViews()
                if (!message.isStreaming && persistedToolCards.isNotEmpty()) {
                    toolResultsContainer.visibility = View.VISIBLE
                    for (card in persistedToolCards) {
                        val cardView = ToolResultCardView(binding.root.context)
                        cardView.bind(card)
                        cardView.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = 8
                        }
                        toolResultsContainer.addView(cardView)
                    }
                } else {
                    toolResultsContainer.visibility = View.GONE
                }

                copyMessageButton.setOnClickListener {
                    val text = if (!message.thinkingContent.isNullOrBlank()) {
                        "【${binding.root.context.getString(R.string.gemma_thinking_section_title)}】\n${message.thinkingContent?.stripGemmaTokens()}\n\n【回答】\n${message.content.stripGemmaTokens()}"
                    } else {
                        message.content.stripGemmaTokens()
                    }
                    copyAllToClipboard(binding.root.context, text)
                }

                val speakText = message.content.stripGemmaTokens().trim()
                val canSpeak = !message.isStreaming && speakText.isNotBlank()
                    && com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED
                val isSpeakingThisMessage = speakingMessageId == message.id
                speakMessageButton.visibility =
                    if (canSpeak && !isSpeakingThisMessage) View.VISIBLE else View.GONE
                speakMessageButton.isEnabled = speakingMessageId == null
                speakMessageProgress.visibility =
                    if (canSpeak && isSpeakingThisMessage) View.VISIBLE else View.GONE
                speakMessageButton.setOnClickListener {
                    onAiMessageSpeak(message, speakText)
                }

                val tps = message.generationTps
                val generationTimeMs = message.generationTimeMs
                if (!message.isStreaming && ((tps != null && tps > 0f) || (generationTimeMs != null && generationTimeMs > 0L))) {
                    tvTps.visibility = View.VISIBLE
                    tvTps.text = listOfNotNull(
                        tps?.takeIf { it > 0f }?.let { String.format("%.1f t/s", it) },
                        generationTimeMs?.takeIf { it > 0L }?.let { formatGenerationTime(it) }
                    ).joinToString("  ·  ")
                } else {
                    tvTps.visibility = View.GONE
                }
            }
        }

        private fun renderPlaceholder(thinking: String?) {
            val text = binding.root.context.getString(
                if (thinking.isNullOrBlank()) {
                    R.string.response_generating
                } else {
                    R.string.gemma_answer_generating_hint
                }
            )
            if (
                lastRenderedContent == text &&
                lastRenderedContentMode == ContentRenderMode.Placeholder
            ) return

            binding.aiMessageText.text = text
            binding.aiMessageText.visibility = View.VISIBLE
            binding.aiMessageMarkdownCompose.visibility = View.GONE
            lastRenderedContent = text
            lastRenderedContentMode = ContentRenderMode.Placeholder
        }

        private fun formatGenerationTime(ms: Long): String {
            return if (ms < 60_000L) {
                String.format("生成 %.1fs", ms / 1000f)
            } else {
                val totalSeconds = ms / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                String.format("生成 %d:%02d", minutes, seconds)
            }
        }

        private fun renderMarkdown(content: String) {
            if (
                lastRenderedContent == content &&
                lastRenderedContentMode == ContentRenderMode.Markdown
            ) return

            binding.aiMessageText.visibility = View.GONE
            binding.aiMessageMarkdownCompose.visibility = View.VISIBLE

            binding.aiMessageMarkdownCompose.setContent {
                GalleryMarkdownText(content = content)
            }
            binding.aiMessageMarkdownCompose.post { onAiMessageLayoutChanged() }
            lastRenderedContent = content
            lastRenderedContentMode = ContentRenderMode.Markdown
        }

        private fun renderThinkingMarkdown(content: String) {
            // ★ Scroll fix:
            //   - early-return 判定は lastRenderedThinking の同値だけで十分。
            //     lastRenderedContentMode は「本文」用のフラグなのでここで参照/更新しない。
            //   - aiThinkingMarkdownCompose の visibility は呼び出し元の展開/折りたたみロジックが管理する。
            //     ここで強制 VISIBLE にすると自動閉じ後に再描画が入ったとき勝手に開いてしまう。
            //   - onAiMessageLayoutChanged() は呼ばない。Thinking ブロックのレイアウト変化で
            //     毎トークン自動スクロールが再スケジュールされ、追従が乱れるため。
            if (lastRenderedThinking == content) return

            binding.aiThinkingMarkdownCompose.setContent {
                GalleryMarkdownText(content = content)
            }
        }

        @Composable
        private fun GalleryMarkdownText(content: String) {
            val shape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(colorResource(id = R.color.surface_card), shape)
                    .border(
                        BorderStroke(1.dp, colorResource(id = R.color.border)),
                        shape
                    )
                    .padding(11.dp)
            ) {
                SelectionContainer {
                    ProvideTextStyle(
                        value = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = colorResource(id = R.color.text_primary),
                            letterSpacing = 0.2.sp
                        )
                    ) {
                        MaterialTheme {
                            MarkdownLatexText(text = content, textSize = 40f)
                        }
                    }
                }
            }
        }
        
        private fun setupAudioPlayback(audioUri: String, playButton: View, durationText: View) {
            try {
                val audioUriObj = MessageMediaStore.toUri(audioUri) ?: return
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(
                        binding.root.context,
                        audioUriObj
                    )
                    setOnPreparedListener { mp ->
                        val duration = mp.duration / 1000
                        val minutes = duration / 60
                        val seconds = duration % 60
                        (durationText as? android.widget.TextView)?.text = String.format("%d:%02d", minutes, seconds)
                    }
                    prepareAsync()
                }
                
                playButton.setOnClickListener {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        (it as? android.widget.Button)?.text = "▶"
                    } else {
                        mediaPlayer?.start()
                        (it as? android.widget.Button)?.text = "⏸"
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(binding.root.context, "音声の再生に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    @Composable
    private fun NezumiComposeTheme(content: @Composable () -> Unit) {
        ProvideTextStyle(
            value = TextStyle(
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = colorResource(id = R.color.text_primary),
                letterSpacing = 0.2.sp
            )
        ) {
            MaterialTheme {
                content()
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean =
            oldItem.id == newItem.id
        
        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            // content と thinkingContent の変更のみをチェック（timestamp 変更は無視）
            // これにより、ストリーミング中の incremental update を正確に検出
            return oldItem.content == newItem.content &&
                oldItem.thinkingContent == newItem.thinkingContent &&
                oldItem.isStreaming == newItem.isStreaming &&
                oldItem.role == newItem.role &&
                oldItem.imageUri == newItem.imageUri &&
                oldItem.audioUri == newItem.audioUri &&
                oldItem.generationTps == newItem.generationTps &&
                oldItem.generationTimeMs == newItem.generationTimeMs
        }
    }

}

