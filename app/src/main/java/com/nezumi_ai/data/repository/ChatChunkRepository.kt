package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.ChatChunkDao
import com.nezumi_ai.data.database.entity.ChatChunkEntity
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.repository.MemoryRepository.Companion.bytesToFloatArray
import com.nezumi_ai.data.repository.MemoryRepository.Companion.cosineSimilarity
import com.nezumi_ai.data.repository.MemoryRepository.Companion.floatArrayToBytes
import com.nezumi_ai.data.repository.MemoryRepository.Companion.l2norm

class ChatChunkRepository(
    private val dao: ChatChunkDao,
    private val context: android.content.Context? = null
) {
    private fun ensureEmbedderInitialized() {
        context?.let { MemoryTextEmbedder.initialize(it) }
    }
    /**
     * メッセージを句読点で分割してベクトル化・保存
     * すでにチャンクがある場合はスキップ
     */
    suspend fun indexMessage(messageId: Long, sessionId: Long, content: String) {
        ensureEmbedderInitialized()
        if (dao.countByMessage(messageId) > 0) return

        val chunks = content
            .split(Regex("[。、！？!?.\n]"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .ifEmpty { listOf(content.trim()) }

        val entities = chunks.mapNotNull { chunk ->
            val embedding = MemoryTextEmbedder.embed(chunk)
            val norm = l2norm(embedding)
            if (norm == 0f) return@mapNotNull null
            ChatChunkEntity(
                messageId = messageId,
                sessionId = sessionId,
                chunkText = chunk,
                embedding = floatArrayToBytes(embedding),
                norm = norm
            )
        }

        if (entities.isNotEmpty()) dao.insertAll(entities)
    }

    /**
     * ハイブリッド検索: コサイン類似度 * 0.7 + 語彙一致率 * 0.3
     */
    suspend fun search(
        query: String,
        sessionId: Long? = null,
        topK: Int = 5,
        minScore: Float = 0.18f
    ): List<SearchResult> {
        ensureEmbedderInitialized()
        val queryEmbedding = MemoryTextEmbedder.embed(query)
        val queryNorm = l2norm(queryEmbedding)
        if (queryNorm == 0f) return emptyList()

        val queryTokens = tokenize(query)

        val candidates = if (sessionId != null) {
            dao.getBySession(sessionId)
        } else {
            dao.getAll()
        }

        return candidates
            .mapNotNull { chunk ->
                val memEmb = bytesToFloatArray(chunk.embedding)
                if (memEmb.size != queryEmbedding.size || chunk.norm == 0f) return@mapNotNull null

                val similarity = cosineSimilarity(queryEmbedding, queryNorm, memEmb, chunk.norm)
                val lexical = if (queryTokens.isEmpty()) 0f else
                    queryTokens.count { chunk.chunkText.contains(it) }.toFloat() / queryTokens.size

                val score = similarity * 0.7f + lexical * 0.3f
                if (score < minScore) return@mapNotNull null

                SearchResult(chunk, score, similarity, lexical)
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    suspend fun deleteBySession(sessionId: Long) = dao.deleteBySession(sessionId)
    suspend fun deleteByMessage(messageId: Long) = dao.deleteByMessage(messageId)

    private fun tokenize(text: String): List<String> {
        val words = text.split(Regex("[\\s、。,.!?！？:：;；()（）「」『』\\[\\]{}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
        val chars = text.filterNot { it.isWhitespace() }
        val bigrams = if (chars.length >= 2) {
            (0 until chars.length - 1).map { chars.substring(it, it + 2) }
        } else emptyList()
        return words + bigrams
    }

    data class SearchResult(
        val chunk: ChatChunkEntity,
        val score: Float,
        val similarity: Float,
        val lexical: Float
    )
}
