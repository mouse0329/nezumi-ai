package com.nezumi_ai.presentation.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.databinding.ItemSessionBinding
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onSessionClick: (Long) -> Unit,
    private val onDeleteClick: (Long) -> Unit
) : ListAdapter<ChatSessionEntity, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding, onSessionClick, onDeleteClick)
    }
    
    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class SessionViewHolder(
        private val binding: ItemSessionBinding,
        private val onSessionClick: (Long) -> Unit,
        private val onDeleteClick: (Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(session: ChatSessionEntity) {
            binding.apply {
                sessionTitle.text = session.name
                sessionDate.text = formatDate(session.lastUpdated)
                
                root.setOnClickListener {
                    onSessionClick(session.id)
                }
                root.setOnLongClickListener {
                    onDeleteClick(session.id)
                    true
                }
                deleteButton.setOnClickListener {
                    onDeleteClick(session.id)
                }
            }
        }
        
        private fun formatDate(timestamp: Long): String {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
    
    class SessionDiffCallback : DiffUtil.ItemCallback<ChatSessionEntity>() {
        override fun areItemsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity) =
            oldItem.id == newItem.id
        
        override fun areContentsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity) =
            oldItem == newItem
    }
}
