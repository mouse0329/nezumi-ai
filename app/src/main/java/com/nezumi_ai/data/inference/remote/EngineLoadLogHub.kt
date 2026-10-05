package com.nezumi_ai.data.inference.remote

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * LiteRT-LM / llama.cpp のロード中に届いたエンジンログ。
 *
 * タイムアウトはロード開始からではなく、最後のログが途絶えてからの無通信時間。
 * [begin] は開始時刻を記録するだけで、ログとしてカウントしない。
 * ログが再び届いたら [lastLogElapsedMs] を今に戻す。
 * ロード画面への表示は全般設定がオンのときだけ UI が読む。
 */
object EngineLoadLogHub {
    private const val MAX_LINES = 24
    private const val MAX_LINE_CHARS = 240

    private val loadStartedElapsed = AtomicLong(0L)
    private val lastLogElapsed = AtomicLong(0L)
    private val lines = ArrayDeque<String>()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    fun begin() {
        loadStartedElapsed.set(SystemClock.elapsedRealtime())
        lastLogElapsed.set(0L)
        synchronized(lines) {
            lines.clear()
            _text.value = ""
        }
    }

    /** ロード開始時刻。ログが一度も届いていないときの予備判定にだけ使う。 */
    fun loadStartedElapsedMs(): Long = loadStartedElapsed.get()

    /**
     * 最後にログが届いた時刻。0 は「まだログがない」。
     * 開始時刻とは別なので、ログ継続中に開始から 60 秒で切れない。
     */
    fun lastLogElapsedMs(): Long = lastLogElapsed.get()

    fun note(line: String?) {
        val trimmed = line?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        lastLogElapsed.set(SystemClock.elapsedRealtime())
        val clipped = if (trimmed.length > MAX_LINE_CHARS) trimmed.take(MAX_LINE_CHARS) + "…" else trimmed
        synchronized(lines) {
            lines.addLast(clipped)
            while (lines.size > MAX_LINES) lines.removeFirst()
            _text.value = lines.joinToString("\n")
        }
    }

    fun end() {
        synchronized(lines) {
            lines.clear()
            _text.value = ""
        }
    }
}
