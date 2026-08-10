package com.nezumi_ai.data.inference

import android.util.Log
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private const val WEB_FETCH_TAG = "WebFetchTool"

// ─────────────────────────────────────────────
// ページ取得 (URL → Markdown) 実装
// ─────────────────────────────────────────────
//
// WebSearchTool (Brave Search API) で見つけた URL の中身を読むためのツール。
// 通信と HTML 解析は jsoup、HTML → Markdown 変換は flexmark-html2md で行う。
// JavaScript レンダリングは行わないため、SPA 等の動的ページでは本文が
// 取得できない場合がある。

private const val FETCH_TIMEOUT_MS = 20_000
private const val MAX_BODY_BYTES = 2L * 1024 * 1024
private const val MAX_MARKDOWN_CHARS = 12_000

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 nezumi-ai"

internal fun performWebFetch(url: String, maxChars: Int): Map<String, Any?> {
    val sanitizedUrl = url.trim()
    if (!sanitizedUrl.startsWith("http://") && !sanitizedUrl.startsWith("https://")) {
        return mapOf(
            "success" to false,
            "error" to "invalid_url",
            "url" to sanitizedUrl
        )
    }

    val response = try {
        Jsoup.connect(sanitizedUrl)
            .userAgent(USER_AGENT)
            .timeout(FETCH_TIMEOUT_MS)
            .maxBodySize(MAX_BODY_BYTES.toInt())
            .followRedirects(true)
            .ignoreContentType(true)
            .execute()
    } catch (e: Exception) {
        Log.e(WEB_FETCH_TAG, "Fetch failed: $sanitizedUrl", e)
        return mapOf(
            "success" to false,
            "error" to "fetch_failed:${e.message}",
            "url" to sanitizedUrl
        )
    }

    val contentType = response.contentType().orEmpty()
    if (contentType.isNotBlank() &&
        !contentType.startsWith("text/html") &&
        !contentType.startsWith("application/xhtml")
    ) {
        return mapOf(
            "success" to false,
            "error" to "unsupported_content_type:$contentType",
            "url" to response.url().toString()
        )
    }

    val document: Document = response.parse()
    val finalUrl = response.url().toString()
    val title = document.title().orEmpty()

    // ナビゲーションや広告など本文以外のノイズを変換前に取り除く
    document.select("script, style, noscript, nav, header, footer, aside, form, iframe").remove()

    val html = document.body()?.html() ?: document.html()
    val markdown = runCatching {
        FlexmarkHtmlConverter.builder().build().convert(html)
    }.getOrElse {
        Log.e(WEB_FETCH_TAG, "HTML to Markdown conversion failed: $finalUrl", it)
        return mapOf(
            "success" to false,
            "error" to "convert_failed:${it.message}",
            "url" to finalUrl
        )
    }

    val normalized = markdown
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    if (normalized.isEmpty()) {
        return mapOf(
            "success" to false,
            "error" to "empty_content",
            "url" to finalUrl
        )
    }

    val limit = maxChars.coerceIn(500, MAX_MARKDOWN_CHARS)
    val truncated = normalized.length > limit
    val body = if (truncated) normalized.substring(0, limit) else normalized

    return mapOf(
        "success" to true,
        "url" to finalUrl,
        "title" to title,
        "contentType" to contentType,
        "markdown" to body,
        "length" to body.length,
        "truncated" to truncated
    )
}
