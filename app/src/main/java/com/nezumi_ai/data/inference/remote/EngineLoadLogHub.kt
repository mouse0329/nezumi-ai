package com.nezumi_ai.data.inference.remote

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * LiteRT-LM / llama.cpp のロード中に届いたエンジンログ。
 *
 * タイムアウトは開始時刻ではなく、最後のログからの無通信時間で判定する。
 * ログが再び届いたら [lastActivityElapsedMs] を今に戻す。
 * ロード画面への表示は全般設定がオンのときだけ UI が読む。
 */
object EngineLoadLogHub {
    private const val MAX_LINES = 24
    private const val MAX_LINE_CHARS = 240

    private val lastActivityElapsed = AtomicLong(SystemClock.elapsedRealtime())
    private val lines = ArrayDeque<String>()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    fun begin() {
        lastActivityElapsed.set(SystemClock.elapsedRealtime())
        synchronized(lines) {
            lines.clear()
            _text.value = ""
        }
    }

    fun lastActivityElapsedMs(): Long = lastActivityElapsed.get()

    fun note(line: String?) {
        val trimmed = line?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        lastActivityElapsed.set(SystemClock.elapsedRealtime())
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
