package com.nezumi_ai.presentation.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.databinding.ItemMessageUserBinding
import com.nezumi_ai.databinding.ItemMessageAiBinding
import com.nezumi_ai.data.database.entity.MessageEntity
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
        fun bind(message: MessageEntity) {
            binding.apply {
                userMessageText.text = message.content
                userMessageTime.text = MessageAdapter.formatTime(message.timestamp)
                revokePromptButton.setOnClickListener {
                    onUserPromptRevoke(message)
                }
            }
        }
    }
    
    class AiMessageViewHolder(private val binding: ItemMessageAiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: MessageEntity) {
            binding.apply {
                aiMessageText.text = message.content
                aiMessageTime.text = MessageAdapter.formatTime(message.timestamp)
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
