package com.nezumi_ai

import com.nezumi_ai.data.inference.stripGemmaTokens

/**
 * iOS シェル (iosApp) が shared.framework を本当にリンクしているか確認するための入口。
 * 画面の本番ロジックは置かない。
 */
object NezumiIosShell {
    fun title(): String = "ネズミAI"

    fun sanitizePreview(text: String): String = text.stripGemmaTokens()
}
