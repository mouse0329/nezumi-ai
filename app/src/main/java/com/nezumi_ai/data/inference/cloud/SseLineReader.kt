package com.nezumi_ai.data.inference.cloud

import okhttp3.Response
import okio.BufferedSource
import java.io.Closeable

/**
 * OkHttp の [Response.body] から SSE (`text/event-stream`) を **行単位で** 読む
 * 軽量なリーダー。
 *
 * OkHttp には `okhttp-sse` (EventSources) もあるが、
 * - コールバックベースでコルーチンとの相性が悪い
 * - Anthropic の `event: ...` 行を含む "typed event" を厳密には
 *   イベントとしてしか渡さないため、任意の行を素の状態で扱いたい今回の用途には
 *   `BufferedSource.readUtf8Line()` を直に叩く方が扱いやすい
 *
 * という理由でここでは自前で実装する。
 * SSE 仕様の最小サブセットのみサポート:
 *   - 空行 = メッセージ区切り
 *   - `data: xxx` の連結 (同一メッセージに複数 data 行がある場合の結合)
 *   - `event: xxx` の保持 (Anthropic 用に必要)
 *   - コメント (`:` 始まり) は無視
 *
 * ## 使い方
 * ```kotlin
 * SseLineReader(response).use { reader ->
 *   reader.forEachMessage { event, data ->
 *     // event は "message" 相当 (未指定なら null), data は結合済み
 *   }
 * }
 * ```
 */
class SseLineReader(private val response: Response) : Closeable {

    private val source: BufferedSource? = response.body?.source()

    /**
     * SSE メッセージを順に消費するループ。
     *
     * @param onMessage (event, data) のペアを 1 メッセージ毎に受け取る。
     *   event が未指定なら null、data はそのメッセージ内の "data:" 行を "\n" で連結した文字列。
     *   コールバックが false を返した場合、または呼び出し側のコルーチンがキャンセルされた場合は
     *   ループを打ち切る。
     */
    inline fun forEachMessage(onMessage: (event: String?, data: String) -> Boolean) {
        val src = internalSource() ?: return
        var eventName: String? = null
        val dataBuffer = StringBuilder()

        while (true) {
            val line = try {
                src.readUtf8Line()
            } catch (t: Throwable) {
                // ソケットクローズ / タイムアウト等はストリーム終端として扱う
                null
            } ?: break

            if (line.isEmpty()) {
                // メッセージ区切り
                if (dataBuffer.isNotEmpty() || eventName != null) {
                    val cont = onMessage(eventName, dataBuffer.toString())
                    dataBuffer.setLength(0)
                    eventName = null
                    if (!cont) return
                }
                continue
            }

            if (line.startsWith(":")) continue  // コメント行は無視

            val colonIdx = line.indexOf(':')
            val field: String
            val value: String
            if (colonIdx < 0) {
                field = line
                value = ""
            } else {
                field = line.substring(0, colonIdx)
                // SSE 仕様上、フィールド名の直後の値部分の先頭スペース 1 つは無視する
                var raw = line.substring(colonIdx + 1)
                if (raw.startsWith(" ")) raw = raw.substring(1)
                value = raw
            }

            when (field) {
                "data" -> {
                    if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                    dataBuffer.append(value)
                }
                "event" -> eventName = value
                // "id", "retry" 等は今回未対応
            }
        }

        // 終端で残っているメッセージを吐き出す
        if (dataBuffer.isNotEmpty() || eventName != null) {
            onMessage(eventName, dataBuffer.toString())
        }
    }

    /** インライン関数から private field にアクセスできないので accessor を挟む。 */
    fun internalSource(): BufferedSource? = source

    override fun close() {
        runCatching { response.close() }
    }
}
