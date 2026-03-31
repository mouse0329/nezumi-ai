package com.nezumi_ai.data.inference

import android.content.Context

object HfAuthManager {

    private const val PREFS_NAME = "hf_auth"
    private const val KEY_TOKEN = "hf_token"

    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TOKEN, "") ?: ""
    }

    fun setToken(context: Context, token: String) {
        val normalized = token.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TOKEN, normalized).apply()
    }

    fun clearToken(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_TOKEN).apply()
    }
}
