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
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
import com.nezumi_ai.databinding.ItemMessageUserBinding
import com.nezumi_ai.databinding.ItemMessageAiBinding
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.media.MessageMediaStore
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val onUserPromptRevoke: (MessageEntity) -> Unit = {}
) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    /** ユーザーが明示的に折りたたんだメッセージ ID（Gallery: 完了後も開いたままがデフォルト） */
    private val thinkingCollapsedByMessageId = mutableSetOf<Long>()
    private var thinkingVisible = true
    
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
            AiMessageViewHolder(binding)
        }
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserMessageViewHolder -> holder.bind(getItem(position))
            is AiMessageViewHolder -> holder.bind(getItem(position))
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
                        // Show image
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
                    
                    if (!message.audioUri.isNullOrEmpty()) {
                        // Show audio player
                        userImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(message.audioUri, userAudioPlayButton, userAudioDuration)
                    }
                } else {
                    mediaContainer.visibility = View.GONE
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
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(
                        binding.root.context,
                        MessageMediaStore.toUri(audioUri)
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
    
    inner class AiMessageViewHolder(private val binding: ItemMessageAiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        private val markwon = Markwon.builder(binding.root.context)
            .usePlugin(TablePlugin.create(binding.root.context))
            .build()

        init {
            binding.aiMessageText.movementMethod = LinkMovementMethod.getInstance()
            binding.aiThinkingText.movementMethod = LinkMovementMethod.getInstance()
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
                    markwon.setMarkdown(aiThinkingText, thinking)
                } else {
                    thinkingCollapsedByMessageId.remove(message.id)
                    aiThinkingBlock.visibility = View.GONE
                }

                val hasThinking = thinkingVisible && !thinking.isNullOrBlank()
                val streamThinking = message.isStreaming && hasThinking
                val expanded = streamThinking || message.id !in thinkingCollapsedByMessageId
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
                            thinkingCollapsedByMessageId.add(message.id)
                            aiThinkingBody.visibility = View.GONE
                            aiThinkingChevron.text = "▼"
                            aiThinkingToggleLabel.setText(R.string.gemma_show_thinking)
                            aiThinkingToggleRow.contentDescription =
                                root.context.getString(R.string.gemma_show_thinking)
                        } else {
                            thinkingCollapsedByMessageId.remove(message.id)
                            aiThinkingBody.visibility = View.VISIBLE
                            aiThinkingChevron.text = "▲"
                            aiThinkingToggleLabel.setText(R.string.gemma_hide_thinking)
                            aiThinkingToggleRow.contentDescription =
                                root.context.getString(R.string.gemma_hide_thinking)
                        }
                    }
                }

                when {
                    message.isStreaming && message.content.isBlank() ->
                        aiMessageText.text = binding.root.context.getString(
                            if (thinking.isNullOrBlank()) {
                                R.string.response_generating
                            } else {
                                R.string.gemma_answer_generating_hint
                            }
                        )
                    else -> markwon.setMarkdown(aiMessageText, message.content)
                }
                aiMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                
                // Media handling
                if (!message.imageUri.isNullOrEmpty() || !message.audioUri.isNullOrEmpty()) {
                    mediaContainer.visibility = View.VISIBLE
                    
                    if (!message.imageUri.isNullOrEmpty()) {
                        // Show image
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
                    
                    if (!message.audioUri.isNullOrEmpty()) {
                        // Show audio player
                        aiImagePreview.visibility = View.GONE
                        audioPlaybackContainer.visibility = View.VISIBLE
                        setupAudioPlayback(message.audioUri, aiAudioPlayButton, aiAudioDuration)
                    }
                } else {
                    mediaContainer.visibility = View.GONE
                }
                
                copyMessageButton.setOnClickListener {
                    val text = if (thinkingVisible && !message.thinkingContent.isNullOrBlank()) {
                        "【${binding.root.context.getString(R.string.gemma_thinking_section_title)}】\n${message.thinkingContent}\n\n【回答】\n${message.content}"
                    } else {
                        message.content
                    }
                    copyAllToClipboard(binding.root.context, text)
                }
            }
        }
        
        private fun setupAudioPlayback(audioUri: String, playButton: View, durationText: View) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(
                        binding.root.context,
                        MessageMediaStore.toUri(audioUri)
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
                oldItem.role == newItem.role &&
                oldItem.imageUri == newItem.imageUri &&
                oldItem.audioUri == newItem.audioUri
        }
    }

}
