package com.nezumi_ai.presentation.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.databinding.ItemDrawerSessionBinding
import com.nezumi_ai.databinding.ItemDrawerSessionLabelBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class DrawerHistoryItem {
    data class Label(val label: String) : DrawerHistoryItem()
    data class Session(val session: ChatSessionEntity) : DrawerHistoryItem()
}

class DrawerHistoryAdapter(
    private val onClick: (ChatSessionEntity) -> Unit,
    private val onLongClick: (ChatSessionEntity) -> Unit,
    private val onListUpdated: (() -> Unit)? = null
) : ListAdapter<DrawerHistoryItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_LABEL = 0
        private const val TYPE_SESSION = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DrawerHistoryItem.Label -> TYPE_LABEL
            is DrawerHistoryItem.Session -> TYPE_SESSION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_LABEL -> {
                val binding = ItemDrawerSessionLabelBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                LabelViewHolder(binding)
            }
            TYPE_SESSION -> {
                val binding = ItemDrawerSessionBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                SessionViewHolder(binding, onClick, onLongClick)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DrawerHistoryItem.Label -> {
                (holder as LabelViewHolder).bind(item.label)
            }
            is DrawerHistoryItem.Session -> {
                (holder as SessionViewHolder).bind(item.session)
            }
        }
    }

    override fun submitList(list: List<DrawerHistoryItem>?) {
        super.submitList(list)
        onListUpdated?.invoke()
    }

    class LabelViewHolder(
        private val binding: ItemDrawerSessionLabelBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.labelText.text = label
        }
    }

    class SessionViewHolder(
        private val binding: ItemDrawerSessionBinding,
        private val onClick: (ChatSessionEntity) -> Unit,
        private val onLongClick: (ChatSessionEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(session: ChatSessionEntity) {
            binding.sessionTitle.text = session.name.ifBlank { "無題のチャット" }
            binding.sessionDate.text = formatTime(session.lastUpdated)
            binding.root.setOnClickListener { onClick(session) }
            binding.root.setOnLongClickListener {
                onLongClick(session)
                true
            }
        }

        private fun formatTime(timestamp: Long): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DrawerHistoryItem>() {
        override fun areItemsTheSame(oldItem: DrawerHistoryItem, newItem: DrawerHistoryItem): Boolean {
            return when {
                oldItem is DrawerHistoryItem.Label && newItem is DrawerHistoryItem.Label ->
                    oldItem.label == newItem.label
                oldItem is DrawerHistoryItem.Session && newItem is DrawerHistoryItem.Session ->
                    oldItem.session.id == newItem.session.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: DrawerHistoryItem, newItem: DrawerHistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}
