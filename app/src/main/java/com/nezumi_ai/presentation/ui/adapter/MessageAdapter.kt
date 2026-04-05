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
    
    class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onUserPromptRevoke: (MessageEntity) -> Unit
    ) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        
        fun bind(message: MessageEntity) {
            Log.d("MessageAdapter", "BIND_USER_MESSAGE: id=${message.id} content='${message.content}'")
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
    
    class AiMessageViewHolder(private val binding: ItemMessageAiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        private var mediaPlayer: MediaPlayer? = null
        private val markwon = Markwon.builder(binding.root.context)
            .usePlugin(TablePlugin.create(binding.root.context))
            .build()

        init {
            binding.aiMessageText.movementMethod = LinkMovementMethod.getInstance()
        }

        fun bind(message: MessageEntity) {
            Log.d("MessageAdapter", "BIND_AI_MESSAGE: id=${message.id} content='${message.content.take(50)}'...")
            binding.apply {
                markwon.setMarkdown(aiMessageText, message.content)
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
                    copyAllToClipboard(binding.root.context, message.content)
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
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity) =
            oldItem.id == newItem.id
        
        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity) =
            oldItem == newItem
    }

}

