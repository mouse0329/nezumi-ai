package com.nezumi_ai.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object PreferencesHelper {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch"
    private const val KEY_INITIAL_SETUP_COMPLETED = "initial_setup_completed"
    private const val KEY_THEME_MODE = "theme_mode"

    const val THEME_SYSTEM = "SYSTEM"
    const val THEME_LIGHT = "LIGHT"
    const val THEME_DARK = "DARK"

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

    fun getThemeMode(context: Context): String {
        val prefs = getSharedPreferences(context)
        return prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setThemeMode(context: Context, mode: String) {
        val normalized = when (mode.uppercase()) {
            THEME_LIGHT -> THEME_LIGHT
            THEME_DARK -> THEME_DARK
            else -> THEME_SYSTEM
        }
        val prefs = getSharedPreferences(context)
        prefs.edit().putString(KEY_THEME_MODE, normalized).apply()
    }

    fun applyThemeMode(context: Context) {
        val mode = getThemeMode(context)
        val nightMode = when (mode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun isInitialSetupCompleted(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        return prefs.getBoolean(KEY_INITIAL_SETUP_COMPLETED, false)
    }

    fun markInitialSetupCompleted(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().putBoolean(KEY_INITIAL_SETUP_COMPLETED, true).apply()
    }

    fun resetInitialSetupCompleted(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().putBoolean(KEY_INITIAL_SETUP_COMPLETED, false).apply()
    }
}
