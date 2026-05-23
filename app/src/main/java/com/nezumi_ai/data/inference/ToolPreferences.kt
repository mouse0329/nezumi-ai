package com.nezumi_ai.data.inference

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nezumi_ai.data.preset.PresetConstants
import org.json.JSONArray

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
    ADD_CALENDAR_EVENT("カレンダー追加"),
    LIST_CALENDAR_EVENTS("カレンダー一覧")
}

class ToolPreferences(context: Context) {
    companion object {
        private const val PREFS_NAME = "tool_preferences"
        private const val KEY_PREFIX = "tool_enabled_"
        private const val KEY_INITIALIZED = "tools_initialized_v2"
        private const val KEY_ACTIVE_PRESET_TOOL_IDS = "active_preset_tool_ids"

        fun resetToDefaults(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
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
            NezumiTool.ADD_CALENDAR_EVENT,
            NezumiTool.LIST_CALENDAR_EVENTS -> setOf(PresetConstants.TOOL_CALENDAR)
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
                if (!prefs.contains(keyFor(NezumiTool.ADD_CALENDAR_EVENT))) {
                    editor.putBoolean(keyFor(NezumiTool.ADD_CALENDAR_EVENT), false)
                }
                if (!prefs.contains(keyFor(NezumiTool.LIST_CALENDAR_EVENTS))) {
                    editor.putBoolean(keyFor(NezumiTool.LIST_CALENDAR_EVENTS), false)
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
        val presetToolIds = getActivePresetToolIds()
        if (presetToolIds != null) {
            return presetIdsForTool(tool).any { it in presetToolIds }
        }
        return prefs.getBoolean(keyFor(tool), defaultEnabled(tool))
    }

    fun setEnabled(tool: NezumiTool, enabled: Boolean) {
        prefs.edit().putBoolean(keyFor(tool), enabled).apply()
    }

    fun getEnabledTools(): Set<NezumiTool> {
        return NezumiTool.entries.filterTo(linkedSetOf()) { isEnabled(it) }
    }

    fun setActivePresetToolIds(toolIdsJson: String) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_TOOL_IDS, toolIdsJson).apply()
    }

    fun clearActivePresetToolIds() {
        prefs.edit().remove(KEY_ACTIVE_PRESET_TOOL_IDS).apply()
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
