package com.nezumi_ai.data.mcp

import com.nezumi_ai.data.database.currentTimeMillis
import com.nezumi_ai.data.database.randomUuidString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MCP (Model Context Protocol) サーバーの接続設定。
 *
 * transport は Streamable HTTP / SSE のみをサポートする。
 * stdio はプロセス起動の制限があるためサポート外。
 */
@Serializable
enum class McpTransport(val id: String, val label: String) {
    @SerialName("streamable_http")
    STREAMABLE_HTTP("streamable_http", "Streamable HTTP"),

    @SerialName("sse")
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
@Serializable
data class McpServerConfig(
    val id: String = randomUuidString(),
    val name: String,
    val transport: McpTransport = McpTransport.STREAMABLE_HTTP,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val autoApprove: Set<String> = emptySet(),
    val createdAt: Long = currentTimeMillis(),
    val updatedAt: Long = currentTimeMillis()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun listToJson(configs: List<McpServerConfig>): String =
            runCatching { json.encodeToString(configs) }.getOrDefault("[]")

        fun listFromJson(raw: String?): List<McpServerConfig> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<McpServerConfig>>(raw) }
                .getOrDefault(emptyList())
        }
    }
}

/**
 * MCP サーバーが公開するツール定義（tools/list のレスポンス相当）。
 */
@Serializable
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
