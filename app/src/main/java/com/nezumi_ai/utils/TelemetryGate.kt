package com.nezumi_ai.utils

import android.content.Context
import android.util.Log
import com.nezumi_ai.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.SentryId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * テレメトリ (Sentry) の起動・停止を一元管理する門番。
 *
 * このアプリは「オフライン優先」を核とする設計だが、実利用の大半を占める
 * オンデバイス推論（GGUF / LiteRtLm）のクラッシュ・パフォーマンスデータは
 * デバッグ上の価値が高いため、クラウド推論と同様の条件でテレメトリ対象に含める。
 * テレメトリは以下の 2 条件を **両方** 満たした場合にのみ有効化される:
 *
 *   1. 推論エンジン（クラウド or オンデバイスのいずれか）が実際に使用されている
 *      （[onCloudInferenceUsed] または [onLocalInferenceUsed] が呼ばれている）
 *   2. ユーザーがテレメトリ送信に同意している（[TelemetryConsent.isEnabled]）
 *
 * ユーザーが同意していない、またはまだ推論を一度も行っていない間は、
 * AndroidManifest 側で Sentry の ContentProvider 自動初期化を無効化しているため
 * （io.sentry.auto-init=false）、このクラスが明示的に [SentryAndroid.init] を
 * 呼ばない限り SDK は一切起動せず、ネットワーク呼び出しも一切発生しない。
 *
 * 送信される内容:
 *   - 未捕捉例外のスタックトレース、メッセージ、発生スレッド名
 *   - パフォーマンス計測（起動時間・推論所要時間などの transaction/span）
 *   - 端末モデル名・OS バージョンなど Sentry SDK が既定で収集する軽量なコンテキスト
 * 送信されない内容:
 *   - チャット本文・プロンプト・生成結果・画像などのユーザーコンテンツ全般
 *     （[beforeSend] / [beforeSendTransaction] で breadcrumb の message を伴う
 *     イベントのうち、アプリ側コードが明示的に付与したもの以外は加工していないが、
 *     アプリはユーザーコンテンツを breadcrumb やイベント extra に一切詰めない方針とする）
 *   - PII（sendDefaultPii は明示的に false のまま）
 */
object TelemetryGate {
    private const val TAG = "TelemetryGate"

    private val sentryStarted = AtomicBoolean(false)
    private val inferenceInUse = AtomicBoolean(false)
    @Volatile
    private var appContext: Context? = null

