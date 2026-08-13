package com.nezumi_ai.data.inference

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.nezumi_ai.CurrentActivityHolder
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume

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

// ─────────────────────────────────────────────
// ページ取得 (URL → JS実行 → Markdown) 実装
// ─────────────────────────────────────────────
//
// 設定で「JavaScript を実行してから取得」がONの場合に使う版。
// AndroidのWebView（メインスレッド専用）でページを描画してからDOMを取り出し、
// 以降は上のperformWebFetchと同じjsoup + flexmarkパイプラインでMarkdown化する。
// SPA等の動的ページにも対応できるが、WebView起動分だけ取得に時間がかかる。

private const val JS_RENDER_TIMEOUT_MS = 15_000L
// ページ側スクリプトによる遅延描画を少し待つための追加時間
private const val SETTLE_DELAY_MS = 800L

internal suspend fun performWebFetchWithJs(
    context: Context,
    url: String,
    maxChars: Int
): Map<String, Any?> {
    val sanitizedUrl = url.trim()
    if (!sanitizedUrl.startsWith("http://") && !sanitizedUrl.startsWith("https://")) {
        return mapOf("success" to false, "error" to "invalid_url", "url" to sanitizedUrl)
    }

    val rawHtml = try {
        withTimeoutOrNull(JS_RENDER_TIMEOUT_MS) {
            renderPageHtml(context, sanitizedUrl)
        }
    } catch (e: Exception) {
        Log.e(WEB_FETCH_TAG, "JS render failed: $sanitizedUrl", e)
        null
    }

    if (rawHtml.isNullOrBlank()) {
        return mapOf("success" to false, "error" to "js_render_timeout_or_failed", "url" to sanitizedUrl)
    }

    val document = Jsoup.parse(rawHtml, sanitizedUrl)
    val title = document.title().orEmpty()
    document.select("script, style, noscript, nav, header, footer, aside, form, iframe").remove()
    val bodyHtml = document.body()?.html() ?: document.html()

    val markdown = runCatching {
        FlexmarkHtmlConverter.builder().build().convert(bodyHtml)
    }.getOrElse {
        Log.e(WEB_FETCH_TAG, "Markdown conversion failed: $sanitizedUrl", it)
        return mapOf("success" to false, "error" to "convert_failed:${it.message}", "url" to sanitizedUrl)
    }

    val normalized = markdown.replace(Regex("\n{3,}"), "\n\n").trim()
    if (normalized.isEmpty()) {
        return mapOf("success" to false, "error" to "empty_content", "url" to sanitizedUrl)
    }

    val limit = maxChars.coerceIn(500, MAX_MARKDOWN_CHARS)
    val truncated = normalized.length > limit
    val body = if (truncated) normalized.substring(0, limit) else normalized

    return mapOf(
        "success" to true,
        "url" to sanitizedUrl,
        "title" to title,
        "markdown" to body,
        "length" to body.length,
        "truncated" to truncated,
        "jsRendered" to true
    )
}

