package com.nezumi_ai.data.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * プリセットに紐付いた MCP サーバー群からツール定義を集約して保持する。
 *
 * - [refresh] はプリセット切替時などに呼び出され、有効サーバーの tools/list を取得してキャッシュする。
 * - [callTool] は修飾名 (mcp__<serverPrefix>__<toolName>) 経由でディスパッチする。
 *
 * このクラスは軽量なメモリキャッシュに徹し、UI からのオンデマンドな tools/list 取得
 * （プリセット編集モーダルの接続テスト等）とは独立している。
 */
class McpToolRegistry private constructor(private val context: Context) {
    private val mutex = Mutex()

    @Volatile
    private var cachedTools: List<McpToolDescriptor> = emptyList()

    @Volatile
    private var activeServerIds: Set<String> = emptySet()

    fun currentTools(): List<McpToolDescriptor> = cachedTools

    fun findTool(qualifiedName: String): McpToolDescriptor? =
        cachedTools.firstOrNull { it.qualifiedName == qualifiedName }

    /** プリセットに紐付く MCP サーバー ID 集合を反映してツール一覧を再構築する。 */
    suspend fun refresh(serverIds: Set<String>) {
        mutex.withLock {
            activeServerIds = serverIds
            if (serverIds.isEmpty()) {
                cachedTools = emptyList()
                return
            }
            val configs = McpPreferences.get(context).getServers()
                .filter { it.enabled && it.id in serverIds && it.url.isNotBlank() }
            val collected = mutableListOf<McpToolDescriptor>()
            for (cfg in configs) {
                runCatching {
                    val tools = McpClient(cfg).listTools()
                    collected.addAll(tools)
                }.onFailure {
                    Log.w(TAG, "Failed to fetch tools from ${cfg.name}", it)
                }
            }
            cachedTools = collected
            Log.d(TAG, "MCP tool registry refreshed: ${collected.size} tools from ${configs.size} server(s)")
        }
    }

    /** 修飾名で MCP ツールを呼び出す。ローカルツール名は受け付けない。 */
    suspend fun callQualified(qualifiedName: String, arguments: Map<String, Any?>): CallResult {
        val descriptor = findTool(qualifiedName)
            ?: return CallResult(false, "unknown_mcp_tool:$qualifiedName", null)
        val cfg = McpPreferences.get(context).getServer(descriptor.serverId)
            ?: return CallResult(false, "mcp_server_not_found:${descriptor.serverId}", null)
        if (!cfg.enabled) {
            return CallResult(false, "mcp_server_disabled:${cfg.name}", null)
        }
        val res = McpClient(cfg).callTool(descriptor.name, McpClient.encodeArguments(arguments))
        val text = McpClient.flattenToolCallResult(res.result)
        return CallResult(
            success = res.ok,
            errorMessage = res.errorMessage,
            resultText = text,
            rawResult = res.result
        )
    }

    data class CallResult(
        val success: Boolean,
        val errorMessage: String?,
        val resultText: String?,
        val rawResult: JSONObject? = null
    )

    companion object {
        private const val TAG = "McpToolRegistry"

        @Volatile
        private var instance: McpToolRegistry? = null

        fun get(context: Context): McpToolRegistry =
            instance ?: synchronized(this) {
                instance ?: McpToolRegistry(context.applicationContext).also { instance = it }
            }
    }
}
