package com.nezumi_ai.data.inference.cloud

/** TODO(ios): OSLog/NSLog ブリッジに置き換える。フェーズ1では println に逃がす。 */
actual object CloudLog {
    actual fun d(tag: String, message: String) { println("D/$tag: $message") }
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        println("W/$tag: $message${throwable?.let { " (${it.message})" } ?: ""}")
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        println("E/$tag: $message${throwable?.let { " (${it.message})" } ?: ""}")
    }
}
