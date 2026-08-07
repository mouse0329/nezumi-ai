package com.nezumi_ai.data.inference

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nezumi_ai.data.preset.PresetConstants
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

enum class NezumiTool(val displayName: String) {
    GET_TIME("現在時刻取得"),
    GET_BATTERY("バッテリー残量"),
    SET_ALARM("アラームセット"),
    DISMISS_ALARM("アラーム解除"),
    LIST_ALARMS("アラームリスト"),
    FLASHLIGHT("ライト"),
    START_TIMER("タイマー開始"),
    STOP_TIMER("タイマー停止"),
    LIST_TIMERS("タイマー一覧"),
    GENERATE_IMAGE("画像生成(SD)"),
    SEARCH_MEMORY("メモリ検索"),
    SAVE_MEMORY("メモリ保存"),
    // CALENDAR_DISABLED: 復活時は下2行のコメントを外し、presetIdsForTool/isEnabled/setEnabled の対応箇所も戻す
    // ADD_CALENDAR_EVENT("カレンダー追加"),
    // LIST_CALENDAR_EVENTS("カレンダー一覧"),
    WEB_SEARCH("ウェブ検索")
}

class ToolPreferences(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "tool_preferences"
        private const val KEY_PREFIX = "tool_enabled_"
        private const val KEY_INITIALIZED = "tools_initialized_v2"
        private const val KEY_ACTIVE_PRESET_TOOL_IDS = "active_preset_tool_ids"
        private const val KEY_ACTIVE_MCP_SERVER_IDS = "active_mcp_server_ids"

        /**
         * プロセス内で共有する revision カウンタ。
         *
         * ツールの ON/OFF やプリセット切替、MCP サーバー集合の変更などを一元化して
         * 「ツール構成が変わった」というイベントを LiteRT-LM などのエンジンに伝える。
         * LiteRT-LM はこの値を ConversationKey に含めることで、モデル再ロードを伴わず
         * Conversation だけを作り直し、新しい buildEnabledToolProviders() を収集する。
         */
        private val revisionCounter = AtomicInteger(0)
        private val _revisionFlow = MutableStateFlow(0)
        val revision: StateFlow<Int> = _revisionFlow.asStateFlow()
        fun currentRevision(): Int = revisionCounter.get()

        private fun bumpRevision() {
            val v = revisionCounter.incrementAndGet()
            _revisionFlow.value = v
            Log.d("ToolPreferences", "revision bumped -> $v")
        }

        fun resetToDefaults(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            bumpRevision()
        }

        fun presetIdsForTool(tool: NezumiTool): Set<String> = when (tool) {
            NezumiTool.GET_TIME -> setOf(PresetConstants.TOOL_TIME)
            NezumiTool.GET_BATTERY -> setOf(PresetConstants.TOOL_BATTERY)
            NezumiTool.SET_ALARM,
            NezumiTool.DISMISS_ALARM,
            NezumiTool.LIST_ALARMS -> setOf(PresetConstants.TOOL_ALARM)
            NezumiTool.FLASHLIGHT -> setOf(PresetConstants.TOOL_FLASHLIGHT)
            NezumiTool.START_TIMER,
            NezumiTool.STOP_TIMER,
            NezumiTool.LIST_TIMERS -> setOf(PresetConstants.TOOL_TIMER)
            NezumiTool.GENERATE_IMAGE -> setOf(PresetConstants.TOOL_IMAGE_GENERATION)
            NezumiTool.SEARCH_MEMORY -> setOf(PresetConstants.TOOL_MEMORY)
            // メモリ保存ツールは、プリセットの「メモリ」ツール（検索と共通）または
            // 専用の「メモリ保存」ツール ID で有効化される。どちらか一方で OK。
            NezumiTool.SAVE_MEMORY -> setOf(PresetConstants.TOOL_MEMORY, PresetConstants.TOOL_MEMORY_SAVE)
            // NezumiTool.ADD_CALENDAR_EVENT,
            // NezumiTool.LIST_CALENDAR_EVENTS -> setOf(PresetConstants.TOOL_CALENDAR)
            NezumiTool.WEB_SEARCH -> setOf(PresetConstants.TOOL_WEB_SEARCH)
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 初回起動時のデフォルト有効ツール（アラーム・タイマー・フラッシュは無効）
    private fun ensureInitialized() {
        synchronized(this) {
            if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
                try {
                    val editor = prefs.edit()
                    editor.putBoolean(KEY_INITIALIZED, true)
                    NezumiTool.entries.forEach { tool ->
                        editor.putBoolean(keyFor(tool), defaultEnabled(tool))
                    }
                    editor.commit()
                } catch (e: Exception) {
                    Log.e("ToolPreferences", "Failed to initialize tool preferences", e)
                }
            } else {
                // カレンダーツールが追加された場合の初期化
                val editor = prefs.edit()
                // CALENDAR_DISABLED
                // if (!prefs.contains(keyFor(NezumiTool.ADD_CALENDAR_EVENT))) {
                //     editor.putBoolean(keyFor(NezumiTool.ADD_CALENDAR_EVENT), false)
                // }
                // if (!prefs.contains(keyFor(NezumiTool.LIST_CALENDAR_EVENTS))) {
                //     editor.putBoolean(keyFor(NezumiTool.LIST_CALENDAR_EVENTS), false)
                // }
                if (!prefs.contains(keyFor(NezumiTool.WEB_SEARCH))) {
                    editor.putBoolean(keyFor(NezumiTool.WEB_SEARCH), false)
                }
                editor.apply()
            }
        }
    }

    private fun defaultEnabled(tool: NezumiTool): Boolean = tool in setOf(
        NezumiTool.GET_TIME,
        NezumiTool.GET_BATTERY
    )

    fun isEnabled(tool: NezumiTool): Boolean {
        ensureInitialized()
        // WEB_SEARCH: APIキー未設定なら常に無効
        if (tool == NezumiTool.WEB_SEARCH &&
            PreferencesHelper.getBraveSearchApiKey(context).isBlank()
        ) return false
        val presetToolIds = getActivePresetToolIds()
        if (presetToolIds != null) {
            return presetIdsForTool(tool).any { it in presetToolIds }
        }
        return prefs.getBoolean(keyFor(tool), defaultEnabled(tool))
    }

    fun setEnabled(tool: NezumiTool, enabled: Boolean) {
        val before = prefs.getBoolean(keyFor(tool), defaultEnabled(tool))
        prefs.edit().putBoolean(keyFor(tool), enabled).apply()
        if (before != enabled) bumpRevision()
    }

    fun getEnabledTools(): Set<NezumiTool> {
        return NezumiTool.entries.filterTo(linkedSetOf()) { isEnabled(it) }
    }

    fun setActivePresetToolIds(toolIdsJson: String) {
        val before = prefs.getString(KEY_ACTIVE_PRESET_TOOL_IDS, null)
        prefs.edit().putString(KEY_ACTIVE_PRESET_TOOL_IDS, toolIdsJson).apply()
        if (before != toolIdsJson) bumpRevision()
    }

    fun clearActivePresetToolIds() {
        val had = prefs.contains(KEY_ACTIVE_PRESET_TOOL_IDS)
        prefs.edit().remove(KEY_ACTIVE_PRESET_TOOL_IDS).apply()
        if (had) bumpRevision()
    }

    fun setActiveMcpServerIds(serverIds: Set<String>) {
        val arr = JSONArray()
        serverIds.forEach { arr.put(it) }
        val next = arr.toString()
        val before = prefs.getString(KEY_ACTIVE_MCP_SERVER_IDS, null)
        prefs.edit().putString(KEY_ACTIVE_MCP_SERVER_IDS, next).apply()
        if (before != next) bumpRevision()
    }

    fun getActiveMcpServerIds(): Set<String> {
        val raw = prefs.getString(KEY_ACTIVE_MCP_SERVER_IDS, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) {
                    val id = array.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
        }.getOrElse {
            Log.e("ToolPreferences", "Failed to parse active MCP server ids", it)
            emptySet()
        }
    }

    private fun getActivePresetToolIds(): Set<String>? {
        val raw = prefs.getString(KEY_ACTIVE_PRESET_TOOL_IDS, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) {
                    val id = array.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
        }.getOrElse {
            Log.e("ToolPreferences", "Failed to parse active preset tools", it)
            emptySet()
        }
    }

    private fun keyFor(tool: NezumiTool): String = KEY_PREFIX + tool.name
}
