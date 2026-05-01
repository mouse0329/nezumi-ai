package com.nezumi_ai.data.inference

fun String.stripGemmaTokens(): String {
    return Gemma4ThinkingParser.sanitizeVisibleText(this)
}
