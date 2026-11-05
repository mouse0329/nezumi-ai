package com.nezumi_ai.data.mcp

import android.util.Log
import com.nezumi_ai.data.inference.cloud.CloudHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP サーバーから `notifications/tools/list_changed` を SSE で購読し、
 * 到着したら [onToolListChanged] を呼んで [McpToolRegistry] を再取得する。
 *
 * - 有効かつ SSE transport のサーバーのみが対象。
 * - 再接続は 5 秒 → 10 秒 → 20 秒 …と指数バックオフ（上限 60 秒）。
 * - 常時接続が難しいネットワーク上では通知が届かないので、
 *   `McpToolRegistry.refresh()` の明示呼び出しがフォールバックとして残る。
 */
class McpNotificationSubscriber private constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    // 通知を受け取ったときに呼ばれる。McpToolRegistry からセットされる。
    @Volatile
    var onToolListChanged: ((serverId: String) -> Unit)? = null

    /** 対象サーバー ID の集合を渡すと、必要なものだけ購読し、外れたものは解除する。 */
    fun sync(configs: List<McpServerConfig>) {
        val targets = configs.filter { it.enabled && it.url.isNotBlank() }
        val targetIds = targets.map { it.id }.toSet()

        // 不要になった購読を解除
        for (id in jobs.keys.toList()) {
            if (id !in targetIds) {
                jobs.remove(id)?.cancel()
                Log.d(TAG, "Unsubscribed from server $id")
            }
        }
        // 新規購読
        for (cfg in targets) {
            if (jobs.containsKey(cfg.id)) continue
            jobs[cfg.id] = scope.launch { subscribeLoop(cfg) }
        }
    }

    fun stopAll() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
    }

    private suspend fun subscribeLoop(cfg: McpServerConfig) {
        var backoffMs = 5_000L
        while (scope.isActive) {
            try {
                val ok = openStream(cfg)
                if (ok) backoffMs = 5_000L
            } catch (t: Throwable) {
                Log.w(TAG, "SSE subscribe error for ${cfg.name}: ${t.message}")
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun openStream(cfg: McpServerConfig): Boolean = withContext(Dispatchers.IO) {
        if (!runCatching { PrivateIpValidator.isCleartextAllowed(cfg.url) }.getOrDefault(true)) {
            return@withContext false
        }
        val resp = CloudHttpClient.instance.get(cfg.url) {
            header("Accept", "text/event-stream")
            header("MCP-Protocol-Version", "2025-03-26")
            cfg.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) }
        }
        if (!resp.status.isSuccess()) {
                Log.w(TAG, "SSE stream returned ${resp.status.value} for ${cfg.name}")
                return@withContext false
        }
        val channel = resp.bodyAsChannel()
        val eventBuf = StringBuilder()
        while (scope.isActive) {
            val line = channel.readUTF8Line() ?: break
            when {
                line.startsWith("data:") -> {
                    eventBuf.append(line.removePrefix("data:").trimStart())
                    eventBuf.append('\n')
                }
                line.isEmpty() && eventBuf.isNotEmpty() -> {
                    handleEvent(cfg, eventBuf.toString().trim())
                    eventBuf.setLength(0)
                }
            }
        }
        true
    }

    private fun handleEvent(cfg: McpServerConfig, jsonText: String) {
        if (jsonText.isBlank() || !jsonText.startsWith("{")) return
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
        val method = obj.optString("method")
        if (method == "notifications/tools/list_changed" ||
            method == "notifications/resources/list_changed" ||
            method == "notifications/prompts/list_changed"
        ) {
            Log.d(TAG, "MCP notification '$method' from ${cfg.name}, triggering refresh")
            onToolListChanged?.invoke(cfg.id)
        }
    }

    companion object {
        private const val TAG = "McpNotifSub"

        @Volatile
        private var instance: McpNotificationSubscriber? = null

        fun get(): McpNotificationSubscriber =
            instance ?: synchronized(this) {
                instance ?: McpNotificationSubscriber().also { instance = it }
            }
    }
}
