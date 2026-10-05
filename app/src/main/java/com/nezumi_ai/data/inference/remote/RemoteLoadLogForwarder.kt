package com.nezumi_ai.data.inference.remote

import android.os.SystemClock
import android.util.Log

/**
 * リモートプロセス (:gguf / :litert) 自身の logcat を追い、
 * ロード中のエンジンログをアプリプロセスへ返す。
 *
 * 一般アプリは他プロセスの logcat を読めないため、ログが出ているプロセス側で読む。
 * 行が届くたびに呼び出し側の無通信タイマーをリセットする。
 *
 * `--pid` と `-T` を同時に付けると、一部の端末で先頭 1 行だけ出して終了し、
 * タイマーがロード開始から 60 秒のままになる。追従は `-T 1` のみにし、
 * プロセス終了まで読み続ける。
 *
 * android.os.Process と java.lang.Process を混同しないこと。
 */
internal class RemoteLoadLogForwarder(
    private val callback: IRemoteResultCallback?
) {
    private var logcatProcess: java.lang.Process? = null
    private var thread: Thread? = null

    fun start() {
        if (callback == null || thread != null) return
        val proc = runCatching {
            ProcessBuilder("logcat", "-v", "brief", "-T", "1")
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            Log.w(TAG, "logcat follow failed to start", it)
            return
        }
        logcatProcess = proc
        val progress = callback
        thread = Thread({
            var lastSentAt = 0L
            runCatching {
                proc.inputStream.bufferedReader().use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        val raw = reader.readLine() ?: break
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("---------")) continue
                        val now = SystemClock.elapsedRealtime()
                        // Binder を埋めないよう送信は間引く。間引いても「動いている」信号にはなる。
                        if (now - lastSentAt < SEND_INTERVAL_MS) continue
                        lastSentAt = now
                        runCatching { progress.onProgress(line.take(MAX_LINE_CHARS)) }
                    }
                }
            }.onFailure { Log.w(TAG, "logcat follow stopped: ${it.message}") }
        }, "RemoteLoadLogForwarder").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
        thread?.interrupt()
        thread = null
    }

    companion object {
        private const val TAG = "RemoteLoadLogForwarder"
        private const val SEND_INTERVAL_MS = 200L
        private const val MAX_LINE_CHARS = 400
    }
}
