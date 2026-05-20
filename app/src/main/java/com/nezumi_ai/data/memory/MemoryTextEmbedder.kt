package com.nezumi_ai.data.memory

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.sqrt

object MemoryTextEmbedder {
    private const val TAG = "MemoryTextEmbedder"
    const val DIMENSION = 1024

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

    private fun createTokenizer(tokenizerFile: File): Any {
        val tokenizerClass = Class.forName("ai.djl.huggingface.tokenizers.HuggingFaceTokenizer")
        val json = tokenizerFile.readText(Charsets.UTF_8)
        val createMethod = tokenizerClass.getMethod("createTokenizerFromString", String::class.java)
        val handle = (createMethod.invoke(null, json) as Number).toLong()
        val constructor = try {
            tokenizerClass.getDeclaredConstructor(java.lang.Long.TYPE)
        } catch (_: NoSuchMethodException) {
            tokenizerClass.getDeclaredConstructor(java.lang.Long::class.java)
        }
        constructor.isAccessible = true
        return constructor.newInstance(handle)
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
        val allocator = getDefaultAllocator(ortEnvironment!!)
        val idsTensor = createTensorWithReflection(ortEnvironment!!, allocator, LongBuffer.wrap(idsLong), shape)
        inputs[inputIdsName] = idsTensor

        val attentionMask = getIntArrayFromObject(encoding, "getAttentionMask")
        if (attentionMask.isNotEmpty()) {
            val mask = IntBuffer.wrap(attentionMask)
            inputs[attentionMaskName ?: "attention_mask"] = createTensorWithReflection(
                ortEnvironment!!,
                allocator,
                mask,
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
            is FloatArray -> value
            is Array<*> -> {
                val first = value.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is DoubleArray -> first.map { it.toFloat() }.toFloatArray()
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun findInputName(session: OrtSession, preferredName: String): String {
        return session.inputInfo.keys.firstOrNull { it == preferredName } ?: session.inputInfo.keys.firstOrNull() ?: preferredName
    }

    private fun getDefaultAllocator(environment: OrtEnvironment): Any {
        val method = environment.javaClass.getDeclaredMethod("getDefaultAllocator")
        method.isAccessible = true
        return method.invoke(environment) as Any
    }

    private fun createTensorWithReflection(
        environment: OrtEnvironment,
        allocator: Any,
        buffer: Any,
        shape: LongArray
    ): OnnxTensor {
        val allocatorClass = Class.forName("ai.onnxruntime.OrtAllocator")
        val method = OnnxTensor::class.java.getDeclaredMethod(
            "createTensor",
            OrtEnvironment::class.java,
            allocatorClass,
            buffer.javaClass,
            LongArray::class.java
        )
        method.isAccessible = true
        return method.invoke(null, environment, allocator, buffer, shape) as OnnxTensor
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
}
