package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.MemoryDao
import com.nezumi_ai.data.database.entity.MemoryEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val dao: MemoryDao
) {
    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeActive()

    suspend fun getById(id: Long): MemoryEntity? = dao.getById(id)

    suspend fun saveMemory(
        content: String,
        embedding: FloatArray,
        importance: Float = 0.7f,
        source: String = MemoryEntity.SOURCE_EXTRACTED,
        sessionId: String = "",
        rgaPrevUid: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            MemoryEntity(
                content = content,
                embedding = floatArrayToBytes(embedding),
                norm = l2norm(embedding),
                importance = importance.coerceIn(0f, 1f),
                createdAt = now,
                updatedAt = now,
                lastAccessedAt = now,
                source = source,
                sessionId = sessionId,
                rgaPrevUid = rgaPrevUid
            )
        )
    }

    suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = DEFAULT_THRESHOLD,
        minSimilarity: Float = DEFAULT_SIMILARITY_THRESHOLD,
        markAccessed: Boolean = true
    ): List<ScoredMemory> {
        val queryNorm = l2norm(queryEmbedding)
        if (queryNorm == 0f) {
            android.util.Log.w("MemoryRepository", "SEARCH: query norm is zero (hash fallback embedding?) -> returning empty")
            return emptyList()
        }

        val active = dao.getActive()
        android.util.Log.d("MemoryRepository", "SEARCH: queryDim=${queryEmbedding.size}, activeCount=${active.size}")

        var dimMismatchCount = 0
        var zeroNormCount = 0

        val scored = active
            .mapNotNull { memory ->
                val memoryEmbedding = bytesToFloatArray(memory.embedding)
                if (memoryEmbedding.size != queryEmbedding.size) {
                    dimMismatchCount++
                    return@mapNotNull null
                }
                if (memory.norm == 0f) {
                    zeroNormCount++
                    return@mapNotNull null
                }
                val similarity = cosineSimilarity(queryEmbedding, queryNorm, memoryEmbedding, memory.norm)
                if (similarity < minSimilarity) return@mapNotNull null
                val score = score(
                    similarity = similarity,
                    lastAccessedAt = memory.lastAccessedAt,
                    importance = memory.importance,
                    accessCount = memory.accessCount
                )
                if (score >= threshold) ScoredMemory(memory, score, similarity) else null
            }
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(1))

        if (dimMismatchCount > 0) {
            android.util.Log.w(
                "MemoryRepository",
                "SEARCH: skipped $dimMismatchCount memories due to embedding dimension mismatch " +
                "(queryDim=${queryEmbedding.size}). " +
                "Stored memories may have been indexed with a different embedder (ONNX vs hash). " +
                "Consider clearing memory DB after switching embedding backend."
            )
        }
        if (zeroNormCount > 0) {
            android.util.Log.w("MemoryRepository", "SEARCH: skipped $zeroNormCount memories due to zero norm")
        }
        android.util.Log.d("MemoryRepository", "SEARCH: results=${scored.size}, dimMismatches=$dimMismatchCount, zeroNorms=$zeroNormCount")

        if (markAccessed && scored.isNotEmpty()) {
            dao.markAccessed(scored.map { it.memory.id })
        }
        return scored
    }

    suspend fun softDelete(id: Long) {
        dao.softDelete(id)
    }

    suspend fun softDeleteAll() {
        dao.softDeleteAll()
    }

    /**
     * ② メモリ GC: 500件超で下位10%をsoft-delete
     * importance が低く、参照回数が少なく、最終参照が古いものから削除
     */
    suspend fun runGcIfNeeded() {
        val count = dao.countActive()
        if (count <= MAX_MEMORY_COUNT) return
        val deleteCount = (count * GC_DELETE_RATIO).toInt().coerceAtLeast(1)
        val ids = dao.getLowScoreIds(deleteCount)
        if (ids.isNotEmpty()) {
            dao.softDeleteByIds(ids)
            android.util.Log.d("MemoryRepository", "GC: deleted ${ids.size} low-score memories (was $count)")
        }
    }

    data class ScoredMemory(
        val memory: MemoryEntity,
        val score: Float,
        val similarity: Float
    )

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_SIMILARITY_THRESHOLD = 0f
        const val DEFAULT_TOP_K = 5
        private const val MILLIS_PER_DAY = 86_400_000f
        const val MAX_MEMORY_COUNT = 500
        const val GC_DELETE_RATIO = 0.10f  // 10% を削除

        fun score(similarity: Float, lastAccessedAt: Long, importance: Float, accessCount: Int): Float {
            val days = (System.currentTimeMillis() - lastAccessedAt) / MILLIS_PER_DAY
            val rawDecay = exp(-0.05f * days / ln(accessCount + 2f))
            val decay = max(rawDecay, importance * 0.3f)
            return similarity * decay * importance
        }

        fun cosineSimilarity(a: FloatArray, normA: Float, b: FloatArray, normB: Float): Float {
            if (a.size != b.size || normA == 0f || normB == 0f) return 0f
            var dot = 0.0
            for (i in a.indices) {
                dot += (a[i] * b[i]).toDouble()
            }
            return (dot / (normA * normB)).toFloat()
        }

        fun l2norm(v: FloatArray): Float {
            var sum = 0.0
            for (value in v) {
                sum += (value * value).toDouble()
            }
            return sqrt(sum).toFloat()
        }

        fun meanPool(tokenEmbeddings: Array<FloatArray>): FloatArray {
            if (tokenEmbeddings.isEmpty()) return FloatArray(0)
            val dim = tokenEmbeddings[0].size
            val result = FloatArray(dim)
            for (vec in tokenEmbeddings) {
                for (i in 0 until dim) {
                    result[i] += vec[i]
                }
            }
            for (i in 0 until dim) {
                result[i] /= tokenEmbeddings.size
            }
            return result
        }

        fun floatArrayToBytes(values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        fun bytesToFloatArray(bytes: ByteArray): FloatArray {
            if (bytes.isEmpty()) return FloatArray(0)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
        }
    }
}