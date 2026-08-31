package com.nezumi_ai.utils

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

object GgufMetadataReader {
    data class Summary(
        val architecture: String,
        val parameterCount: Long,
    )

    private data class CacheEntry(val summary: Summary, val lastModified: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class ChatTemplateCacheEntry(val template: String?, val lastModified: Long)
    private val chatTemplateCache = ConcurrentHashMap<String, ChatTemplateCacheEntry>()

    private const val GGUF_MAGIC = 0x46554747

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    fun readSummary(file: File): Summary {
        require(file.isFile) { "GGUF ファイルが見つかりません" }
        val lastModified = file.lastModified()
        cache[file.absolutePath]?.let { entry ->
            if (entry.lastModified == lastModified) return entry.summary
        }
        val summary = readSummaryFromFile(file)
        cache[file.absolutePath] = CacheEntry(summary, lastModified)
        return summary
    }

    fun invalidate(path: String): Boolean {
        chatTemplateCache.remove(path)
        return cache.remove(path) != null
    }

    /**
     * GGUF メタデータの `tokenizer.chat_template` を読み出す。
     * 読み取れない場合 (非 GGUF / キーなし) は null を返す。
     */
    fun readChatTemplate(file: File): String? {
        if (!file.isFile) return null
        val lastModified = file.lastModified()
        chatTemplateCache[file.absolutePath]?.let { entry ->
            if (entry.lastModified == lastModified) return entry.template
        }
        val template = runCatching { readChatTemplateFromFile(file) }.getOrNull()
        chatTemplateCache[file.absolutePath] = ChatTemplateCacheEntry(template, lastModified)
        return template
    }

    private fun readChatTemplateFromFile(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val magic = raf.readLittleInt()
            if (magic != GGUF_MAGIC) return null
            val version = raf.readLittleUInt32()
            if (version !in 1L..3L) return null
            raf.readLittleUInt64() // tensorCount (不要)
            val metadataCount = raf.readLittleUInt64()
            var i = 0L
            while (i < metadataCount) {
                val key = raf.readGgufString()
                val valueType = raf.readLittleUInt32().toInt()
                if (key == "tokenizer.chat_template") {
                    return readValueAsString(raf, valueType)?.takeIf { it.isNotBlank() }
                }
                skipValue(raf, valueType)
                i++
            }
            return null
        }
    }

    private fun readSummaryFromFile(file: File): Summary {
        RandomAccessFile(file, "r").use { raf ->
            val magic = raf.readLittleInt()
            require(magic == GGUF_MAGIC) { "GGUF ヘッダーではありません" }

            val version = raf.readLittleUInt32()
            require(version in 1L..3L) { "未対応の GGUF バージョンです: $version" }

            val tensorCount = raf.readLittleUInt64()
            val metadataCount = raf.readLittleUInt64()

            var architecture: String? = null
            var parameterCount: Long? = null

            var i = 0L
            while (i < metadataCount) {
                val key = raf.readGgufString()
                val valueType = raf.readLittleUInt32().toInt()
                when {
                    key == "general.architecture" -> {
                        architecture = readValueAsString(raf, valueType)?.takeIf { it.isNotBlank() }
                    }
                    key == "general.parameter_count" ||
                        key == "general.n_params" ||
                        key == "general.params" ||
                        key.endsWith(".parameter_count") ||
                        key.endsWith(".n_params") -> {
                        parameterCount = readValueAsLong(raf, valueType)?.takeIf { it > 0L }
                    }
                    else -> skipValue(raf, valueType)
                }
                i++
            }

            val resolvedParameterCount = parameterCount ?: sumTensorElements(raf, tensorCount)
            return Summary(
                architecture = architecture ?: "不明",
                parameterCount = resolvedParameterCount,
            )
        }
    }

    private fun sumTensorElements(raf: RandomAccessFile, tensorCount: Long): Long {
        var total = 0L
        var i = 0L
        while (i < tensorCount) {
            raf.readGgufString() // tensor name
            val nDimensions = raf.readLittleUInt32().toInt()
            var elements = 1L
            repeat(nDimensions) {
                val dim = raf.readLittleUInt64()
                elements = safeMultiply(elements, dim)
            }
            raf.skipFully(4L) // ggml type
            raf.skipFully(8L) // tensor offset
            total = safeAdd(total, elements)
            i++
        }
        return total
    }