@SuppressLint("SetJavaScriptEnabled")
private suspend fun renderPageHtml(context: Context, url: String): String =
    withContext(Dispatchers.Main) {
        // context (呼び出し元では applicationContext) は WebView の生成には使わない。
        // WebView は Activity Context (かつ実際の View 階層にアタッチされた状態) が必要なため、
        // 下記で CurrentActivityHolder から取得した Activity を使う。
        // WebView は真にウィンドウの View 階層にアタッチされていないと、
        // evaluateJavascript のコールバックがコンポジタのフレーム生成待ちで
        // 永遠に返らないことがある (Chromium の既知の制約)。
        // measure/layout を手動で呼ぶだけでは実際の Surface 合成は起きないため、
        // ここでは CurrentActivityHolder 経由でフォアグラウンドの Activity を取得し、
        // その DecorView に 1x1px で実際に addView した上でロードする。
        // Activity が取得できない (バックグラウンド実行/画面遷移中など) 場合は、
        // 表示できたとしてもレンダリングが進まない可能性が高いためすぐに失敗として返す。
        val activity = CurrentActivityHolder.get()
        if (activity == null) {
            Log.w(WEB_FETCH_TAG, "No foreground Activity available; cannot attach WebView for JS rendering")
            return@withContext ""
        }
        val decorView = activity.window?.decorView as? ViewGroup
        if (decorView == null) {
            Log.w(WEB_FETCH_TAG, "Activity has no decorView; cannot attach WebView for JS rendering")
            return@withContext ""
        }

        suspendCancellableCoroutine { cont ->
            val webView = WebView(activity)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            // 1x1px かつ透過で画面には見えない状態にしつつ、実際の View 階層に
            // アタッチすることで Chromium のレンダリングパイプラインを正しく駆動させる。
            webView.alpha = 0f

            val layoutParams = FrameLayout.LayoutParams(1, 1)

            var handled = false
            var attached = false

            fun detachAndDestroy() {
                if (attached) {
                    runCatching { decorView.removeView(webView) }
                    attached = false
                }
                webView.destroy()
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    Log.d(WEB_FETCH_TAG, "onPageFinished: $finishedUrl (target=$url, handled=$handled)")
                    if (handled) return
                    // メインフレームの完了だけを最終確定として扱う。
                    // リダイレクトを考慮し、末尾スラッシュの有無程度の差は許容する。
                    val normalizedFinished = finishedUrl.trimEnd('/')
                    val normalizedTarget = url.trimEnd('/')
                    if (normalizedFinished != normalizedTarget && !normalizedFinished.startsWith(normalizedTarget)) {
                        // サブフレーム、または別ドメインへのリダイレクト途中の可能性が高いので待つ。
                        return
                    }
                    handled = true
                    // SPAの遅延レンダリングを考慮し、少し待ってからDOMを取り出す
                    view.postDelayed({
                        view.evaluateJavascript("document.documentElement.outerHTML") { rawResult ->
                            val html = unescapeJsStringResult(rawResult)
                            Log.d(WEB_FETCH_TAG, "evaluateJavascript result length=${html.length}")
                            if (cont.isActive) cont.resume(html)
                            detachAndDestroy()
                        }
                    }, SETTLE_DELAY_MS)
                }

                // 新しい (WebResourceRequest 版) onReceivedError。
                // 広告/トラッキングスクリプトなどサブリソースの読み込み失敗でも
                // 呼ばれるため、isForMainFrame でメインフレームの失敗だけを
                // 致命的エラーとして扱う。判定しないと、ニュースサイト等で
                // 広告タグの1つが失敗しただけでページ全体が失敗扱いになり、
                // web_fetch (JS描画版) が実質常にタイムアウトしてしまう不具合になる。
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (!request.isForMainFrame) {
                        // サブリソース (広告/トラッカー/iframe 等) の失敗は無視して継続する。
                        Log.d(
                            WEB_FETCH_TAG,
                            "Ignoring sub-resource load error: ${request.url} " +
                                "code=${error.errorCode} desc=${error.description}"
                        )
                        return
                    }
                    if (handled) return
                    handled = true
                    Log.e(
                        WEB_FETCH_TAG,
                        "Main frame load error: ${request.url} " +
                            "code=${error.errorCode} desc=${error.description}"
                    )
                    if (cont.isActive) cont.cancel()
                    detachAndDestroy()
                }
            }

            cont.invokeOnCancellation {
                webView.post { detachAndDestroy() }
            }

            decorView.addView(webView, layoutParams)
            attached = true
            webView.loadUrl(url)
        }
    }

// evaluateJavascriptはJSON文字列（ダブルクォート＋エスケープ）で返ってくるためデコードする
private fun unescapeJsStringResult(raw: String?): String {
    if (raw == null || raw == "null") return ""
    val trimmed = raw.trim().removeSurrounding("\"")
    return trimmed
        .replace("\\u003C", "<")
        .replace("\\u003E", ">")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}
