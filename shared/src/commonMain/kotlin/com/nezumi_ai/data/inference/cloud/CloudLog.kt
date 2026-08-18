package com.nezumi_ai.data.inference.cloud

/**
 * commonMain からプラットフォームのロギング (Android では android.util.Log) を
 * 呼ぶための最小ファサード。既存コードのタグ・レベル・メッセージを維持する。
 */
expect object CloudLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
