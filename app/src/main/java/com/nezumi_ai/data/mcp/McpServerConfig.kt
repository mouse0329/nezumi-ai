package com.nezumi_ai.data.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MCP (Model Context Protocol) サーバーの接続設定。
 *
 * transport は Android 上で現実的な Streamable HTTP / SSE のみをサポートする。
 * stdio はプロセス起動の制限があるためサポート外。
 */
enum class McpTransport(val id: String, val label: String) {
    STREAMABLE_HTTP("streamable_http", "Streamable HTTP"),
    SSE("sse", "SSE (Server-Sent Events)");

    companion object {
        fun fromId(id: String?): McpTransport =
            entries.firstOrNull { it.id == id } ?: STREAMABLE_HTTP
    }
}

/**
 * 単一の MCP サーバー設定。
 *
 * - [url]: エンドポイント URL。Streamable HTTP なら POST 先、SSE ならイベントストリーム URL。
 * - [headers]: 追加ヘッダ（Authorization 等）を key=value で保持する。
 * - [enabled]: このサーバーを実際に使うかどうか。
 * - [autoApprove]: 自動承認するツール名のセット。
 */
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val autoApprove: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("transport", transport.id)
        obj.put("url", url)
        val headersObj = JSONObject()
        headers.forEach { (k, v) -> headersObj.put(k, v) }
        obj.put("headers", headersObj)
        obj.put("enabled", enabled)
        val approveArr = JSONArray()
        autoApprove.forEach { approveArr.put(it) }
        obj.put("autoApprove", approveArr)
        obj.put("createdAt", createdAt)
        obj.put("updatedAt", updatedAt)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): McpServerConfig {
            val headers = mutableMapOf<String, String>()
            obj.optJSONObject("headers")?.let { h ->
                val it = h.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    headers[k] = h.optString(k, "")
                }
            }
            val autoApprove = mutableSetOf<String>()
            obj.optJSONArray("autoApprove")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotEmpty()) autoApprove.add(v)
                }
            }
            return McpServerConfig(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "MCP Server"),
                transport = McpTransport.fromId(obj.optString("transport")),
                url = obj.optString("url", ""),
                headers = headers,
                enabled = obj.optBoolean("enabled", true),
                autoApprove = autoApprove,
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * MCP サーバーが公開するツール定義（tools/list のレスポンス相当）。
 */
data class McpToolDescriptor(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String
) {
    /** サーバー横断で衝突しない一意な識別子。 */
    val qualifiedName: String get() = "mcp__${serverId.take(8)}__$name"
}
