package com.nezumi_ai.data.inference.cloud

import android.util.Log

/** Android 実装: android.util.Log にそのまま委譲する。 */
actual object CloudLog {
    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}