    private fun readValueAsString(raf: RandomAccessFile, valueType: Int): String? {
        return when (valueType) {
            TYPE_STRING -> raf.readGgufString()
            TYPE_UINT8 -> raf.readUnsignedByte().toString()
            TYPE_INT8 -> raf.readByte().toString()
            TYPE_UINT16 -> raf.readLittleUInt16().toString()
            TYPE_INT16 -> raf.readLittleShort().toString()
            TYPE_UINT32 -> raf.readLittleUInt32().toString()
            TYPE_INT32 -> raf.readLittleInt().toString()
            TYPE_UINT64 -> raf.readLittleUInt64().toString()
            TYPE_INT64 -> raf.readLittleLong().toString()
            TYPE_BOOL -> if (raf.readUnsignedByte() != 0) "true" else "false"
            TYPE_FLOAT32 -> java.lang.Float.intBitsToFloat(raf.readLittleInt()).toString()
            TYPE_FLOAT64 -> java.lang.Double.longBitsToDouble(raf.readLittleLong()).toString()
            TYPE_ARRAY -> {
                skipArrayPayload(raf)
                null
            }
            else -> {
                skipValue(raf, valueType)
                null
            }
        }
    }

    private fun readValueAsLong(raf: RandomAccessFile, valueType: Int): Long? {
        return when (valueType) {
            TYPE_UINT8 -> raf.readUnsignedByte().toLong()
            TYPE_INT8 -> raf.readByte().toLong()
            TYPE_UINT16 -> raf.readLittleUInt16().toLong()
            TYPE_INT16 -> raf.readLittleShort().toLong()
            TYPE_UINT32 -> raf.readLittleUInt32()
            TYPE_INT32 -> raf.readLittleInt().toLong()
            TYPE_UINT64 -> raf.readLittleUInt64()
            TYPE_INT64 -> raf.readLittleLong()
            TYPE_BOOL -> if (raf.readUnsignedByte() != 0) 1L else 0L
            TYPE_STRING -> raf.readGgufString().toLongOrNull()
            TYPE_ARRAY -> {
                skipArrayPayload(raf)
                null
            }
            TYPE_FLOAT32 -> java.lang.Float.intBitsToFloat(raf.readLittleInt()).toLong()
            TYPE_FLOAT64 -> java.lang.Double.longBitsToDouble(raf.readLittleLong()).toLong()
            else -> {
                skipValue(raf, valueType)
                null
            }
        }
    }

    private fun skipValue(raf: RandomAccessFile, valueType: Int) {
        when (valueType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> raf.skipFully(1L)
            TYPE_UINT16, TYPE_INT16 -> raf.skipFully(2L)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> raf.skipFully(4L)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> raf.skipFully(8L)
            TYPE_STRING -> raf.skipFully(raf.readLittleUInt64())
            TYPE_ARRAY -> skipArrayPayload(raf)
            else -> throw IllegalArgumentException("未対応の GGUF value type: $valueType")
        }
    }

    private fun skipArrayPayload(raf: RandomAccessFile) {
        val elementType = raf.readLittleUInt32().toInt()
        val count = raf.readLittleUInt64()
        when (elementType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> raf.skipFully(count)
            TYPE_UINT16, TYPE_INT16 -> raf.skipFully(count * 2L)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> raf.skipFully(count * 4L)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> raf.skipFully(count * 8L)
            TYPE_STRING -> {
                var i = 0L
                while (i < count) {
                    raf.skipFully(raf.readLittleUInt64())
                    i++
                }
            }
            TYPE_ARRAY -> {
                var i = 0L
                while (i < count) {
                    skipArrayPayload(raf)
                    i++
                }
            }
            else -> throw IllegalArgumentException("未対応の GGUF array type: $elementType")
        }
    }

    private fun RandomAccessFile.readGgufString(): String {
        val length = readLittleUInt64()
        require(length >= 0L) { "文字列長が不正です" }
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun RandomAccessFile.skipFully(bytes: Long) {
        if (bytes <= 0L) return
        seek(filePointer + bytes)
    }

    private fun RandomAccessFile.readLittleUInt16(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun RandomAccessFile.readLittleShort(): Short {
        return readLittleUInt16().toShort()
    }

    private fun RandomAccessFile.readLittleInt(): Int {
        return Integer.reverseBytes(readInt())
    }

    private fun RandomAccessFile.readLittleUInt32(): Long {
        return readLittleInt().toLong() and 0xffffffffL
    }

    private fun RandomAccessFile.readLittleLong(): Long {
        return java.lang.Long.reverseBytes(readLong())
    }

    private fun RandomAccessFile.readLittleUInt64(): Long {
        val value = readLittleLong()
        require(value >= 0L) { "64bit unsigned integer が Long の範囲を超えています" }
        return value
    }

    private fun safeMultiply(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE
        return left + right
    }
}
