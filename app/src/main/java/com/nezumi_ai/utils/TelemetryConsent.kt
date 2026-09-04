package com.nezumi_ai.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * テレメトリ（クラッシュレポート + パフォーマンス計測）の送信同意状態を管理する。
 *
 * 重要な前提:
 *   - このフラグは「クラウド推論モデルを使う場合に限り」意味を持つ。
 *     オンデバイス推論のみで使っている間は、このフラグの値に関わらず
 *     [TelemetryGate] が Sentry SDK 自体を初期化しないため、何も送信されない。
 *   - デフォルトは OFF（オプトイン）。ユーザーが明示的に有効化するまで、
 *     クラウドモデル利用時であっても送信は行われない。
 *
 * 他の設定 (app_prefs) とはあえて別ファイルに分離している。
 * 同意状態は将来的にエクスポート/監査ログの対象になり得るため、
 * 一般設定と混在させないほうが見通しが良い。
 */
object TelemetryConsent {
    private const val PREF_NAME = "telemetry_consent"
    private const val KEY_ENABLED = "telemetry_enabled"
    private const val KEY_ASKED = "telemetry_asked"
    private const val KEY_CRASH_REPORTS = "crash_reports"
    private const val KEY_PERFORMANCE = "performance_metrics"
    private const val KEY_DIAGNOSTICS = "inference_diagnostics"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** ユーザーがテレメトリ送信に同意しているか。デフォルトは false（オプトイン）。 */
    fun isEnabled(context: Context): Boolean =
        isCrashReportsEnabled(context) || isPerformanceMetricsEnabled(context) || isInferenceDiagnosticsEnabled(context)

    fun isCrashReportsEnabled(context: Context): Boolean = getCategory(context, KEY_CRASH_REPORTS)

    fun isPerformanceMetricsEnabled(context: Context): Boolean = getCategory(context, KEY_PERFORMANCE)

    fun isInferenceDiagnosticsEnabled(context: Context): Boolean = getCategory(context, KEY_DIAGNOSTICS)

    /** 同意状態を更新する。[TelemetryGate] が次回評価時にこの値を反映する。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_CRASH_REPORTS, enabled)
            .putBoolean(KEY_PERFORMANCE, enabled)
            .putBoolean(KEY_DIAGNOSTICS, enabled)
            .putBoolean(KEY_ASKED, true)
            .apply()
    }

    fun setCrashReportsEnabled(context: Context, enabled: Boolean) = setCategory(context, KEY_CRASH_REPORTS, enabled)

    fun setPerformanceMetricsEnabled(context: Context, enabled: Boolean) = setCategory(context, KEY_PERFORMANCE, enabled)

    fun setInferenceDiagnosticsEnabled(context: Context, enabled: Boolean) = setCategory(context, KEY_DIAGNOSTICS, enabled)

    /** 初回のクラウドモデル利用時など、同意確認ダイアログをまだ出していないかどうか。 */
    fun hasBeenAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED, false)

    private fun getCategory(context: Context, key: String): Boolean {
        val preferences = prefs(context)
        return if (preferences.contains(key)) {
            preferences.getBoolean(key, false)
        } else {
            preferences.getBoolean(KEY_ENABLED, false)
        }
    }

    private fun setCategory(context: Context, key: String, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(key, enabled)
            .putBoolean(KEY_ASKED, true)
            .apply()
    }
}
