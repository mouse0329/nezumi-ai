package com.nezumi_ai.data.inference.cloud

/** commonMain からプラットフォームロギングを呼ぶ最小ファサード。 */
expect object CloudLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
