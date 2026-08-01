package com.nezumi_ai.data.mcp

import android.content.Context
import android.util.Log
import com.nezumi_ai.data.inference.ToolPreferences
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
 * - [ensureFresh] は推論直前に呼ばれ、キャッシュが空／失効している場合のみ取り直す。
 * - [callTool] は修飾名 (mcp__<serverPrefix>__<toolName>) 経由でディスパッチする。
 * - [fingerprint] は「現在のキャッシュ状態を一意に表す文字列」で、
 *   LiteRT-LM の ConversationKey に含めて、ツール構成が変わったら
 *   モデル再ロード無しで Conversation だけを作り直すために使う。
 *
 * Bug fix: 以前は「プリセット選択時の非同期 refresh が完了していること」が
 * ツール公開の前提になっていたため、アプリ起動直後の初回推論では
 * cachedTools が空 -> mcp_call スキーマ未登録 -> MCP ツールが一切認識されない、
 * という状態が発生していた。[ensureFresh] と [activeServerIds] のプリファレンス
 * フォールバックでこれを解消する。
 */
class McpToolRegistry private constructor(private val context: Context) {
    private val mutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedTools: List<McpToolDescriptor> = emptyList()

    @Volatile
    private var activeServerIds: Set<String> = emptySet()

    @Volatile
    private var lastRefreshAtMs: Long = 0L

    @Volatile
    private var lastRefreshedIds: Set<String> = emptySet()

    /** キャッシュが更新されるたびに増える版番号。UI と推論エンジンの両方が購読する。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        // 通知購読からのコールバックを受け取り、必要なら再取得する
        McpNotificationSubscriber.get().onToolListChanged = { serverId ->
            if (serverId in activeServerIds()) {
                bgScope.launch { refresh(activeServerIds(), force = true) }
            }
        }
    }

    fun currentTools(): List<McpToolDescriptor> = cachedTools

    fun findTool(qualifiedName: String): McpToolDescriptor? =
        cachedTools.firstOrNull { it.qualifiedName == qualifiedName }

    /**
     * 現在アクティブな MCP サーバー ID。
     * メモリ上に無い場合（プロセス再生成直後など）は ToolPreferences から復元する。
     */
    fun activeServerIds(): Set<String> {
        val inMemory = activeServerIds
        if (inMemory.isNotEmpty()) return inMemory
        return runCatching { ToolPreferences(context).getActiveMcpServerIds() }.getOrDefault(emptySet())
    }

    /** MCP サーバーが 1 つ以上プリセットに紐付いているか（ツール一覧の取得可否とは独立）。 */
    fun hasActiveServers(): Boolean = activeServerIds().isNotEmpty()

    /**
     * 現在のツールセットの指紋。ConversationKey に含めて再作成判定に使う。
     * サーバーID集合とツール名集合の順序非依存ハッシュ。
     */
    fun fingerprint(): String {
        val tools = cachedTools
        val ids = activeServerIds()
        if (tools.isEmpty() && ids.isEmpty()) return "mcp:empty"
        val idText = ids.toSortedSet().joinToString(",")
        val names = tools.map { it.qualifiedName }.toSortedSet().joinToString(",")
        return "mcp:${idText.hashCode()}:${names.hashCode()}:${tools.size}"
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
                lastRefreshedIds = emptySet()
                lastRefreshAtMs = System.currentTimeMillis()
                McpNotificationSubscriber.get().sync(emptyList())
                return
            }
            if (sameTargets && !force && cachedTools.isNotEmpty()) {
                Log.d(TAG, "MCP registry refresh skipped: unchanged targets and non-empty cache")
                return
            }
            refreshLocked(serverIds)
        }
    }

    /**
     * 推論直前などに呼ぶ軽量リフレッシュ。
     * - サーバー ID が変わった
     * - キャッシュが空
     * - 前回取得から [CACHE_TTL_MS] 以上経過
     * のいずれかでのみ実際に tools/list を叩く。
     */
    suspend fun ensureFresh(force: Boolean = false) {
        val ids = activeServerIds()
        if (ids.isEmpty()) {
            if (cachedTools.isNotEmpty()) {
                mutex.withLock {
                    cachedTools = emptyList()
                    lastRefreshedIds = emptySet()
                    bumpRevision()
                }
            }
            return
        }
        if (!isStale(ids, force)) return
        mutex.withLock {
            // ロック取得中に別コルーチンが更新した可能性を再チェック
            if (!isStale(ids, force)) return@withLock
            activeServerIds = ids
            refreshLocked(ids)
        }
    }

    private fun isStale(ids: Set<String>, force: Boolean): Boolean =
        force ||
            cachedTools.isEmpty() ||
            lastRefreshedIds != ids ||
            (System.currentTimeMillis() - lastRefreshAtMs) > CACHE_TTL_MS

    private suspend fun refreshLocked(serverIds: Set<String>) {
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
        // 取得に全滅した場合、直前のキャッシュを消さない（一時的なネットワーク断で
        // ツールが「消える」ことを防ぐ）。サーバー構成自体が変わった場合は差し替える。
        if (collected.isEmpty() && cachedTools.isNotEmpty() && lastRefreshedIds == serverIds) {
            Log.w(TAG, "MCP tools/list returned nothing; keeping $prevSize cached tool(s)")
        } else {
            cachedTools = collected
        }
        lastRefreshedIds = serverIds
        lastRefreshAtMs = System.currentTimeMillis()
        // SSE 通知購読も対象サーバー集合に合わせて更新
        McpNotificationSubscriber.get().sync(configs)
        Log.d(
            TAG,
            "MCP tool registry refreshed: ${cachedTools.size} tools from ${configs.size} server(s) (prev=$prevSize)"
        )
        bumpRevision()
    }

    /** 設定変更後などに、現在のアクティブサーバー集合をそのまま force refresh する。 */
    fun refreshActiveAsync() {
        bgScope.launch { refresh(activeServerIds(), force = true) }
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /** 修飾名で MCP ツールを呼び出す。ローカルツール名は受け付けない。 */
    suspend fun callQualified(qualifiedName: String, arguments: Map<String, Any?>): CallResult {
        // 未キャッシュのまま LLM がツール名を投げてきた場合に備え、1 度だけ取り直す。
        if (findTool(qualifiedName) == null) {
            runCatching { ensureFresh(force = true) }
        }
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
        private const val CACHE_TTL_MS = 5 * 60_000L

        @Volatile
        private var instance: McpToolRegistry? = null

        fun get(context: Context): McpToolRegistry =
            instance ?: synchronized(this) {
                instance ?: McpToolRegistry(context.applicationContext).also { instance = it }
            }
    }
}
