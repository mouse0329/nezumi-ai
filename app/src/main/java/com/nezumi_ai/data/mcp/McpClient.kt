package com.nezumi_ai.data.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 クライアント。
 *
 * サポート transport:
 * - Streamable HTTP: 単一エンドポイントに POST してレスポンスを直接読む
 * - SSE: レスポンスを text/event-stream として読み、最初の JSON-RPC メッセージを取り出す
 *
 * 参考: https://modelcontextprotocol.io/specification/2025-03-26/basic/transports
 */
class McpClient(
    private val config: McpServerConfig,
    private val client: OkHttpClient = defaultClient
) {
    private val requestId = AtomicLong(1L)
    private var sessionId: String? = null
    private var initialized = false

    data class RpcResult(val ok: Boolean, val result: JSONObject?, val errorMessage: String?)

    suspend fun initialize(): RpcResult = withContext(Dispatchers.IO) {
        val params = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "nezumi-ai")
                put("version", "2.2.1")
            })
        }
        val res = rpc("initialize", params)
        if (res.ok) initialized = true
        // MCP 仕様上、initialize 後に notifications/initialized を送るべき（fire-and-forget）
        if (res.ok) runCatching { notify("notifications/initialized", JSONObject()) }
        res
    }

    suspend fun listTools(): List<McpToolDescriptor> = withContext(Dispatchers.IO) {
        if (!initialized) {
            val init = initialize()
            if (!init.ok) {
                Log.w(TAG, "initialize failed for ${config.name}: ${init.errorMessage}")
                return@withContext emptyList()
            }
        }
        val res = rpc("tools/list", JSONObject())
        if (!res.ok || res.result == null) {
            Log.w(TAG, "tools/list failed for ${config.name}: ${res.errorMessage}")
            return@withContext emptyList()
        }
        val tools = res.result.optJSONArray("tools") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                val name = t.optString("name").ifBlank { continue }
                val schema = t.optJSONObject("inputSchema") ?: JSONObject()
                add(
                    McpToolDescriptor(
                        serverId = config.id,
                        serverName = config.name,
                        name = name,
                        description = t.optString("description", ""),
                        inputSchemaJson = schema.toString()
                    )
                )
            }
        }
    }

    suspend fun callTool(name: String, arguments: JSONObject): RpcResult = withContext(Dispatchers.IO) {
        if (!initialized) {
            val init = initialize()
            if (!init.ok) return@withContext init
        }
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        rpc("tools/call", params)
    }

    /** initialize が通るかだけを見る軽量ping。UIの「接続テスト」で利用。 */
    suspend fun ping(): RpcResult = initialize()

    private fun buildRequest(bodyJson: JSONObject): Request {
        check(PrivateIpValidator.isCleartextAllowed(config.url)) {
            "Blocked request to disallowed URL (http:// to non-private host, or invalid scheme): ${config.url}"
        }
        val builder = Request.Builder()
            .url(config.url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        config.headers.forEach { (k, v) -> if (k.isNotBlank()) builder.header(k, v) }
        return builder.post(
            bodyJson.toString().toRequestBody("application/json".toMediaType())
        ).build()
    }

    private fun rpc(method: String, params: JSONObject): RpcResult {
        val id = requestId.getAndIncrement()
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return runCatching {
            client.newCall(buildRequest(body)).execute().use { resp ->
                // MCP セッション ID を追跡（Streamable HTTP 用）
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return RpcResult(false, null, "HTTP ${resp.code}: ${text.take(300)}")
                }
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val payload = if (contentType.contains("text/event-stream")) {
                    extractJsonFromSseStream(text)
                } else {
                    text
                }
                if (payload.isBlank()) {
                    return RpcResult(false, null, "Empty response body")
                }
                val obj = JSONObject(payload)
                obj.optJSONObject("error")?.let { err ->
                    return RpcResult(false, null, "RPC error ${err.optInt("code")}: ${err.optString("message")}")
                }
                RpcResult(true, obj.optJSONObject("result") ?: JSONObject(), null)
            }
        }.getOrElse { e ->
            Log.w(TAG, "rpc($method) failed", e)
            RpcResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun notify(method: String, params: JSONObject) {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        runCatching {
            client.newCall(buildRequest(body)).execute().use { it.body?.close() }
        }
    }

    /**
     * text/event-stream 応答から最初の JSON-RPC メッセージ（data: 行）を抜き出す。
     * MCP over SSE では 1 レスポンス = 1 か少数のメッセージなので、
     * 全文を受け取ってから最初の完全な JSON を返す簡易実装で足りる。
     */
    private fun extractJsonFromSseStream(text: String): String {
        val builder = StringBuilder()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("data:")) {
                builder.append(line.removePrefix("data:").trimStart())
                builder.append('\n')
            } else if (line.isEmpty() && builder.isNotEmpty()) {
                // イベント境界
                val candidate = builder.toString().trim()
                if (candidate.startsWith("{")) return candidate
                builder.clear()
            }
        }
        return builder.toString().trim()
    }

    companion object {
        private const val TAG = "McpClient"
        private const val PROTOCOL_VERSION = "2025-03-26"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** tools/call のレスポンスから、テキスト結果を平坦化して取り出すヘルパ。 */
        fun flattenToolCallResult(result: JSONObject?): String {
            if (result == null) return ""
            val content = result.optJSONArray("content") ?: return result.toString()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "text" -> sb.append(item.optString("text"))
                    "image" -> sb.append("[image ${item.optString("mimeType", "")}]")
                    "resource" -> sb.append(item.optJSONObject("resource")?.toString().orEmpty())
                    else -> sb.append(item.toString())
                }
                if (i < content.length() - 1) sb.append('\n')
            }
            val isError = result.optBoolean("isError", false)
            return if (isError) "[MCP error] $sb" else sb.toString()
        }

        fun encodeArguments(args: Map<String, Any?>): JSONObject {
            val obj = JSONObject()
            args.forEach { (k, v) -> obj.put(k, v ?: JSONObject.NULL) }
            return obj
        }
    }
}
