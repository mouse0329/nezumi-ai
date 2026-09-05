package com.nezumi_ai.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * アプリの未捕捉例外 (UncaughtException) をフックし、
 * スタックトレースとメタ情報をアプリ内ストレージにファイル保存するレコーダー。
 *
 * - Application.onCreate() の最も早い段階で [install] を呼び出す想定。
 *   他の初期化より先に登録することで、初期化コード自体の例外も捕捉できる。
 * - 保存先は filesDir/crash_records/ 配下。1件 1ファイル (JSON ライク)。
 *   保持件数の上限を超えたら古いものから削除する
 *   （＝LogcatRecorder と同じ発想の簡易リングバッファ）。
 * - クラッシュ時にネットワーク送信などの重い処理は行わない。
 *   プロセスが直後に終了するため書ききれないリスクがあるため、
 *   「まずファイルに書く」→「次回起動時に UI で通知」の 2 段構成にする。
 *
 * 次回起動時のモーダル表示は [MainActivity] 側で [getPendingCrash] / [clearAll] を使う。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_DIR_NAME = "crash_records"
    private const val FILE_PREFIX = "crash_"
    private const val FILE_SUFFIX = ".log"

    // 保持するクラッシュログ件数の上限。これを超えたら古いものから削除する。
    private const val MAX_FILE_COUNT = 10

    // ログ末尾にサマリー用として合わせて保存する logcat 末尾の最大文字数。
    // 大きすぎるとダイアログ描画が重くなるため控えめに。
    private const val LOGCAT_TAIL_MAX_CHARS = 8_000

    private val installed = AtomicBoolean(false)

    /**
     * 未捕捉例外ハンドラをインストールする。多重呼び出しは無視される。
     * @param context Application 相当の Context。filesDir を参照する。
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // ここは クラッシュ寸前の非常に短い時間しか動けないため、
            // 例外を握りつぶしてでも「デフォルトハンドラへの委譲」を最優先する。
            runCatching { saveCrashLog(appContext, thread, throwable) }
                .onFailure { Log.w(TAG, "Failed to save crash log", it) }

            // Sentry がアクティブ（クラウド or オンデバイスいずれかの推論利用中
            // + ユーザー同意済み）な場合のみ、ローカル保存に加えて Sentry にも転送する。
            // TelemetryGate.isActive() が false（＝推論未使用、または未同意）の間は
            // ここは何もしない。
            runCatching { TelemetryGate.captureException(throwable) }
                .onFailure { Log.w(TAG, "Failed to forward crash to Sentry", it) }

            // 標準のクラッシュ処理 (システムダイアログ表示 / プロセス終了) に委譲。
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "CrashReporter installed. dir=${crashDir(appContext).absolutePath}")
    }

    /** クラッシュログの保存先ディレクトリ。 */
    fun crashDir(context: Context): File = File(context.filesDir, CRASH_DIR_NAME)

    /**
     * 未読 (=まだユーザーに提示していない) クラッシュログのうち、
     * 最新の 1 件を返す。存在しなければ null。
     */
    fun getPendingCrash(context: Context): CrashLog? {
        val files = sortedCrashFiles(crashDir(context))
        if (files.isEmpty()) return null
        val latest = files.last()
        return runCatching { CrashLog.fromText(latest.name, latest.readText()) }.getOrNull()
    }

    /** 保存済みのクラッシュログを全件削除する (ダイアログを閉じた後に呼ぶ)。 */
    fun clearAll(context: Context) {
        val dir = crashDir(context)
        dir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "CrashReporter logs cleared")
    }

    /** 現在の保持件数。 */
    fun pendingCount(context: Context): Int = sortedCrashFiles(crashDir(context)).size

    private fun saveCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        if (!dir.exists()) dir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()

        // LogcatRecorder が保存済みの logcat 末尾を、クラッシュ直前の文脈として同梱する。
        // LogcatRecorder 未起動でも例外を投げないよう防御的に読み出す。
        val logcatTail = runCatching {
            val all = LogcatRecorder.readAllLogs(context, maxChars = LOGCAT_TAIL_MAX_CHARS)
            if (all.length > LOGCAT_TAIL_MAX_CHARS) {
                "...(\u7701\u7565)...\n" + all.substring(all.length - LOGCAT_TAIL_MAX_CHARS)
            } else {
                all
            }
        }.getOrDefault("")

        val log = CrashLog(
            fileName = "$FILE_PREFIX$timestamp$FILE_SUFFIX",
            timestamp = timestamp,
            threadName = thread.name,
            message = throwable.message ?: throwable::class.java.simpleName,
            exceptionClass = throwable::class.java.name,
            stackTrace = stackTrace,
            logcatTail = logcatTail
        )

        val file = File(dir, log.fileName)
        file.writeText(log.toText())

        enforceRetentionLimit(dir)
    }

    private fun sortedCrashFiles(dir: File): List<File> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?: return emptyList()
        // ファイル名の timestamp 部分でソート = 生成順 = 古い順。
        return files.sortedBy { extractTimestamp(it.name) }
    }

    private fun extractTimestamp(fileName: String): Long =
        fileName.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L

    /** 保持ファイル数が上限を超えていたら、古いものから削除する。 */
    private fun enforceRetentionLimit(dir: File) {
        val files = sortedCrashFiles(dir)
        val overflow = files.size - MAX_FILE_COUNT
        if (overflow <= 0) return
        files.take(overflow).forEach { f ->
            val deleted = f.delete()
            Log.d(TAG, "rotated out old crash file: ${f.name} deleted=$deleted")
        }
    }

    /**
     * 1件分のクラッシュログ。ファイルには単純なヘッダ+本文形式で書き出す
     * （JSON エスケープを避けて可読性を優先。読み込みも簡単）。
     */
    data class CrashLog(
        val fileName: String,
        val timestamp: Long,
        val threadName: String,
        val message: String,
        val exceptionClass: String,
        val stackTrace: String,
        val logcatTail: String
    ) {
        fun formattedTimestamp(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

        fun toText(): String = buildString {
            append("timestamp=").append(timestamp).append('\n')
            append("thread=").append(threadName).append('\n')
            append("exception=").append(exceptionClass).append('\n')
            append("message=").append(message.replace("\n", " ")).append('\n')
            append(SECTION_STACK).append('\n')
            append(stackTrace)
            if (!stackTrace.endsWith("\n")) append('\n')
            append(SECTION_LOGCAT).append('\n')
            append(logcatTail)
        }

        companion object {
            private const val SECTION_STACK = "----STACK----"
            private const val SECTION_LOGCAT = "----LOGCAT----"

            fun fromText(fileName: String, text: String): CrashLog {
                var timestamp = 0L
                var threadName = "unknown"
                var message = ""
                var exceptionClass = ""

                val stackIdx = text.indexOf(SECTION_STACK)
                val logcatIdx = text.indexOf(SECTION_LOGCAT)

                val headerEnd = if (stackIdx >= 0) stackIdx else text.length
                text.substring(0, headerEnd).lineSequence().forEach { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) return@forEach
                    val key = line.substring(0, eq)
                    val value = line.substring(eq + 1)
                    when (key) {
                        "timestamp" -> timestamp = value.toLongOrNull() ?: 0L
                        "thread" -> threadName = value
                        "message" -> message = value
                        "exception" -> exceptionClass = value
                    }
                }

                val stack = if (stackIdx >= 0) {
                    val start = stackIdx + SECTION_STACK.length + 1 // skip newline
                    val end = if (logcatIdx > stackIdx) logcatIdx else text.length
                    if (start in 0..end) text.substring(start, end) else ""
                } else ""

                val logcat = if (logcatIdx >= 0) {
                    val start = logcatIdx + SECTION_LOGCAT.length + 1
                    if (start in 0..text.length) text.substring(start) else ""
                } else ""

                return CrashLog(
                    fileName = fileName,
                    timestamp = timestamp,
                    threadName = threadName,
                    message = message,
                    exceptionClass = exceptionClass,
                    stackTrace = stack,
                    logcatTail = logcat
                )
            }
        }
    }
}
