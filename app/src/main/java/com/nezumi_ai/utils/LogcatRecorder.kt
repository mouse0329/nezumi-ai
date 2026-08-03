package com.nezumi_ai.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * アプリ自身の logcat 出力を常時バックグラウンドで収集し、
 * ディスク上にサイズローテーションしながら保存し続けるレコーダー。
 *
 * - Android 4.1+ の制限により、アプリは自プロセスのログしか読めない
 *   （READ_LOGS はシステム署名権限のため一般アプリでは取得不可）。
 *   ここでは `logcat -v threadtime` を継続実行し、自プロセスのログを
 *   そのまま追記していく。
 * - 1ファイルが MAX_FILE_SIZE_BYTES を超えたら次のファイルにローテーションし、
 *   保持ファイル数が MAX_FILE_COUNT を超えたら最も古いファイルを削除する
 *   （＝「古いものから消す」リングバッファ的な保持）。
 * - 起動中はプロセスを握り続けるだけなので、UI 側は保存済みファイルを
 *   読み込んで表示すればよい（[readAllLogs] / [logDir]）。
 */
object LogcatRecorder {

    private const val TAG = "LogcatRecorder"
    private const val LOG_DIR_NAME = "logcat_records"
    private const val FILE_PREFIX = "logcat_"
    private const val FILE_SUFFIX = ".log"

    // 1ファイルあたりの上限サイズ。超えたら新しいファイルにローテーションする。
    private const val MAX_FILE_SIZE_BYTES = 512L * 1024L // 512KB
    // 保持するファイル数の上限。これを超えたら最古のファイルを削除する。
    private const val MAX_FILE_COUNT = 8

    private val isRunning = AtomicBoolean(false)
    private var recorderThread: Thread? = null
    private var logcatProcess: Process? = null

    /**
     * 常時収集を開始する。すでに起動済みなら何もしない。
     * Application.onCreate() から一度だけ呼び出すことを想定。
     */
    @Synchronized
    fun start(context: Context) {
        if (isRunning.get()) return
        isRunning.set(true)

        val dir = logDir(context)
        if (!dir.exists()) dir.mkdirs()

        val thread = Thread({
            runCatching { recordLoop(dir) }
                .onFailure { e -> Log.w(TAG, "logcat recording loop stopped: ${e.message}") }
            isRunning.set(false)
        }, "LogcatRecorderThread")
        thread.isDaemon = true
        thread.start()
        recorderThread = thread

        Log.i(TAG, "LogcatRecorder started. dir=${dir.absolutePath}")
    }

    /** 収集を止め、logcat プロセスを破棄する。通常は呼ばなくてよい。 */
    @Synchronized
    fun stop() {
        isRunning.set(false)
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
        recorderThread = null
    }

    /** ログファイルの保存先ディレクトリ。 */
    fun logDir(context: Context): File = File(context.filesDir, LOG_DIR_NAME)

    /**
     * 保存済みログを古い順に連結して1つの文字列として返す。
     * デバッグ画面での表示・共有用。
     */
    fun readAllLogs(context: Context, maxChars: Int = 200_000): String {
        val files = sortedLogFiles(logDir(context))
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        for (f in files) {
            runCatching {
                sb.append(f.readText())
                if (!sb.endsWith("\n")) sb.append('\n')
            }
        }
        return if (sb.length > maxChars) {
            // 表示は末尾（最新側）を優先する
            "...(省略)...\n" + sb.substring(sb.length - maxChars)
        } else {
            sb.toString()
        }
    }

    /** 蓄積済みログファイルをすべて削除する（表示のクリアボタン用）。 */
    fun clearAll(context: Context) {
        val dir = logDir(context)
        dir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "LogcatRecorder logs cleared")
    }

    /** 現在保存されている合計サイズ（バイト）。UI 表示の補助情報用。 */
    fun totalSizeBytes(context: Context): Long =
        sortedLogFiles(logDir(context)).sumOf { it.length() }

    /**
     * 蓄積ログを1つのファイルにマージして cacheDir/shared_logs 配下に書き出し、
     * そのファイルを返す。共有(Intent.ACTION_SEND)やエクスポート用。
     * 呼び出し側で FileProvider.getUriForFile によって URI 化する想定。
     */
    fun exportToFile(context: Context): File {
        val exportDir = File(context.cacheDir, "shared_logs")
        if (!exportDir.exists()) exportDir.mkdirs()
        // 毎回上書きせず日時付きファイル名にして、共有履歴などで区別しやすくする。
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val exportFile = File(exportDir, "nezumi_logcat_$timestamp.log")
        exportFile.writeText(readAllLogs(context, maxChars = Int.MAX_VALUE))
        return exportFile
    }

    private fun sortedLogFiles(dir: File): List<File> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return emptyList()
        // ファイル名の連番（logcat_<seq>.log）でソート = 生成順 = 古い順
        return files.sortedBy { extractSeq(it.name) }
    }

    private fun extractSeq(fileName: String): Long =
        fileName.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L

    private fun nextSeq(dir: File): Long {
        val existing = sortedLogFiles(dir)
        return if (existing.isEmpty()) 1L else extractSeq(existing.last().name) + 1L
    }

    /**
     * logcat をクリアしてから自プロセス分を継続 tail し、行ごとに現在のファイルへ追記する。
     * ファイルサイズが上限を超えたら新しいファイルへローテーションし、
     * 保持数上限を超えた最古のファイルを削除する。
     */
    private fun recordLoop(dir: File) {
        // 前回までのシステムバッファを一旦クリアし、以降の差分だけを追い続ける。
        // 失敗しても致命的ではないので無視してよい。
        runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        }

        val pid = android.os.Process.myPid()
        // -v threadtime: 時刻・スレッドID付き。--pid で自プロセスのみに絞る（API 24+）。
        // 古い端末向けに --pid が効かなくても "logcat" 自体は自プロセス限定になるため実害は小さい。
        val command = arrayOf("logcat", "-v", "threadtime", "--pid=$pid")
        val process = Runtime.getRuntime().exec(command)
        logcatProcess = process

        var currentSeq = nextSeq(dir)
        var currentFile = File(dir, "$FILE_PREFIX$currentSeq$FILE_SUFFIX")
        var out = FileOutputStream(currentFile, true)

        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (isRunning.get()) {
                    line = reader.readLine() ?: break
                    val bytes = (line + "\n").toByteArray(Charsets.UTF_8)

                    if (currentFile.length() + bytes.size > MAX_FILE_SIZE_BYTES) {
                        out.flush()
                        out.close()
                        currentSeq = nextSeq(dir)
                        currentFile = File(dir, "$FILE_PREFIX$currentSeq$FILE_SUFFIX")
                        out = FileOutputStream(currentFile, true)
                        enforceRetentionLimit(dir)
                    }

                    out.write(bytes)
                }
            }
        } finally {
            runCatching { out.flush(); out.close() }
            runCatching { process.destroy() }
        }
    }

    /** 保持ファイル数が上限を超えていたら、古いものから削除する。 */
    private fun enforceRetentionLimit(dir: File) {
        val files = sortedLogFiles(dir)
        val overflow = files.size - MAX_FILE_COUNT
        if (overflow <= 0) return
        files.take(overflow).forEach { f ->
            val deleted = f.delete()
            Log.d(TAG, "rotated out old log file: ${f.name} deleted=$deleted")
        }
    }
}
