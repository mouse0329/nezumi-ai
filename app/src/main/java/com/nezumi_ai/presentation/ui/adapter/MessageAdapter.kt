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
import com.nezumi_ai.data.media.MessageMediaStore
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import com.nezumi_ai.presentation.ui.composable.MarkdownLatexText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val onUserPromptRevoke: (MessageEntity) -> Unit = {},
    private val onAiMessageLayoutChanged: () -> Unit = {},
    private val lifecycleOwner: LifecycleOwner? = null,
    private val viewModelStoreOwner: ViewModelStoreOwner? = null
) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    /** ユーザーが明示的に展開したメッセージ ID（生成中は常に自動展開） */
    private val thinkingExpandedByMessageId = mutableSetOf<Long>()
    private var thinkingVisible = true

    private enum class ContentRenderMode {
        Placeholder,
        Markdown
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
                
                // Phase 14: file:// と content:// 両対応
                try {
                    val loadUri = MessageMediaStore.toUri(uri)
                    if (loadUri?.scheme == "file") {
                        // file:// スキーム：直接ファイルから読み込み
                        val path = loadUri?.path
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
                        // content:// スキーム：contentResolver で読み込み
                        imageView.setImageURI(loadUri)
                    }
                } catch (e: Exception) {
                    imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
                
                // タップしてモーダルで大きく表示
                imageView.setOnClickListener {
                    showImageModal(context, uri)
                }
                
                cardView.addView(imageView)
                container.addView(cardView)
            }
        }
        
        // モーダルで画像をフルスクリーン表示
        // Phase 14: file:// URI に対応
        private fun showImageModal(context: Context, imageUri: String) {
            val imageView = ImageView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
                contentDescription = context.getString(R.string.message_image)
            }
            try {
                val loadUri = MessageMediaStore.toUri(imageUri)
                if (loadUri?.scheme == "file") {
                    // file:// スキーム：直接ファイルから読み込み
                    val path = loadUri?.path
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
                } else {
                    // content:// スキーム：contentResolver で読み込み
                    imageView.setImageURI(loadUri)
                }
            } catch (e: Exception) {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setView(imageView)
                .setNegativeButton("閉じる") { dialog, _ -> dialog.dismiss() }
                .show()
                .apply {
                    window?.setLayout(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
        }
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_AI
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ItemMessageUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            UserMessageViewHolder(binding, onUserPromptRevoke)
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

    fun setThinkingVisible(visible: Boolean) {
        if (thinkingVisible == visible) return
        thinkingVisible = visible
        notifyDataSetChanged()
    }
    
    class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onUserPromptRevoke: (MessageEntity) -> Unit
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
                                userImagePreview.setImageURI(
                                    MessageMediaStore.toUri(message.imageUri!!)
                                )
                            } catch (e: Exception) {
                                userImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
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
                revokePromptButton.setOnClickListener {
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
            binding.aiThinkingText.movementMethod = LinkMovementMethod.getInstance()
            binding.aiMessageMarkdownCompose.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            binding.aiMessageMarkdownCompose.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    onAiMessageLayoutChanged()
                }
            }
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
                val thinking = message.thinkingContent
                if (thinkingVisible && !thinking.isNullOrBlank()) {
                    aiThinkingBlock.visibility = View.VISIBLE
                    if (thinking != lastRenderedThinking) {
                        aiThinkingText.text = thinking
                        lastRenderedThinking = thinking
                    }
                } else {
                    thinkingExpandedByMessageId.remove(message.id)
                    aiThinkingBlock.visibility = View.GONE
                    lastRenderedThinking = null
                }

                val hasThinking = thinkingVisible && !thinking.isNullOrBlank()
                val streamThinking = message.isStreaming && hasThinking
                val expanded = streamThinking || message.id in thinkingExpandedByMessageId
                if (hasThinking) {
                    aiThinkingBody.visibility = if (expanded) View.VISIBLE else View.GONE
                    aiThinkingChevron.text = if (expanded) "▲" else "▼"
                    aiThinkingToggleLabel.setText(
                        if (expanded) R.string.gemma_hide_thinking else R.string.gemma_show_thinking
                    )
                    aiThinkingToggleRow.contentDescription = root.context.getString(
                        if (expanded) R.string.gemma_hide_thinking else R.string.gemma_show_thinking
                    )
                    aiThinkingToggleRow.setOnClickListener {
                        if (message.isStreaming && hasThinking) return@setOnClickListener
                        val nowOpen = aiThinkingBody.visibility == View.VISIBLE
                        if (nowOpen) {
                            thinkingExpandedByMessageId.remove(message.id)
                            aiThinkingBody.visibility = View.GONE
                            aiThinkingChevron.text = "▼"
                            aiThinkingToggleLabel.setText(R.string.gemma_show_thinking)
                            aiThinkingToggleRow.contentDescription =
                                root.context.getString(R.string.gemma_show_thinking)
                        } else {
                            thinkingExpandedByMessageId.add(message.id)
                            aiThinkingBody.visibility = View.VISIBLE
                            aiThinkingChevron.text = "▲"
                            aiThinkingToggleLabel.setText(R.string.gemma_hide_thinking)
                            aiThinkingToggleRow.contentDescription =
                                root.context.getString(R.string.gemma_hide_thinking)
                        }
                    }
                }

                when {
                    message.isStreaming && message.content.isBlank() -> {
                        renderPlaceholder(thinking)
                    }
                    else -> {
                        renderMarkdown(message.content)
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
                                aiImagePreview.setImageURI(
                                    MessageMediaStore.toUri(message.imageUri!!)
                                )
                            } catch (e: Exception) {
                                aiImagePreview.setImageResource(android.R.drawable.ic_menu_gallery)
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
                
                // Tool Results
                toolResultsContainer.removeAllViews()
                if (!message.toolResultsJson.isNullOrEmpty()) {
                    val cards = com.nezumi_ai.data.inference.ToolResultCard.listFromJsonArray(message.toolResultsJson)
                    if (cards.isNotEmpty()) {
                        toolResultsContainer.visibility = View.VISIBLE
                        for (card in cards) {
                            val cardView = com.nezumi_ai.presentation.ui.component.ToolResultCardView(binding.root.context)
                            cardView.bind(card)
                            val params = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 0, 0, 8) // bottom margin
                            }
                            cardView.layoutParams = params
                            toolResultsContainer.addView(cardView)
                        }
                    } else {
                        toolResultsContainer.visibility = View.GONE
                    }
                } else {
                    toolResultsContainer.visibility = View.GONE
                }
                
                copyMessageButton.setOnClickListener {
                    val text = if (thinkingVisible && !message.thinkingContent.isNullOrBlank()) {
                        "【${binding.root.context.getString(R.string.gemma_thinking_section_title)}】\n${message.thinkingContent}\n\n【回答】\n${message.content}"
                    } else {
                        message.content
                    }
                    copyAllToClipboard(binding.root.context, text)
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
