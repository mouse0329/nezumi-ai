package com.nezumi_ai.utils

import android.content.Context
import com.nezumi_ai.data.preset.PresetModelCatalog

/**
 * クラウドモデルをプリセット / チャットのモデル一覧から隠す。
 * モデル管理画面には残し、そこから戻せる。
 */
object CloudModelVisibility {
    private const val PREF = "cloud_model_visibility"
    private const val KEY = "hidden"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isHidden(context: Context, modelId: String): Boolean =
        prefs(context).getStringSet(KEY, emptySet())?.contains(modelId) == true

    fun setHidden(context: Context, modelId: String, hidden: Boolean) {
        val next = prefs(context).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (hidden) next.add(modelId) else next.remove(modelId)
        prefs(context).edit().putStringSet(KEY, next).apply()
        PresetModelCatalog.invalidateCache()
    }
}
