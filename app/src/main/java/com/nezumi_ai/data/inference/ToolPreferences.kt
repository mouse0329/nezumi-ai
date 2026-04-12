package com.nezumi_ai.data.inference

import android.content.Context
import android.content.SharedPreferences

enum class NezumiTool(val displayName: String) {
    GET_TIME("現在時刻取得"),
    GET_BATTERY("バッテリー残量"),
    SET_ALARM("アラームセット"),
    DISMISS_ALARM("アラーム解除"),
    LIST_ALARMS("アラームリスト"),
    FLASHLIGHT("ライト")
}

class ToolPreferences(context: Context) {
    companion object {
        private const val PREFS_NAME = "tool_preferences"
        private const val KEY_PREFIX = "tool_enabled_"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(tool: NezumiTool): Boolean {
        // GET_TIME は常に有効、その他は初期状態では無効
        val defaultEnabled = tool == NezumiTool.GET_TIME
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
