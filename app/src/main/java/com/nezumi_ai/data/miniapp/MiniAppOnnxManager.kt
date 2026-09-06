package com.nezumi_ai.data.miniapp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Base64
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
 * - メモリ上限（§29）: テンソル確保は合計 2.5GiB まで。超過は MEMORY_PRESSURE。
 * - モデルファイルは App Data 内からのみ読み込み可能（§6）。
 *   グローバルモデルストレージへの直接アクセスは廃止。
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

    /**
     * §27 open: ONNX セッションを開く。
     *
     * @param backend 実行プロバイダ。"nnapi" | "cpu" | null（省略時は "cpu" 相当のデフォルト）。
     *   非対応端末や指定バックエンドの初期化に失敗した場合は例外を投げず CPU にフォールバックする
     *   （Mini App 側の分岐を減らし、開発者が握り潰す必要をなくすため）。
     */
    fun open(modelPath: String, backend: String? = null): String {
        val file = resolveModelPath(modelPath)
        if (!file.exists()) throw MiniAppException("FILE_NOT_FOUND", "ONNX モデルが見つかりません: $modelPath")
        val id = UUID.randomUUID().toString()
        val options = buildSessionOptions(backend)
        return try {
            sessions[id] = SessionEntry(options.use { env.createSession(file.absolutePath, it) }, file.absolutePath)
            id
        } catch (e: Exception) {
            throw MiniAppException("MODEL_LOAD_FAILED", "ONNX セッションの作成に失敗しました: ${e.message}", cause = e)
        }
    }

    /** 指定バックエンドで SessionOptions を構築する。非対応 backend 文字列は無視して CPU 既定にフォールバック。 */
    private fun buildSessionOptions(backend: String?): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        when (backend?.lowercase()) {
            "nnapi" -> {
                try {
                    options.addNnapi()
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI 実行プロバイダの有効化に失敗。CPU にフォールバックします: ${e.message}")
                }
            }
            "cpu", null -> {
                // 明示的な追加は不要（CPU が既定の実行プロバイダ）
            }
            else -> {
                Log.w(TAG, "未知の backend 指定 '$backend' を無視し、CPU で実行します")
            }
        }
        return options
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

    /** §27 createTensor: float32/int64/int32 を little-endian で受け取る。 */
    fun createTensor(
        sessionId: String,
        shape: List<Long>,
        dataBase64: String,
        dtype: String = "float32"
    ): String {
        sessions[sessionId] ?: throw MiniAppException("INVALID_STATE", "セッションがありません: $sessionId")
        if (shape.any { it < 0L }) {
            throw MiniAppException("INVALID_INPUT", "shape には負の値を指定できません")
        }
        val normalizedDtype = dtype.lowercase()
        val elementBytes = when (normalizedDtype) {
            "float32", "int32" -> 4L
            "int64" -> 8L
            else -> throw MiniAppException("INVALID_INPUT", "未対応の dtype です: $dtype")
        }
        val bytes = android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
        val elementCount = shape.fold(1L) { product, dimension ->
            if (dimension != 0L && product > Long.MAX_VALUE / dimension) {
                throw MiniAppException("INVALID_INPUT", "shape の要素数が大きすぎます")
            }
            product * dimension
        }
        val expectedBytes = elementCount * elementBytes
        if (expectedBytes != bytes.size.toLong()) {
            throw MiniAppException(
                "INVALID_INPUT",
                "data のサイズが shape/dtype と一致しません: expected=$expectedBytes actual=${bytes.size}"
            )
        }
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
            val tensor = when (normalizedDtype) {
                "float32" -> OnnxTensor.createTensor(env, buf.asFloatBuffer(), shape.toLongArray())
                "int32" -> OnnxTensor.createTensor(env, buf.asIntBuffer(), shape.toLongArray())
                "int64" -> OnnxTensor.createTensor(env, buf.asLongBuffer(), shape.toLongArray())
                else -> error("validated dtype")
            }
            tensors[id] = TensorEntry(tensor, bytes.size.toLong(), normalizedDtype, shape.toLongArray())
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
            s.session.run(tensorMap).use { results ->
                val out = mutableMapOf<String, String>()
                val outputNames = s.session.outputNames.toList()
                for (i in 0 until results.size()) {
                    val r = results.get(i)
                    val info = r.info
                    if (info is ai.onnxruntime.TensorInfo) {
                        val value = r.value
                        val outputName = outputNames.getOrNull(i) ?: "output_$i"
                        out[outputName] = FLOAT32_BASE64_PREFIX + encodeAsFloat32Base64(value)
                    }
                }
                return out
            }
        } catch (e: MiniAppException) {
            throw e
        } catch (e: Exception) {
            throw MiniAppException("INTERNAL_ERROR", "ONNX 推論に失敗しました: ${e.message}", cause = e)
        }
    }

    private fun encodeAsFloat32Base64(value: Any): String {
        val count = countNumericElements(value)
        if (count > Int.MAX_VALUE / Float.SIZE_BYTES) {
            throw MiniAppException("MEMORY_PRESSURE", "ONNX 出力が大きすぎます: $count elements")
        }
        val bytes = ByteBuffer.allocate(count * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        appendNumericElements(value, bytes)
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }

    private fun countNumericElements(value: Any?): Int {
        if (value == null) throw MiniAppException("INTERNAL_ERROR", "ONNX 出力に null 要素があります")
        if (value is Number) return 1
        val length = java.lang.reflect.Array.getLength(value)
        var count = 0L
        for (index in 0 until length) {
            count += countNumericElements(java.lang.reflect.Array.get(value, index))
            if (count > Int.MAX_VALUE) throw MiniAppException("MEMORY_PRESSURE", "ONNX 出力が大きすぎます")
        }
        return count.toInt()
    }

    private fun appendNumericElements(value: Any?, buffer: ByteBuffer) {
        if (value == null) throw MiniAppException("INTERNAL_ERROR", "ONNX 出力に null 要素があります")
        if (value is Number) {
            buffer.putFloat(value.toFloat())
            return
        }
        val length = java.lang.reflect.Array.getLength(value)
        for (index in 0 until length) appendNumericElements(java.lang.reflect.Array.get(value, index), buffer)
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

    /**
     * §6: App Data 内からのみ読み込み。
     * `models/` プレフィックスによるグローバルモデルストレージへのアクセスは廃止し、
     * モデルファイルは SDK のダウンロード API で App Data 内に取得してから開く。
     */
    private fun resolveModelPath(modelPath: String): File {
        val cleaned = modelPath.removePrefix("/")
        if (cleaned.startsWith("models/") || cleaned == "models") {
            throw MiniAppException(
                "FILE_ACCESS_DENIED",
                "グローバルモデルストレージへのアクセスは廃止されました。nezumi.download で App Data 内にダウンロードしてください"
            )
        }
        val root = dataRoot.canonicalFile
        val target = File(root, cleaned).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw MiniAppException("FILE_ACCESS_DENIED", "App Data 境界外へのアクセスは禁止されています")
        }
        return target
    }

    companion object {
        private const val TAG = "MiniAppOnnxManager"
        private const val MAX_TENSOR_BYTES = 2_560L * 1024 * 1024
        private const val FLOAT32_BASE64_PREFIX = "__f32b64__:"
    }
}