    /**
     * Application.onCreate() から一度だけ呼ぶ。まだ Sentry は起動しない
     * （起動は [onCloudInferenceUsed] / [onLocalInferenceUsed] 経由でのみ行う）。
     * 単にコンテキストを保持するだけ。
     */
    fun initHooks(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * クラウド推論エンジン（Claude/Gemini/OpenAI互換/LM Studio/Ollama）が
     * 実際に呼び出されるタイミングで呼ぶ。同意済みかつ DSN 設定済みなら、
     * この呼び出しを合図に初めて Sentry SDK を起動する。
     *
     * 冪等: 既に起動済みなら何もしない。同意が無ければ何もしない
     * （SDK 未起動のまま = ネットワークアクセスなし）。
     */
    fun onCloudInferenceUsed() {
        val context = appContext ?: return
        inferenceInUse.set(true)
        if (!TelemetryConsent.isEnabled(context)) return
        startSentryIfNeeded(context)
    }

    /**
     * オンデバイス推論エンジン（GGUF / LiteRT など）が実際に使われる
     * タイミングで呼ぶ。起動条件は [onCloudInferenceUsed] と同じで、
     * ユーザー同意済みかつ DSN 設定済みの場合にのみ Sentry を起動する。
     */
    fun onLocalInferenceUsed() {
        val context = appContext ?: return
        inferenceInUse.set(true)
        if (!TelemetryConsent.isEnabled(context)) return
        startSentryIfNeeded(context)
    }

    /**
     * 同意状態の変更に応答して、条件（推論利用中 + 同意済み）を再評価する。
     * [TelemetryConsent] の setter からのみ呼ばれる想定。
     * 条件が揃っていなければ何もしない（冪等）。
     */
    internal fun startIfConsented(context: Context) {
        if (!inferenceInUse.get()) return
        if (!TelemetryConsent.isEnabled(context)) return
        startSentryIfNeeded(context)
    }

    /**
     * 現在 Sentry が起動中 (= 実際にイベントを送信し得る状態) かどうか。
     * パフォーマンス計測箇所がトランザクション開始前にこれを見て、
     * 無駄な計測コストを避けるために使う。
     */
    fun isActive(): Boolean = sentryStarted.get()

    /**
     * クラウド/オンデバイスいずれかの推論がこのプロセスで使われたかどうか。
     * [TelemetryConsent] が同意変更時に Sentry 起動要否を判断するために使う。
     */
    fun isInferenceInUse(): Boolean = inferenceInUse.get()

    /**
     * ユーザーが設定画面でテレメトリを無効化した場合に呼ぶ。
     * 既に起動済みの Sentry を停止し、以降のイベント送信を止める。
     */
    fun onConsentRevoked() {
        if (sentryStarted.compareAndSet(true, false)) {
            runCatching { Sentry.close() }
                .onFailure { Log.w(TAG, "Sentry.close() failed", it) }
            Log.i(TAG, "Telemetry disabled by user; Sentry stopped.")
        }
    }

    /**
     * 未捕捉例外を Sentry に転送する。[com.nezumi_ai.utils.CrashReporter] による
     * ローカル保存とは独立した経路であり、Sentry が非アクティブなら何もしない。
     */
    fun captureException(throwable: Throwable): SentryId? {
        if (!sentryStarted.get() || !hasCategory(TelemetryCategory.CRASH)) return null
        return runCatching { Sentry.captureException(throwable) }.getOrNull()
    }

    /** 推論エラーなどの診断情報を送る場合に使う。 */
    fun captureDiagnostic(message: String, level: SentryLevel = SentryLevel.INFO) {
        captureMessage(message, level, TelemetryCategory.DIAGNOSTICS)
    }

    /** 推論速度などのパフォーマンス情報を送る場合に使う。 */
    fun capturePerformance(message: String, level: SentryLevel = SentryLevel.INFO) {
        captureMessage(message, level, TelemetryCategory.PERFORMANCE)
    }

    private fun captureMessage(message: String, level: SentryLevel, category: TelemetryCategory) {
        if (!sentryStarted.get() || !hasCategory(category)) return
        runCatching { Sentry.captureMessage(message, level) }
    }

    private fun hasCategory(category: TelemetryCategory): Boolean {
        val context = appContext ?: return false
        return when (category) {
            TelemetryCategory.CRASH ->
                TelemetryConsent.isCategoryEnabled(context, TelemetryConsent.Category.CRASH_REPORTS)
            TelemetryCategory.PERFORMANCE ->
                TelemetryConsent.isCategoryEnabled(context, TelemetryConsent.Category.PERFORMANCE)
            TelemetryCategory.DIAGNOSTICS ->
                TelemetryConsent.isCategoryEnabled(context, TelemetryConsent.Category.DIAGNOSTICS)
        }
    }

    private enum class TelemetryCategory { CRASH, PERFORMANCE, DIAGNOSTICS }

    private fun startSentryIfNeeded(context: Context) {
        if (!sentryStarted.compareAndSet(false, true)) return

        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            // DSN 未設定のビルド (OSS ビルドやローカル開発など) では、
            // 同意があっても静かに何もしない。フラグは戻しておく。
            sentryStarted.set(false)
            Log.i(TAG, "SENTRY_DSN not configured; telemetry stays disabled.")
            return
        }

        runCatching {
            SentryAndroid.init(context) { options ->
                options.dsn = dsn
                // ユーザーコンテンツ・IP・端末識別子などの PII は一切送らない。
                options.isSendDefaultPii = false
                // パフォーマンス計測（起動時間・推論時間）を有効化。
                // ただし送信頻度を抑えるためサンプリングする。
                options.tracesSampleRate = 0.2
                // クラウド or オンデバイスいずれかの推論利用時のみ有効化する運用であり、
                // 常時起動するセッション追跡（画面遷移ごとのイベント等）はノイズが多いため無効化。
                options.isEnableAutoSessionTracking = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                // ビルド種別をタグとして残す（機微情報ではない）。
                options.environment = if (BuildConfig.DEBUG) "debug" else "release"
                options.release = "nezumi-ai@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            }
            Log.i(TAG, "Sentry started (inference in use, user consented).")
        }.onFailure {
            sentryStarted.set(false)
            Log.w(TAG, "Failed to start Sentry", it)
        }
    }
}
