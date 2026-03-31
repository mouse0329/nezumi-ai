package com.nezumi_ai.utils

import android.content.Context
import android.content.SharedPreferences

object PreferencesHelper {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isFirstLaunch(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        val isFirst = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        if (isFirst) {
            // フラグを更新して次回からは false を返すようにする
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        }
        return isFirst
    }

    fun resetFirstLaunchFlag(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
    }
}
