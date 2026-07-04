package com.nezumi_ai.presentation.viewmodel

const val DEFAULT_SESSION_TITLE = "新しいチャット"

internal fun buildSessionTitle(userMessage: String, aiResponse: String): String {
    val source = sequenceOf(userMessage, aiResponse)
        .map { it.trim().replace("\n", " ") }
        .firstOrNull { it.isNotBlank() }
        ?: return DEFAULT_SESSION_TITLE
    val cleaned = source
        .replace(Regex("^[「『\"'\\s]+"), "")
        .replace(Regex("[」』\"'\\s]+$"), "")
        .replace(Regex("\\s+"), " ")
    val maxLen = 28
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen).trimEnd() + "..."
}
