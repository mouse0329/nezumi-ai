package com.nezumi_ai.data.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * プリセットに紐付いた MCP サーバー群からツール定義を集約して保持する。
 *
 * - [refresh] はプリセット切替時 / 設定変更時 / SSE 通知受信時に呼び出され、
 *   有効サーバーの tools/list を取得してキャッシュする。
 * - [callTool] は修飾名 (mcp__<serverPrefix>__<toolName>) 経由でディスパッチする。
 * - [fingerprint] は「現在のキャッシュ状態を一意に表す文字列」で、
 *   LiteRT-LM の ConversationKey に含めて、ツール構成が変わったら
 *   モデル再ロード無しで Conversation だけを作り直すために使う。
 */
class McpToolRegistry private constructor(private val context: Context) {
    private val mutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedTools: List<McpToolDescriptor> = emptyList()

    @Volatile
    private var activeServerIds: Set<String> = emptySet()

    /** キャッシュが更新されるたびに増える版番号。UI と推論エンジンの両方が購読する。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        // 通知購読からのコールバックを受け取り、必要なら再取得する
        McpNotificationSubscriber.get().onToolListChanged = { serverId ->
            if (serverId in activeServerIds) {
                bgScope.launch { refresh(activeServerIds, force = true) }
            }
        }
    }

    fun currentTools(): List<McpToolDescriptor> = cachedTools

    fun findTool(qualifiedName: String): McpToolDescriptor? =
        cachedTools.firstOrNull { it.qualifiedName == qualifiedName }

    /**
     * 現在のツールセットの指紋。ConversationKey に含めて再作成判定に使う。
     * サーバーID集合とツール名集合の順序非依存ハッシュ。
     */
    fun fingerprint(): String {
        val tools = cachedTools
        if (tools.isEmpty() && activeServerIds.isEmpty()) return "mcp:empty"
        val ids = activeServerIds.toSortedSet().joinToString(",")
        val names = tools.map { it.qualifiedName }.toSortedSet().joinToString(",")
        return "mcp:${ids.hashCode()}:${names.hashCode()}:${tools.size}"
    }

    /** プリセットに紐付く MCP サーバー ID 集合を反映してツール一覧を再構築する。 */
    suspend fun refresh(serverIds: Set<String>, force: Boolean = false) {
        mutex.withLock {
            val sameTargets = serverIds == activeServerIds
            activeServerIds = serverIds
            if (serverIds.isEmpty()) {
                if (cachedTools.isNotEmpty() || force) {
                    cachedTools = emptyList()
                    bumpRevision()
                }
                McpNotificationSubscriber.get().sync(emptyList())
                return
            }
            if (sameTargets && !force && cachedTools.isNotEmpty()) {
                Log.d(TAG, "MCP registry refresh skipped: unchanged targets and non-empty cache")
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
            val prevSize = cachedTools.size
            cachedTools = collected
            // SSE 通知購読も対象サーバー集合に合わせて更新
            McpNotificationSubscriber.get().sync(configs)
            Log.d(TAG, "MCP tool registry refreshed: ${collected.size} tools from ${configs.size} server(s) (prev=$prevSize)")
            bumpRevision()
        }
    }

    /** 設定変更後などに、現在のアクティブサーバー集合をそのまま force refresh する。 */
    fun refreshActiveAsync() {
        bgScope.launch { refresh(activeServerIds, force = true) }
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
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
