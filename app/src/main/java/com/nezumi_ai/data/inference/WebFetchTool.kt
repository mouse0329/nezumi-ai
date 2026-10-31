package com.nezumi_ai.data.inference

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nezumi_ai.CurrentActivityHolder
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
// WebView は画面の View 階層に載っていないと evaluateJavascript の
// コールバックがコンポジタ待ちで返らない (Chromium の制約)。
// ただし 1x1px だと SPA のレイアウトが走らず本文が空になるため、
// 実画面サイズの WebView を画面外へずらして載せる。
// 本文は evaluateJavascript の戻り値（Binder の約1MB制限・JSONエスケープ）
// ではなく JavascriptInterface で分割して受け取る。
// 描画に失敗した、または本文が極端に短いときは jsoup の静的取得へ戻す。

private const val JS_RENDER_TIMEOUT_MS = 22_000L
private const val FETCH_PROFILE = "nezumi_web_fetch"
private const val MAX_DUMP_CHARS = 350_000
private const val DUMP_CHUNK_CHARS = 50_000
private const val MIN_USEFUL_MARKDOWN = 200

/** メインフレームの onPageFinished だけを採用する。要求URLとの一致は見ない（http→https 等のリダイレクトを取りこぼすため）。 */
internal fun isMainFramePageFinished(finishedUrl: String?, viewUrl: String?): Boolean {
    if (finishedUrl.isNullOrBlank() || viewUrl.isNullOrBlank()) return false
    if (finishedUrl == "about:blank" || viewUrl == "about:blank") return false
    return finishedUrl == viewUrl
}

/** JavascriptInterface で届いたチャンクを結合する。欠けていれば null。 */
internal fun joinChunks(total: Int, chunks: Map<Int, String>): String? {
    if (total <= 0 || total > 32 || chunks.size < total) return null
    return buildString {
        for (index in 0 until total) {
            append(chunks[index] ?: return null)
        }
    }
}

/**
 * JS描画結果が十分ならそれを使う。短い・失敗のときは、より長い静的取得があればそちらへ戻す。
 */
internal fun chooseJsOrStatic(
    js: Map<String, Any?>?,
    staticResult: Map<String, Any?>
): Map<String, Any?> {
    val jsLength = (js?.get("length") as? Int) ?: 0
    if (js?.get("success") == true && jsLength >= MIN_USEFUL_MARKDOWN) return js
    val staticLength = (staticResult["length"] as? Int) ?: 0
    if (staticResult["success"] == true && staticLength >= jsLength) {
        return staticResult + mapOf<String, Any?>(
            "jsRendered" to false,
            "jsFallback" to true
        )
    }
    if (js?.get("success") == true) return js
    return staticResult + mapOf<String, Any?>("jsFallback" to true)
}

internal fun pageDumpScript(token: String): String {
    require(token.matches(Regex("[0-9a-f]{8,64}"))) { "invalid_dump_token" }
    return """
        (function() {
          if (window.__nezumiFetchStarted) return;
          window.__nezumiFetchStarted = true;
          var token = "$token";
          var last = -1;
          var stable = 0;
          var ticks = 0;
          function len() {
            var b = document.body;
            return b ? (b.innerText || "").length : 0;
          }
          function deliver() {
            if (!window.NezumiFetch) return;
            var nodes = document.querySelectorAll("script,style,noscript,svg,iframe");
            for (var i = 0; i < nodes.length; i++) nodes[i].remove();
            var html = document.documentElement ? document.documentElement.outerHTML : "";
            var cap = $MAX_DUMP_CHARS;
            if (html.length > cap) html = html.substring(0, cap);
            var size = $DUMP_CHUNK_CHARS;
            var total = Math.max(1, Math.ceil(html.length / size));
            NezumiFetch.begin(token, total);
            for (var c = 0; c < total; c++) {
              NezumiFetch.chunk(token, c, html.substring(c * size, (c + 1) * size));
            }
          }
          function tick() {
            ticks++;
            var n = len();
            if (n > 40 && n === last) stable++; else stable = 0;
            last = n;
            if ((stable >= 2 && n > 40) || ticks >= 10) deliver();
            else setTimeout(tick, 400);
          }
          setTimeout(tick, 400);
        })();
    """.trimIndent()
}

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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(WEB_FETCH_TAG, "JS render failed: $sanitizedUrl", e)
        null
    }

    val jsResult = if (rawHtml.isNullOrBlank()) {
        null
    } else {
        withContext(Dispatchers.Default) {
            markdownFromRenderedHtml(rawHtml, sanitizedUrl, maxChars)
        }
    }
    val jsLength = (jsResult?.get("length") as? Int) ?: 0
    if (jsResult?.get("success") == true && jsLength >= MIN_USEFUL_MARKDOWN) {
        return jsResult
    }

    Log.w(WEB_FETCH_TAG, "JS render unusable (length=$jsLength); falling back to static fetch: $sanitizedUrl")
    val staticResult = withContext(Dispatchers.IO) {
        performWebFetch(sanitizedUrl, maxChars)
    }
    return chooseJsOrStatic(jsResult, staticResult)
}

