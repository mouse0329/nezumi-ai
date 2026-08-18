package com.nezumi_ai.data.inference.cloud

/**
 * iOS 実装。フェーズ1 では NSLog 相当の本実装は行わず、println に逃がす。
 * TODO(ios): OSLog / NSLog ブリッジに置き換える。
 */
actual object CloudLog {
    actual fun d(tag: String, message: String) {
        println("D/$tag: $message")
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        println("W/$tag: $message${throwable?.let { " (${it.message})" } ?: ""}")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        println("E/$tag: $message${throwable?.let { " (${it.message})" } ?: ""}")
    }
}
