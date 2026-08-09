package com.nezumi_ai.presentation.viewmodel

const val DEFAULT_SESSION_TITLE = "新しいチャット"

internal fun buildSessionTitle(userMessage: String, aiResponse: String): String {
    val source = sequenceOf(userMessage, aiResponse)
        // モデル向けの埋め込みタグ (テキスト/ドキュメント添付の <txtfile>、動画メタの
        // <video>) はタイトルに採用しない。ここで剥がしてから先頭の実質本文を選ぶ。
        .map {
            it.stripModelInjectionBlocks()
                .trim()
                .replace("\n", " ")
        }
        .firstOrNull { it.isNotBlank() }
        ?: return DEFAULT_SESSION_TITLE
    val cleaned = source
        .replace(Regex("^[「『\"'\\s]+"), "")
        .replace(Regex("[」』\"'\\s]+$"), "")
        .replace(Regex("\\s+"), " ")
    val maxLen = 28
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen).trimEnd() + "..."
}

/**
 * モデル向けにプロンプトへ埋め込まれたブロックタグ
 * (<txtfile> / <docfile> / <video>) をまとめて除去する。
 * タイトル生成など「ユーザーが書いた実質本文」だけが欲しい経路で使う。
 */
internal fun String.stripModelInjectionBlocks(): String {
    return this
        .replace(Regex("<txtfile>.*?</txtfile>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<docfile>.*?</docfile>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<video>.*?</video>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
}
