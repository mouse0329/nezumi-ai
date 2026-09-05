package com.nezumi_ai.data.miniapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 仕様 v1.1 §27 ONNX Low-Level API / Tensor。
 *
 * - セッション・テンソルは runtimeId に紐付き、runtime 終了で全解放（§18 と同じ寿命管理）。
 * - メモリ上限（§29）: テンソル確保は合計 512MB まで。超過は MEMORY_PRESSURE。
 * - モデルファイルは App Data または Global モデルストレージからのみ読み込み可能（§6/§20）。
 */
class MiniAppOnnxManager(
    private val context: Context,
    private val runtimeId: String,
    private val dataRoot: File
) {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private data class SessionEntry(val session: OrtSession, val modelPath: String)
    private data class TensorEntry(val tensor: OnnxTensor, val bytes: Long, val dtype: String, val shape: LongArray)

    private val sessions = ConcurrentHashMap<String, SessionEntry>()
    private val tensors = ConcurrentHashMap<String, TensorEntry>()
    private val totalTensorBytes = java.util.concurrent.atomic.AtomicLong(0)

    /** §27 open: ONNX セッションを開く。 */
    fun open(modelPath: String): String {
        val file = resolveModelPath(modelPath)
        if (!file.exists()) throw MiniAppException("FILE_NOT_FOUND", "ONNX モデルが見つかりません: $modelPath")
        val id = UUID.randomUUID().toString()
        return try {
            sessions[id] = SessionEntry(env.createSession(file.absolutePath), file.absolutePath)
            id
        } catch (e: Exception) {
            throw MiniAppException("MODEL_LOAD_FAILED", "ONNX セッションの作成に失敗しました: ${e.message}", cause = e)
        }
    }

    fun getInputs(sessionId: String): List<Map<String, Any>> = tensorInfoList(sessionId, inputs = true)
    fun getOutputs(sessionId: String): List<Map<String, Any>> = tensorInfoList(sessionId, inputs = false)

    private fun tensorInfoList(sessionId: String, inputs: Boolean): List<Map<String, Any>> {
        val s = sessions[sessionId] ?: throw MiniAppException("INVALID_STATE", "セッションがありません: $sessionId")
        val names = if (inputs) s.session.inputNames else s.session.outputNames
        val infoMap = if (inputs) s.session.inputInfo else s.session.outputInfo
        return names.map { name ->
            val ti = infoMap[name]?.info
            val shape = (ti as? ai.onnxruntime.TensorInfo)?.shape?.toList() ?: emptyList()
            mapOf(
                "name" to name,
                "shape" to shape,
                "dtype" to ((ti as? ai.onnxruntime.TensorInfo)?.type?.name ?: "unknown")
            )
        }
    }

    /** §27 createTensor: float32 専用（v1.1 実装範囲）。 */
    fun createTensor(sessionId: String, shape: List<Long>, dataBase64: String): String {
        sessions[sessionId] ?: throw MiniAppException("INVALID_STATE", "セッションがありません: $sessionId")
        val bytes = android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
        val newTotal = totalTensorBytes.addAndGet(bytes.size.toLong())
        if (newTotal > MAX_TENSOR_BYTES) {
            totalTensorBytes.addAndGet(-bytes.size.toLong())
            throw MiniAppException(
                "MEMORY_PRESSURE",
                "ONNX テンソル確保が上限（${MAX_TENSOR_BYTES / 1024 / 1024}MB）を超えます"
            )
        }
        val id = UUID.randomUUID().toString()
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val fb = buf.asFloatBuffer()
            val tensor = OnnxTensor.createTensor(env, fb, shape.toLongArray())
            tensors[id] = TensorEntry(tensor, bytes.size.toLong(), "float32", shape.toLongArray())
            id
        } catch (e: Exception) {
            totalTensorBytes.addAndGet(-bytes.size.toLong())
            throw MiniAppException("INVALID_INPUT", "テンソル作成に失敗しました: ${e.message}", cause = e)
        }
    }

    /** §27 run。 */
    fun run(sessionId: String, inputs: Map<String, String>): Map<String, String> {
        val s = sessions[sessionId] ?: throw MiniAppException("INVALID_STATE", "セッションがありません: $sessionId")
        val tensorMap = mutableMapOf<String, OnnxTensor>()
        try {
            for ((name, tensorId) in inputs) {
                val t = tensors[tensorId]
                    ?: throw MiniAppException("INVALID_INPUT", "テンソルがありません: $tensorId")
                tensorMap[name] = t.tensor
            }
            val results = s.session.run(tensorMap)
            val out = mutableMapOf<String, String>()
            val outputNames = s.session.outputNames.toList()
            for (i in 0 until results.size()) {
                val r = results.get(i)
                val info = r.info
                if (info is ai.onnxruntime.TensorInfo) {
                    val value = r.value
                    val outputName = outputNames.getOrNull(i) ?: "output_$i"
                    when (value) {
                        is FloatArray -> out[outputName] = value.joinToString(",")
                        is Array<*> -> out[outputName] = value.contentDeepToString()
                        else -> out[outputName] = value.toString()
                    }
                }
            }
            return out
        } catch (e: MiniAppException) {
            throw e
        } catch (e: Exception) {
            throw MiniAppException("INTERNAL_ERROR", "ONNX 推論に失敗しました: ${e.message}", cause = e)
        }
    }

    fun disposeTensor(tensorId: String) {
        tensors.remove(tensorId)?.let { t ->
            totalTensorBytes.addAndGet(-t.bytes)
            runCatching { t.tensor.close() }
        }
    }

    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)?.let { s ->
            runCatching { s.session.close() }
        }
    }

    /** runtime 終了時に全リソース解放。 */
    fun destroy() {
        sessions.keys.toList().forEach { closeSession(it) }
        tensors.keys.toList().forEach { disposeTensor(it) }
        Log.d(TAG, "ONNX resources released for runtime=$runtimeId")
    }

    /** §6/§20: App Data または Global モデルストレージからのみ読み込み。 */
    private fun resolveModelPath(modelPath: String): File {
        val cleaned = modelPath.removePrefix("/")
        return if (cleaned.startsWith("models/")) {
            val root = File(context.filesDir, "models").canonicalFile
            val target = File(root, cleaned.removePrefix("models/")).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) {
                throw MiniAppException("FILE_ACCESS_DENIED", "モデルストレージ境界外へのアクセスは禁止されています")
            }
            target
        } else {
            val root = dataRoot.canonicalFile
            val target = File(root, cleaned).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) {
                throw MiniAppException("FILE_ACCESS_DENIED", "App Data 境界外へのアクセスは禁止されています")
            }
            target
        }
    }

    companion object {
        private const val TAG = "MiniAppOnnxManager"
        private const val MAX_TENSOR_BYTES = 512L * 1024 * 1024
    }
}
