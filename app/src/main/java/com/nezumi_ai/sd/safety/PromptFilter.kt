package com.nezumi_ai.sd.safety

import android.util.Log

object PromptFilter {

    private const val TAG = "PromptFilter"

    // 明確に性的・暴力的なキーワード（英語）
    // 追加が必要な場合はここに追記するだけでよい
    private val BLOCK_KEYWORDS: Set<String> = setOf(
        "naked", "nude", "nudity", "nsfw", "porn", "pornography",
        "pussy", "vagina", "penis", "cock", "dick", "boobs", "tits",
        "nipple", "nipples", "sex", "sexual", "hentai", "xxx",
        "blowjob", "handjob", "cumshot", "creampie", "gangbang",
        "orgasm", "erection", "genitals", "anus", "butthole",
        "uncensored", "explicit"
    )

    enum class Result { ALLOW, BLOCK }

    /**
     * プロンプトを検査して ALLOW / BLOCK を返す。
     * BLOCK の場合、MNNサーバーへのリクエストをキャンセルすることで
     * 約50秒の無駄なUNET演算を防止する。
     */
    fun check(prompt: String): Result {
        val lower = prompt.lowercase()
        val hit: String? = BLOCK_KEYWORDS.firstOrNull { lower.contains(it) }
        return if (hit != null) {
            Log.w(TAG, "Prompt blocked by keyword: \"$hit\"")
            Result.BLOCK
        } else {
            Result.ALLOW
        }
    }
}
