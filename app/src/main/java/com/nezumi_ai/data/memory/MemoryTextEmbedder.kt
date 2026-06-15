package com.nezumi_ai.data.memory

import android.content.Context
import android.text.TextUtils
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.nezumi_ai.data.inference.HfAuthManager
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MemoryTextEmbedder {
    private const val TAG = "MemoryTextEmbedder"
    // 実行時に決まる場合があるため定数から可変へ変更。ONNX モデルから取得可能なら上書きする。
    var DIMENSION = 1024

    private var initialized = false
    private var useOnnx = false
    private var onnxModelPath: String? = null
    private var tokenizerInstance: Tokenizer? = null
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputIdsName: String = "input_ids"
    private var attentionMaskName: String? = "attention_mask"

    private val embeddingDirName = "embeddings"
    private val onnxCandidates = listOf("model_quantized_arm64.onnx", "embedding.onnx")
    private val tokenizerCandidates = listOf("tokenizer.json", "tokenizer.model")

    private interface Tokenizer {
        fun encode(text: String): TokenizationResult
    }

    private data class TokenizationResult(
        val ids: IntArray,
        val attentionMask: IntArray
    )

    private data class Token(
        val text: String,
        val score: Double,
        val id: Int
    )

    fun initialize(context: Context): Boolean {
        if (initialized) return useOnnx

        try {
            val embeddingDir = File(context.filesDir, embeddingDirName)
            if (!embeddingDir.exists()) {
                Log.w(TAG, "Embedding directory does not exist: ${embeddingDir.absolutePath}")
                initialized = true
                return false
            }

            val modelFile = onnxCandidates.map { File(embeddingDir, it) }.firstOrNull { it.exists() }
            if (modelFile == null) {
                Log.w(TAG, "No ONNX embedding model found in ${embeddingDir.absolutePath}")
                initialized = true
                return false
            }

            val tokenizerFile = tokenizerCandidates.map { File(embeddingDir, it) }.firstOrNull { it.exists() }
            if (tokenizerFile == null) {
                Log.w(TAG, "No tokenizer file found in ${embeddingDir.absolutePath}")
                initialized = true
                return false
            }

            val classPresent = runCatching { Class.forName("ai.onnxruntime.OrtEnvironment") }.isSuccess
            if (!classPresent) {
                Log.w(TAG, "ONNX Runtime is not available on classpath")
                initialized = true
                return false
            }

            tokenizerInstance = createTokenizer(tokenizerFile)
            if (tokenizerInstance == null) {
                Log.w(TAG, "Tokenizer initialization failed, ONNX embedding disabled")
                initialized = true
                return false
            }

            ortEnvironment = OrtEnvironment.getEnvironment()
            ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, createSessionOptions())
            onnxModelPath = modelFile.absolutePath
            inputIdsName = findInputName(ortSession!!, "input_ids")
            attentionMaskName = findInputName(ortSession!!, "attention_mask")

            val outputInfo = ortSession!!.outputInfo
            val firstOutput = outputInfo.values.firstOrNull()
            val onnxDim = (firstOutput?.info as? ai.onnxruntime.TensorInfo)
                ?.shape?.lastOrNull()?.toInt()
            if (onnxDim != null && onnxDim > 0 && DIMENSION != onnxDim) {
                Log.i(TAG, "ONNX embedding dimension updated: $DIMENSION -> $onnxDim")
                DIMENSION = onnxDim
            }

            useOnnx = true
            initialized = true
            Log.i(TAG, "ONNX embedding initialized: model=${modelFile.absolutePath}, tokenizer=${tokenizerFile.absolutePath}, dim=$DIMENSION")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX embedding backend — will use hash fallback", e)
            cleanupOnnxResources()
            return false
        }
    }

    /**
     * 非同期で IO コンテキスト上に初期化処理を移動するユーティリティ。
     * 呼び出し元は `viewModelScope.launch(Dispatchers.IO) { ... }` ではなく
     * こちらのサスペンド関数を使うことでメインスレッドのブロッキングを避けられます。
     */
    suspend fun initializeAsync(context: Context): Boolean = withContext(Dispatchers.IO) {
        initialize(context)
    }
    fun resetInitialization() {
        if (!useOnnx) {
            Log.d(TAG, "resetInitialization: clearing initialized flag for retry")
            initialized = false
            cleanupOnnxResources()
        }
    }

    fun embed(text: String): FloatArray {
        if (initialized && useOnnx) {
            try {
                val tokenizer = tokenizerInstance ?: run {
                    Log.w(TAG, "embed: tokenizerInstance is null despite useOnnx=true — falling back to hash")
                    return fallbackEmbedding(text)
                }
                val encoding = invokeTokenizerEncode(tokenizer, text)
                return runOnnxEmbedding(encoding)
            } catch (e: Exception) {
                Log.e(TAG, "ONNX embedding failed, falling back to hash embedder", e)
                return fallbackEmbedding(text)
            }
        }
        if (!initialized) {
            Log.d(TAG, "embed: not initialized yet — using hash fallback (call initialize() first)")
        } else {
            Log.d(TAG, "embed: ONNX unavailable (useOnnx=false) — using hash fallback")
        }
        return fallbackEmbedding(text)
    }

    private fun createTokenizer(tokenizerFile: File): Tokenizer? {
        return try {
            val raw = tokenizerFile.readText(Charsets.UTF_8)
            val root = Json.parseToJsonElement(raw).jsonObject
            val model = root["model"]?.jsonObject ?: return null
            val vocabArray = model["vocab"]?.jsonArray ?: return null

            val tokens = vocabArray.mapIndexed { index, element ->
                val tokenElement = element.jsonArray
                val tokenText = tokenElement[0].jsonPrimitive.content
                val tokenScore = tokenElement[1].jsonPrimitive.content.toDouble()
                Token(tokenText, tokenScore, index)
            }
            val tokenByText = tokens.associateBy { it.text }
            val unkId = model["unk_id"]?.jsonPrimitive?.content?.toInt()
                ?: tokenByText["<unk>"]?.id
                ?: 0
            val bosId = tokenByText["<s>"]?.id
            val eosId = tokenByText["</s>"]?.id

            val preTokenizer = root["pre_tokenizer"]?.jsonObject
            val replacement = preTokenizer?.get("replacement")?.jsonPrimitive?.content ?: "▁"
            val prependScheme = preTokenizer?.get("prepend_scheme")?.jsonPrimitive?.content
            val prepend = prependScheme == "always"

            UnigramTokenizer(tokens, unkId, bosId, eosId, replacement, prepend)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create tokenizer from tokenizer.json", e)
            null
        }
    }

    private fun createSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions()
    }

    private fun invokeTokenizerEncode(tokenizer: Tokenizer, text: String): TokenizationResult {
        return tokenizer.encode(text)
    }

    private fun runOnnxEmbedding(encoding: TokenizationResult): FloatArray {
        val session = ortSession ?: return fallbackEmbedding(encoding.ids.joinToString())

        val idsLong = encoding.ids.map { it.toLong() }.toLongArray()
        val shape = longArrayOf(1, idsLong.size.toLong())

        val inputs = mutableMapOf<String, OnnxTensor>()
        val idsTensor = OnnxTensor.createTensor(ortEnvironment!!, LongBuffer.wrap(idsLong), shape)
        inputs[inputIdsName] = idsTensor

        if (encoding.attentionMask.isNotEmpty()) {
            val maskLong = encoding.attentionMask.map { it.toLong() }.toLongArray()
            inputs[attentionMaskName ?: "attention_mask"] = OnnxTensor.createTensor(
                ortEnvironment!!,
                LongBuffer.wrap(maskLong),
                shape
            )
        }

        var resultTensor: FloatArray? = null
        try {
            val result = session.run(inputs)
            result.use { output -> resultTensor = extractEmbedding(output) }
        } finally {
            inputs.values.forEach { try { it.close() } catch (_: Exception) {} }
        }

        return resultTensor ?: fallbackEmbedding(encoding.ids.joinToString())
    }

    private class UnigramTokenizer(
        tokens: List<Token>,
        private val unkId: Int,
        private val bosId: Int?,
        private val eosId: Int?,
        private val replacement: String,
        private val prepend: Boolean
    ) : Tokenizer {
        private val tokensByFirstChar: Map<Char, List<Token>> = tokens
            .groupBy { it.text.firstOrNull() ?: '\u0000' }
            .mapValues { it.value.sortedByDescending { token -> token.text.length } }

        override fun encode(text: String): TokenizationResult {
            val normalized = normalizeText(text)
            val preTokenized = applyMetaspace(normalized)
            val ids = encodeUnigram(preTokenized)
            val attentionMask = IntArray(ids.size) { 1 }
            return TokenizationResult(ids, attentionMask)
        }

        private fun normalizeText(text: String): String {
            return Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        }

        private fun applyMetaspace(text: String): String {
            var result = text.replace(Regex("\\s+"), replacement)
            if (prepend && result.isNotEmpty() && !result.startsWith(replacement)) {
                result = replacement + result
            }
            return result
        }

        private fun encodeUnigram(text: String): IntArray {
            val n = text.length
            val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
            val prev = IntArray(n + 1) { -1 }
            val tokenAt = IntArray(n + 1) { -1 }
            best[0] = 0.0

            for (i in 0 until n) {
                if (best[i] == Double.NEGATIVE_INFINITY) continue
                val candidates = tokensByFirstChar[text[i]] ?: emptyList()
                var matched = false
                for (token in candidates) {
                    if (text.regionMatches(i, token.text, 0, token.text.length)) {
                        val j = i + token.text.length
                        val score = best[i] + token.score
                        if (score > best[j]) {
                            best[j] = score
                            prev[j] = i
                            tokenAt[j] = token.id
                        }
                        matched = true
                    }
                }
                if (!matched) {
                    val j = i + 1
                    if (best[i] > best[j]) {
                        best[j] = best[i]
                        prev[j] = i
                        tokenAt[j] = unkId
                    }
                }
            }

            if (best[n] == Double.NEGATIVE_INFINITY) {
                return buildOutput(listOf(unkId))
            }

            val ids = mutableListOf<Int>()
            var cursor = n
            while (cursor > 0 && prev[cursor] >= 0) {
                ids.add(tokenAt[cursor])
                cursor = prev[cursor]
            }
            if (cursor != 0) {
                return buildOutput(listOf(unkId))
            }
            ids.reverse()
            return buildOutput(ids)
        }

        private fun buildOutput(tokenIds: List<Int>): IntArray {
            val output = mutableListOf<Int>()
            if (bosId != null) output.add(bosId)
            output.addAll(tokenIds)
            if (eosId != null) output.add(eosId)
            return output.toIntArray()
        }
    }

    private fun extractEmbedding(result: OrtSession.Result): FloatArray? {
        if (result.size() == 0) return null
        val value = result[0].value
        return when (value) {
            is FloatArray -> {
                DIMENSION = value.size
                value
            }
            is Array<*> -> {
                val first = value.firstOrNull()
                when (first) {
                    is FloatArray -> {
                        DIMENSION = first.size
                        first
                    }
                    is DoubleArray -> {
                        val arr = first.map { it.toFloat() }.toFloatArray()
                        DIMENSION = arr.size
                        arr
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun findInputName(session: OrtSession, preferredName: String): String {
        return session.inputInfo.keys.firstOrNull { it == preferredName } ?: session.inputInfo.keys.firstOrNull() ?: preferredName
    }

    private fun cleanupOnnxResources() {
        try { ortSession?.close() } catch (_: Exception) {}
        try { ortEnvironment?.close() } catch (_: Exception) {}
        ortSession = null
        ortEnvironment = null
        tokenizerInstance = null
        useOnnx = false
    }

    private fun fallbackEmbedding(text: String): FloatArray {
        val normalized = text.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        val vector = FloatArray(DIMENSION)
        if (normalized.isEmpty()) return vector

        val tokens = tokenize(normalized)
        tokens.forEach { token ->
            val hash = token.hashCode()
            val index = abs(hash % DIMENSION)
            val sign = if ((hash and 1) == 0) 1f else -1f
            vector[index] += sign
        }

        var norm = 0.0
        for (value in vector) norm += (value * value).toDouble()
        val scale = sqrt(norm).toFloat()
        if (scale > 0f) {
            for (i in vector.indices) vector[i] /= scale
        }
        return vector
    }

    private fun tokenize(text: String): List<String> {
        val words = text.split(Regex("[\\s、。,.!?！？:：;；()（）「」『』\\[\\]{}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
        val chars = text.filterNot { it.isWhitespace() }
        val bigrams = if (chars.length >= 2) {
            (0 until chars.length - 1).map { chars.substring(it, it + 2) }
        } else {
            emptyList()
        }
        return words + bigrams
    }
    private const val EMBEDDING_MODEL_URL =
        "https://huggingface.co/hotchpotch/static-embedding-japanese/resolve/main/onnx/model_quantized_arm64.onnx?download=true"
    private const val EMBEDDING_TOKENIZER_URL =
        "https://huggingface.co/hotchpotch/static-embedding-japanese/resolve/main/0_StaticEmbedding/tokenizer.json?download=true"

    suspend fun ensureEmbeddingFilesDownloaded(
        context: Context,
        onProgress: ((file: String, downloaded: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val embeddingDir = File(context.filesDir, embeddingDirName).also { it.mkdirs() }
        val modelFile = File(embeddingDir, "model_quantized_arm64.onnx")
        val tokenizerFile = File(embeddingDir, "tokenizer.json")

        runCatching {
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.i(TAG, "Downloading embedding model...")
                downloadEmbeddingFile(context, EMBEDDING_MODEL_URL, modelFile) { d, t ->
                    onProgress?.invoke("model_quantized_arm64.onnx", d, t)
                }
                Log.i(TAG, "Embedding model downloaded: ${modelFile.length()} bytes")
            } else {
                Log.d(TAG, "Embedding model already exists, skipping download")
            }
            if (!tokenizerFile.exists() || tokenizerFile.length() == 0L) {
                Log.i(TAG, "Downloading embedding tokenizer...")
                downloadEmbeddingFile(context, EMBEDDING_TOKENIZER_URL, tokenizerFile) { d, t ->
                    onProgress?.invoke("tokenizer.json", d, t)
                }
                Log.i(TAG, "Embedding tokenizer downloaded: ${tokenizerFile.length()} bytes")
            } else {
                Log.d(TAG, "Embedding tokenizer already exists, skipping download")
            }
            resetInitialization()
            true
        }.onFailure {
            Log.e(TAG, "Failed to download embedding files", it)
        }.getOrDefault(false)
    }

    private suspend fun downloadEmbeddingFile(
        context: Context,
        urlString: String,
        outFile: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val tmpFile = File("${outFile.absolutePath}.download")
        val token = HfAuthManager.getToken(context)
        val conn = (java.net.URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "nezumi-ai/1.0")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $urlString")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmpFile).buffered(32 * 1024).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, total)
                    }
                    output.flush()
                }
            }
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                throw IllegalStateException("Failed to rename tmp file to ${outFile.absolutePath}")
            }
        } finally {
            conn.disconnect()
            if (tmpFile.exists() && !outFile.exists()) tmpFile.delete()
        }
    }

    fun hasEmbeddingFiles(context: Context): Boolean {
        val embeddingDir = File(context.filesDir, embeddingDirName)
        val modelFile = onnxCandidates.map { File(embeddingDir, it) }
            .firstOrNull { it.exists() && it.length() > 0 } ?: return false
        val tokenizerFile = tokenizerCandidates.map { File(embeddingDir, it) }
            .firstOrNull { it.exists() && it.length() > 0 } ?: return false
        return modelFile.exists() && tokenizerFile.exists()
    }

    data class EmbeddingFileEntry(
        val fileName: String,
        val sizeBytes: Long,
        val exists: Boolean
    )

    fun listEmbeddingFileEntries(context: Context): List<EmbeddingFileEntry> {
        val embeddingDir = File(context.filesDir, embeddingDirName)
        val knownFiles = listOf(
            "model_quantized_arm64.onnx",
            "embedding.onnx",
            "tokenizer.json",
            "tokenizer.model"
        )
        return knownFiles.map { name ->
            val file = File(embeddingDir, name)
            EmbeddingFileEntry(
                fileName = name,
                sizeBytes = if (file.isFile) file.length() else 0L,
                exists = file.isFile && file.length() > 0L
            )
        }.filter { it.exists }
    }

    fun totalEmbeddingSizeBytes(context: Context): Long =
        listEmbeddingFileEntries(context).sumOf { it.sizeBytes }
}