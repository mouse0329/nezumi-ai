package com.nezumi_ai.data.memory

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.nezumi_ai.data.inference.HfAuthManager
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object MemoryTextEmbedder {
    private const val TAG = "MemoryTextEmbedder"
    // 実行時に決まる場合があるため定数から可変へ変更。ONNX モデルから取得可能なら上書きする。
    var DIMENSION = 1024

    private var initialized = false
    private var useOnnx = false
    private var onnxModelPath: String? = null
    private var tokenizerInstance: Any? = null
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputIdsName: String = "input_ids"
    private var attentionMaskName: String? = "attention_mask"

    private val embeddingDirName = "embeddings"
    private val onnxCandidates = listOf("model_quantized_arm64.onnx", "embedding.onnx")
    private val tokenizerCandidates = listOf("tokenizer.json", "tokenizer.model")

    fun initialize(context: Context): Boolean {
        if (initialized) return useOnnx
        initialized = true

        try {
            val embeddingDir = File(context.filesDir, embeddingDirName)
            if (!embeddingDir.exists()) {
                Log.w(TAG, "Embedding directory does not exist: ${embeddingDir.absolutePath}")
                return false
            }

            val modelFile = onnxCandidates.map { File(embeddingDir, it) }.firstOrNull { it.exists() }
            if (modelFile == null) {
                Log.w(TAG, "No ONNX embedding model found in ${embeddingDir.absolutePath}")
                return false
            }

            val tokenizerFile = tokenizerCandidates.map { File(embeddingDir, it) }.firstOrNull { it.exists() }
            if (tokenizerFile == null) {
                Log.w(TAG, "No tokenizer file found in ${embeddingDir.absolutePath}")
                return false
            }

            val classPresent = runCatching { Class.forName("ai.onnxruntime.OrtEnvironment") }.isSuccess
            if (!classPresent) {
                Log.w(TAG, "ONNX Runtime is not available on classpath")
                return false
            }

            tokenizerInstance = createTokenizer(tokenizerFile)
            if (tokenizerInstance == null) {
                Log.w(TAG, "Tokenizer initialization failed, ONNX embedding disabled")
                return false
            }
            
            ortEnvironment = OrtEnvironment.getEnvironment()
            ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            onnxModelPath = modelFile.absolutePath
            inputIdsName = findInputName(ortSession!!, "input_ids")
            attentionMaskName = findInputName(ortSession!!, "attention_mask")
            useOnnx = true
            Log.i(TAG, "ONNX embedding initialized: model=${modelFile.absolutePath}, tokenizer=${tokenizerFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX embedding backend", e)
            cleanupOnnxResources()
            return false
        }
    }

    fun embed(text: String): FloatArray {
        if (initialized && useOnnx) {
            try {
                val tokenizer = tokenizerInstance ?: return fallbackEmbedding(text)
                val encoding = invokeTokenizerEncode(tokenizer, text)
                return runOnnxEmbedding(encoding)
            } catch (e: Exception) {
                Log.e(TAG, "ONNX embedding failed, falling back to hash embedder", e)
                return fallbackEmbedding(text)
            }
        }
        return fallbackEmbedding(text)
    }

    private fun createTokenizer(tokenizerFile: File): Any? {
        return try {
            val tokenizerClass = Class.forName("ai.djl.huggingface.tokenizers.HuggingFaceTokenizer")
            // DJL 0.36.0: HuggingFaceTokenizer.newInstance(Path path)
            val newInstanceMethod = tokenizerClass.getMethod("newInstance", java.nio.file.Path::class.java)
            val tokenizer = newInstanceMethod.invoke(null, tokenizerFile.toPath())
            Log.d(TAG, "Successfully created tokenizer using newInstance(Path)")
            tokenizer
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create tokenizer with newInstance", e)
            null
        }
    }

    private fun invokeTokenizerEncode(tokenizer: Any, text: String): Any {
        val encodeMethod = tokenizer.javaClass.getMethod("encode", String::class.java)
        return encodeMethod.invoke(tokenizer, text) as Any
    }

    private fun runOnnxEmbedding(encoding: Any): FloatArray {
        val session = ortSession ?: return fallbackEmbedding(encoding.toString())

        val ids = getIntArrayFromObject(encoding, "getIds")
        if (ids.isEmpty()) return FloatArray(DIMENSION)
        val idsLong = ids.map { it.toLong() }.toLongArray()
        val shape = longArrayOf(1, idsLong.size.toLong())

        val inputs = mutableMapOf<String, OnnxTensor>()
        val idsTensor = OnnxTensor.createTensor(ortEnvironment!!, LongBuffer.wrap(idsLong), shape)
        inputs[inputIdsName] = idsTensor

        val attentionMask = getIntArrayFromObject(encoding, "getAttentionMask")
        if (attentionMask.isNotEmpty()) {
            val maskLong = attentionMask.map { it.toLong() }.toLongArray()
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

        return resultTensor ?: fallbackEmbedding(encoding.toString())
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

    private fun getIntArrayFromObject(obj: Any, methodName: String): IntArray {
        return try {
            val method = obj.javaClass.getMethod(methodName)
            when (val raw = method.invoke(obj)) {
                is IntArray -> raw
                is LongArray -> raw.map { it.toInt() }.toIntArray()
                is Array<*> -> raw.filterIsInstance<Number>().map { it.toInt() }.toIntArray()
                else -> IntArray(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invoke $methodName on tokenizer encoding", e)
            IntArray(0)
        }
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
}
