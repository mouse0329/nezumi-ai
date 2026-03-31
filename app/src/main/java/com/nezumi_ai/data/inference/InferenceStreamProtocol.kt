package com.nezumi_ai.data.inference

object InferenceStreamProtocol {
    private const val FINAL_PREFIX = "\u0000__FINAL__\u0000"

    fun encodeFinal(fullText: String): String = FINAL_PREFIX + fullText

    fun decodeFinal(chunk: String): String? {
        if (!chunk.startsWith(FINAL_PREFIX)) return null
        return chunk.removePrefix(FINAL_PREFIX)
    }
}
