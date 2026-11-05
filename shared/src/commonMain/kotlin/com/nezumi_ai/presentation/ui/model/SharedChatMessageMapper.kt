package com.nezumi_ai.presentation.ui.model

import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.presentation.ui.composable.SharedChatMessage
import com.nezumi_ai.presentation.ui.composable.SharedMessageRole

fun MessageEntity.toSharedChatMessage(): SharedChatMessage? {
    val messageRole = when (role.lowercase()) {
        "user" -> SharedMessageRole.USER
        "assistant" -> SharedMessageRole.ASSISTANT
        else -> return null
    }
    return SharedChatMessage(
        id = id.toString(),
        role = messageRole,
        text = content,
        timestamp = timestamp,
        isStreaming = isStreaming
    )
}

fun Iterable<MessageEntity>.toSharedChatMessages(): List<SharedChatMessage> =
    mapNotNull(MessageEntity::toSharedChatMessage)
