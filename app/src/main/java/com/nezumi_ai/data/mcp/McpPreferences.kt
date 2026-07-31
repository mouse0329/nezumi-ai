package com.nezumi_ai.data.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP サーバー設定を SharedPreferences で永続化する。
 *
 * DB を使わないのは、プリセットとの参照関係（プリセットは MCP サーバー ID の集合を持つ）が
 * 単純で、設定件数もごく少数（数個〜数十個）想定のため。
 */
class McpPreferences private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow<List<McpServerConfig>>(loadFromPrefs())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    fun getServers(): List<McpServerConfig> = _servers.value

    fun getServer(id: String): McpServerConfig? =
        _servers.value.firstOrNull { it.id == id }

    fun upsert(config: McpServerConfig) {
        val next = _servers.value.toMutableList()
        val idx = next.indexOfFirst { it.id == config.id }
        val stamped = config.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) next[idx] = stamped else next.add(stamped)
        _servers.value = next
        persist(next)
    }

    fun remove(id: String) {
        val next = _servers.value.filterNot { it.id == id }
        _servers.value = next
        persist(next)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val cur = getServer(id) ?: return
        upsert(cur.copy(enabled = enabled))
    }

    private fun persist(list: List<McpServerConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun loadFromPrefs(): List<McpServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(McpServerConfig.fromJson(obj))
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to load MCP server configs", it)
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "McpPreferences"
        private const val PREFS_NAME = "mcp_preferences"
        private const val KEY_SERVERS = "mcp_servers"

        @Volatile
        private var instance: McpPreferences? = null

        fun get(context: Context): McpPreferences =
            instance ?: synchronized(this) {
                instance ?: McpPreferences(context.applicationContext).also { instance = it }
            }

        /** プリセット保存文字列（JSON配列） ↔ Set<String> の相互変換ヘルパ。 */
        fun encodeServerIds(ids: Collection<String>): String {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            return arr.toString()
        }

        fun decodeServerIds(json: String?): Set<String> {
            if (json.isNullOrBlank()) return emptySet()
            return runCatching {
                val arr = JSONArray(json)
                buildSet {
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }.getOrDefault(emptySet())
        }

        /** プリセット追加情報 (JSON) の "mcpServerIds" フィールドから MCP サーバー ID 集合を取り出す。 */
        fun extractFromPresetExtras(extrasJson: String?): Set<String> {
            if (extrasJson.isNullOrBlank()) return emptySet()
            return runCatching {
                val obj = JSONObject(extrasJson)
                val arr = obj.optJSONArray("mcpServerIds") ?: return emptySet()
                buildSet {
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }.getOrDefault(emptySet())
        }
    }
}
