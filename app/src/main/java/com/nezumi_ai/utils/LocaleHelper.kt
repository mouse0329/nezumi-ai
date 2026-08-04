package com.nezumi_ai.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * アプリの UI 言語を切り替えるためのヘルパー。
 *
 * - `SYSTEM` の場合は端末のデフォルト Locale をそのまま使う。
 * - `JA` / `EN` を指定した場合は該当 Locale で `createConfigurationContext` した
 *   Context を返し、Activity / Application の `attachBaseContext` から適用する。
 *
 * 変更は Activity 再作成のタイミングで反映されるので、
 * 切り替え直後は `activity?.recreate()` を呼び出すこと。
 */
object LocaleHelper {

    /**
     * 現在の言語設定に従い、指定 Context にロケールを適用した
     * ラップ済み Context を返す。
     */
    fun wrap(context: Context): Context {
        val lang = PreferencesHelper.getLanguage(context)
        val locale = resolveLocale(lang) ?: return context
        return applyLocale(context, locale)
    }

    /**
     * 言語コードから Locale を解決する。SYSTEM の場合は null を返して
     * OS デフォルトを尊重する。
     */
    private fun resolveLocale(lang: String): Locale? {
        return when (lang) {
            PreferencesHelper.LANG_JA -> Locale.JAPANESE
            PreferencesHelper.LANG_EN -> Locale.ENGLISH
            else -> null
        }
    }

    private fun applyLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = android.os.LocaleList(locale)
            android.os.LocaleList.setDefault(list)
            config.setLocales(list)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }
}
