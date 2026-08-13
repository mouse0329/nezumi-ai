package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.ChatSessionDao
import com.nezumi_ai.data.database.dao.ToolCallHistoryDao
import com.nezumi_ai.data.database.entity.ToolCallHistoryEntity
import com.nezumi_ai.data.inference.ToolResultCard
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class ToolCallHistoryRepository(
    private val dao: ToolCallHistoryDao,
    private val sessionDao: ChatSessionDao? = null
) {
    fun observeRecent(limit: Int = 500): Flow<List<ToolCallHistoryEntity>> = dao.observeRecent(limit)
    suspend fun getRecent(limit: Int = 500) = dao.getRecent(limit)
    suspend fun search(q: String, limit: Int = 200) = dao.search(q, limit)
    suspend fun clearAll() = dao.clearAll()

    suspend fun recordFromToolResultsJson(sessionId: Long, messageId: Long?, toolResultsJson: String?) {
        if (toolResultsJson.isNullOrBlank()) return
        val cards = ToolResultCard.listFromJsonArray(toolResultsJson)
        if (cards.isEmpty()) return
        val sessionName = sessionDao?.getSessionById(sessionId)?.name
        val now = System.currentTimeMillis()
        dao.insertAll(cards.map { card ->
            ToolCallHistoryEntity(
                timestamp = now,
                sessionId = sessionId,
                sessionName = sessionName,
                toolName = card.toolName,
                query = extractQuery(card.payload),
                success = card.success,
                resultSummary = extractSummary(card.payload),
                messageId = messageId
            )
        })
    }

    private fun extractQuery(payload: Map<String, JsonElement>): String? {
        for (k in listOf("query", "prompt", "text", "q", "search", "message", "label", "name", "url", "path")) {
            val v = payload[k]?.let { asString(it) }
            if (!v.isNullOrBlank()) return v
        }
        val compact = payload.entries.take(3).joinToString(", ") { (k, v) ->
            "$k=${asString(v)?.take(80) ?: "..."}"
        }
        return compact.ifBlank { null }
    }

    private fun extractSummary(payload: Map<String, JsonElement>): String? {
        for (k in listOf("result", "message", "status", "error", "summary")) {
            val v = payload[k]?.let { asString(it) }
            if (!v.isNullOrBlank()) return v.take(200)
        }
        return null
    }

    private fun asString(el: JsonElement): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull ?: el.toString()
        else -> el.toString().take(120)
    }
}