private fun markdownFromRenderedHtml(
    rawHtml: String,
    url: String,
    maxChars: Int
): Map<String, Any?> {
    val document = Jsoup.parse(rawHtml, url)
    val title = document.title().orEmpty()
    document.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove()
    val bodyHtml = document.body()?.html() ?: document.html()
    val markdown = runCatching {
        FlexmarkHtmlConverter.builder().build().convert(bodyHtml)
    }.getOrElse {
        Log.e(WEB_FETCH_TAG, "Markdown conversion failed: $url", it)
        return mapOf("success" to false, "error" to "convert_failed:${it.message}", "url" to url)
    }
    val normalized = markdown.replace(Regex("\n{3,}"), "\n\n").trim()
    if (normalized.isEmpty()) {
        return mapOf("success" to false, "error" to "empty_content", "url" to url)
    }
    val limit = maxChars.coerceIn(500, MAX_MARKDOWN_CHARS)
    val truncated = normalized.length > limit
    val body = if (truncated) normalized.substring(0, limit) else normalized
    return mapOf(
        "success" to true,
        "url" to url,
        "title" to title,
        "markdown" to body,
        "length" to body.length,
        "truncated" to truncated,
        "jsRendered" to true
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("UNUSED_PARAMETER")
private suspend fun renderPageHtml(context: Context, url: String): String =
    withContext(Dispatchers.Main) {
        // applicationContext だけでは WebView のコンポジタがフレームを出さない。
        // フォアグラウンド Activity の DecorView に、実寸・画面外で載せる。
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
            applyFetchProfile(webView)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = USER_AGENT
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
            }

            val metrics = activity.resources.displayMetrics
            val width = metrics.widthPixels.coerceIn(360, 1080)
            val height = metrics.heightPixels.coerceIn(640, 1920)
            webView.isClickable = false
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            // 実寸のまま画面外へ出す。alpha=0 や 1x1 はフレームが出ず、SPA も空になる。
            webView.translationX = -width * 3f
            webView.translationY = -height * 3f

            val resumed = AtomicBoolean(false)
            val cleaned = AtomicBoolean(false)
            val dumpArmed = AtomicBoolean(false)

            fun destroyWebView() {
                if (!cleaned.compareAndSet(false, true)) return
                webView.post {
                    runCatching { webView.removeJavascriptInterface("NezumiFetch") }
                    runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                    webView.post { runCatching { webView.destroy() } }
                }
            }

            fun finish(html: String) {
                if (!resumed.compareAndSet(false, true)) return
                if (cont.isActive) {
                    runCatching { cont.resume(html) }
                }
                destroyWebView()
            }

            val token = UUID.randomUUID().toString().replace("-", "")
            webView.addJavascriptInterface(
                PageDumpBridge(token) { html ->
                    Log.d(WEB_FETCH_TAG, "JS dump length=${html.length}")
                    webView.post { finish(html) }
                },
                "NezumiFetch"
            )

            webView.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult
                ): Boolean {
                    result.cancel()
                    return true
                }

                override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.confirm()
                    return true
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, false, false)
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    Log.w(WEB_FETCH_TAG, "Blocked non-http navigation: ${request.url}")
                    return true
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (!isMainFramePageFinished(finishedUrl, view.url)) {
                        Log.d(WEB_FETCH_TAG, "Ignoring non-main onPageFinished: $finishedUrl view=${view.url}")
                        return
                    }
                    if (!dumpArmed.compareAndSet(false, true)) return
                    Log.d(WEB_FETCH_TAG, "Main frame finished: $finishedUrl (requested=$url)")
                    view.evaluateJavascript(pageDumpScript(token), null)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (!request.isForMainFrame) {
                        Log.d(
                            WEB_FETCH_TAG,
                            "Ignoring sub-resource load error: ${request.url} " +
                                "code=${error.errorCode} desc=${error.description}"
                        )
                        return
                    }
                    if (!dumpArmed.compareAndSet(false, true)) return
                    Log.e(
                        WEB_FETCH_TAG,
                        "Main frame load error: ${request.url} " +
                            "code=${error.errorCode} desc=${error.description}"
                    )
                    finish("")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    if (!dumpArmed.compareAndSet(false, true)) return
                    Log.e(WEB_FETCH_TAG, "SSL error: ${error.url} primary=${error.primaryError}")
                    finish("")
                }
            }

            cont.invokeOnCancellation {
                webView.post { destroyWebView() }
            }

            val attached = runCatching {
                decorView.addView(webView, FrameLayout.LayoutParams(width, height))
            }.onFailure {
                Log.e(WEB_FETCH_TAG, "Failed to attach WebView", it)
            }.isSuccess
            if (!attached) {
                finish("")
            } else {
                webView.loadUrl(url)
            }
        }
    }

private fun applyFetchProfile(webView: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
    runCatching {
        ProfileStore.getInstance().getOrCreateProfile(FETCH_PROFILE)
        WebViewCompat.setProfile(webView, FETCH_PROFILE)
    }.onFailure {
        Log.w(WEB_FETCH_TAG, "Isolated WebView profile unavailable", it)
    }
}

/**
 * 取得ページから本文 HTML を受け取る。トークンが一致しない呼び出しは捨てる。
 * Cookie はこのブリッジに載せず、プロファイル分離と合わせてアプリ本体へ漏れないようにする。
 */
internal class PageDumpBridge(
    private val token: String,
    private val onDump: (String) -> Unit
) {
    private val chunks = HashMap<Int, String>()
    private var total = -1
    private val done = AtomicBoolean(false)

    @JavascriptInterface
    fun begin(gotToken: String, count: Int) {
        if (gotToken != token || count <= 0 || count > 32) return
        synchronized(this) {
            if (total >= 0) return
            total = count
        }
        finishIfComplete()
    }

    @JavascriptInterface
    fun chunk(gotToken: String, index: Int, text: String) {
        if (gotToken != token || index < 0 || index >= 32) return
        if (text.length > DUMP_CHUNK_CHARS + 1024) return
        synchronized(this) { chunks[index] = text }
        finishIfComplete()
    }

    private fun finishIfComplete() {
        val html = synchronized(this) { joinChunks(total, chunks) } ?: return
        if (done.compareAndSet(false, true)) onDump(html)
    }
}

