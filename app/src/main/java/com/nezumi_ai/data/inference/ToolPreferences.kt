package com.nezumi_ai.data.inference

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

enum class NezumiTool(val displayName: String) {
    GET_TIME("現在時刻取得"),
    GET_BATTERY("バッテリー残量"),
    SET_ALARM("アラームセット"),
    DISMISS_ALARM("アラーム解除"),
    LIST_ALARMS("アラームリスト"),
    FLASHLIGHT("ライト"),
    START_TIMER("タイマー開始"),
    STOP_TIMER("タイマー停止"),
    LIST_TIMERS("タイマー一覧")
}

class ToolPreferences(context: Context) {
    companion object {
        private const val PREFS_NAME = "tool_preferences"
        private const val KEY_PREFIX = "tool_enabled_"
        private const val KEY_INITIALIZED = "tools_initialized"

        fun resetToDefaults(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 初回起動時のみすべてのツールをデフォルトで有効化（遅延初期化）
    private fun ensureInitialized() {
        synchronized(this) {
            if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
                try {
                    val editor = prefs.edit()
                    editor.putBoolean(KEY_INITIALIZED, true)
                    NezumiTool.entries.forEach { tool ->
                        editor.putBoolean(keyFor(tool), true)
                    }
                    editor.commit()
                } catch (e: Exception) {
                    Log.e("ToolPreferences", "Failed to initialize tool preferences", e)
                }
            }
        }
    }

    fun isEnabled(tool: NezumiTool): Boolean {
        ensureInitialized()
        // 初期状態で有効なツール一覧
        val defaultEnabled = tool in setOf(
            NezumiTool.GET_TIME,
            NezumiTool.GET_BATTERY,
            NezumiTool.SET_ALARM,
            NezumiTool.DISMISS_ALARM,
            NezumiTool.LIST_ALARMS,
            NezumiTool.FLASHLIGHT,
            NezumiTool.START_TIMER,
            NezumiTool.STOP_TIMER,
            NezumiTool.LIST_TIMERS
        )
        return prefs.getBoolean(keyFor(tool), defaultEnabled)
    }

    fun setEnabled(tool: NezumiTool, enabled: Boolean) {
        prefs.edit().putBoolean(keyFor(tool), enabled).apply()
    }

    fun getEnabledTools(): Set<NezumiTool> {
        return NezumiTool.entries.filterTo(linkedSetOf()) { isEnabled(it) }
    }

    private fun keyFor(tool: NezumiTool): String = KEY_PREFIX + tool.name
}
