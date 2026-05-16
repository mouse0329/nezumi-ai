package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log

object HfAuthManager {

    private const val PREFS_NAME = "hf_auth"
    private const val KEY_TOKEN = "hf_token"
    private const val TAG = "HfAuthManager"

    /**
     * SharedPreferences からトークンを読み込む（毎回読み込み、メモリキャッシュなし）
     * バックグラウンドkillでトークンが揮発しないよう、毎回SharedPreferencesから取得する
     * @return トークン（未設定の場合は空文字）
     */
    fun getToken(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY_TOKEN, "") ?: ""
            if (token.isNotBlank()) {
                Log.d(TAG, "Token loaded from SharedPreferences (length: ${token.length})")
            }
            token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load token from SharedPreferences", e)
            ""
        }
    }

    /**
     * トークンを SharedPreferences に保存
     * @param token HuggingFace API トークン
     */
    fun setToken(context: Context, token: String) {
        try {
            val normalized = token.trim()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_TOKEN, normalized).apply()
            Log.d(TAG, "Token saved to SharedPreferences (length: ${normalized.length})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save token to SharedPreferences", e)
        }
    }

    /**
     * トークンをクリア
     */
    fun clearToken(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_TOKEN).apply()
            Log.d(TAG, "Token cleared from SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear token from SharedPreferences", e)
        }
    }
}
