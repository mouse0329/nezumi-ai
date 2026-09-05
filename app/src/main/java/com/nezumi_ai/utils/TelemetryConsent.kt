package com.nezumi_ai.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * テレメトリ（クラッシュレポート + パフォーマンス計測 + 診断情報）の
 * 送信同意状態を管理する。
 *
 * 同意状態は 3 つのカテゴリ別フラグの組み合わせとしてのみ管理する
 * （グローバルな全体フラグは持たない）:
 *
 *   - 全体トグル（[setAllEnabled]）は 3 カテゴリを同時に書き換える
 *     「見た目上の操作」として実装される。
 *   - 個別カテゴリの読み出しは常に「保存された値そのもの」を返し、
 *     他のキーへのフォールバックは行わない。したがって将来
 *     「特定カテゴリだけ設定をリセットする」機能を実装しても、
 *     ユーザーが明示的に OFF にしたカテゴリが復活することはない。
 *   - 「テレメトリ送信に同意している」は「いずれか 1 つでもカテゴリが
 *     ON」（[isEnabled]）として定義する。
 *
 * 重要な前提:
 *   - このフラグは「クラウド or オンデバイスいずれかの推論が使われた場合に
 *     限り」意味を持つ。推論機能を一度も使っていない間は、このフラグの値に
 *     関わらず [TelemetryGate] が Sentry SDK 自体を初期化しないため、
 *     何も送信されない。
 *   - デフォルトは OFF（オプトイン）。ユーザーが明示的に有効化するまで、
 *     推論機能の利用時であっても送信は行われない。
 *
 * カテゴリとイベントの対応（UI の説明文言とも揃えること）:
 *   - [Category.CRASH] … 未捕捉例外のスタックトレース等（クラッシュレポート）
 *   - [Category.PERFORMANCE] … モデルロード所要時間・推論速度などの計測値
 *   - [Category.DIAGNOSTICS] … モデルロード失敗・推論失敗の種類（エラー情報）
 *
 * 他の設定 (app_prefs) とはあえて別ファイルに分離している。
 * 同意状態は将来的にエクスポート/監査ログの対象になり得るため、
 * 一般設定と混在させないほうが見通しが良い。
 */
object TelemetryConsent {
    private const val PREF_NAME = "telemetry_consent"
    private const val KEY_ASKED = "telemetry_asked"
    private const val KEY_CRASH_REPORTS = "crash_reports"
    private const val KEY_PERFORMANCE = "performance_metrics"
    private const val KEY_DIAGNOSTICS = "inference_diagnostics"

    /** テレメトリの送信カテゴリ。 */
    enum class Category {
        CRASH_REPORTS,
        PERFORMANCE,
        DIAGNOSTICS;

        internal val prefKey: String
            get() = when (this) {
                CRASH_REPORTS -> KEY_CRASH_REPORTS
                PERFORMANCE -> KEY_PERFORMANCE
                DIAGNOSTICS -> KEY_DIAGNOSTICS
            }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * ユーザーがテレメトリ送信に同意しているか（= いずれかのカテゴリが ON か）。
     * デフォルトは false（オプトイン）。
     */
    fun isEnabled(context: Context): Boolean =
        Category.entries.any { isCategoryEnabled(context, it) }

    fun isCrashReportsEnabled(context: Context): Boolean =
        isCategoryEnabled(context, Category.CRASH_REPORTS)

    fun isPerformanceMetricsEnabled(context: Context): Boolean =
        isCategoryEnabled(context, Category.PERFORMANCE)

    fun isInferenceDiagnosticsEnabled(context: Context): Boolean =
        isCategoryEnabled(context, Category.DIAGNOSTICS)

    fun isCategoryEnabled(context: Context, category: Category): Boolean =
        prefs(context).getBoolean(category.prefKey, false)

    /**
     * 全体トグル。3 カテゴリすべてを一括で ON/OFF する。
     * 更新後の状態に応じて [TelemetryGate] の起動/停止もここで面倒を見るため、
     * UI 側はこのメソッドを呼ぶだけでよい。
     */
    fun setAllEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CRASH_REPORTS, enabled)
            .putBoolean(KEY_PERFORMANCE, enabled)
            .putBoolean(KEY_DIAGNOSTICS, enabled)
            .putBoolean(KEY_ASKED, true)
            .apply()
        onConsentUpdated(context)
    }

    fun setCrashReportsEnabled(context: Context, enabled: Boolean) =
        setCategoryEnabled(context, Category.CRASH_REPORTS, enabled)

    fun setPerformanceMetricsEnabled(context: Context, enabled: Boolean) =
        setCategoryEnabled(context, Category.PERFORMANCE, enabled)

    fun setInferenceDiagnosticsEnabled(context: Context, enabled: Boolean) =
        setCategoryEnabled(context, Category.DIAGNOSTICS, enabled)

    /**
     * カテゴリ別の同意状態を更新する統一 API。
     *
     * 「全カテゴリが OFF になったら [TelemetryGate.onConsentRevoked] で
     * Sentry を停止する」という判断もここで行う。UI 層（設定画面・
     * セットアップ画面など）はカテゴリと値を渡すだけでよく、
     * 停止条件を個別に実装する必要はない。
     */
    fun setCategoryEnabled(context: Context, category: Category, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(category.prefKey, enabled)
            .putBoolean(KEY_ASKED, true)
            .apply()
        onConsentUpdated(context)
    }

    /** 初回のクラウドモデル利用時など、同意確認ダイアログをまだ出していないかどうか。 */
    fun hasBeenAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED, false)

    private fun onConsentUpdated(context: Context) {
        if (!isEnabled(context)) {
            // 全カテゴリが OFF になった = 同意が完全に撤回された。
            // 既に起動済みの Sentry を停止し、以降のイベント送信を止める。
            TelemetryGate.onConsentRevoked()
        } else if (TelemetryGate.isInferenceInUse()) {
            // 既に推論（クラウド/オンデバイス問わず）が利用中であれば、
            // 同意の再付与を即座に反映して Sentry を起動する。
            // 未利用なら何もしない（次回の利用開始時に起動される）。
            TelemetryGate.startIfConsented(context)
        }
    }
}
