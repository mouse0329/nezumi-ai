package com.nezumi_ai.presentation.ui.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.miniapp.MiniAppEventBus
import com.nezumi_ai.data.miniapp.MiniAppHttpServer
import com.nezumi_ai.data.miniapp.MiniAppJsBridge
import com.nezumi_ai.data.miniapp.MiniAppPermissionManager
import com.nezumi_ai.data.miniapp.MiniAppRpcDispatcher
import com.nezumi_ai.data.miniapp.MiniAppRuntimeContext
import com.nezumi_ai.data.miniapp.MiniAppStore
import com.nezumi_ai.presentation.ui.theme.NezumiComposeTheme

/**
 * installed Mini App の実行画面（§3: installed のみ実行可能）。
 *
 * - Package は [MiniAppHttpServer] 経由 `http://127.0.0.1:<port>/miniapp/<appId>/` で配信（§7）。
 * - Native API は [MiniAppRpcDispatcher] + [MiniAppJsBridge] 経由のみ（§8/§10）。
 * - 画面本体は Compose（WebView は AndroidView で包む）。Fragment は名前だけで中身は Compose。
 */
class MiniAppRunnerFragment : Fragment() {

    private var dispatcher: MiniAppRpcDispatcher? = null
    private var bridge: MiniAppJsBridge? = null
    private var webView: WebView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                MiniAppRunnerScreen(
                    fragment = this@MiniAppRunnerFragment,
                    appId = arguments?.getString("appId").orEmpty(),
                    onClose = { findNavController().popBackStack() }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // §18: runtime 終了で Mini App Tool を自動削除
        dispatcher?.destroy()
        dispatcher = null
        bridge = null
        webView?.destroy()
        webView = null
    }

    /** ランタイム WebView を構築する。アプリ未インストールなら null（APP_NOT_INSTALLED）。 */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun buildRuntimeWebView(appId: String, onClose: () -> Unit): WebView? {
        val ctx = context ?: return null
        val store = MiniAppStore.get(ctx)
        val app = store.get(appId) ?: return null
        val manifest = app.manifest

        val runtime = MiniAppRuntimeContext(
            appId = manifest.id,
            appVersion = manifest.version
        )
        val eventBus = MiniAppEventBus()
        val disp = MiniAppRpcDispatcher(
            context = ctx.applicationContext,
            runtime = runtime,
            manifest = manifest,
            eventBus = eventBus,
            closeCallback = { view?.post { onClose() } }
        )
        dispatcher = disp

        val port = MiniAppHttpServer.get(ctx).start()
        val url = "http://127.0.0.1:$port/miniapp/${manifest.id}/"

        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // サンドボックス（§33）: Package 内リソースはローカル HTTP 経由のみに限定
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
        }
        webView = wv

        val br = MiniAppJsBridge(wv, disp)
        bridge = br
        eventBus.add(object : MiniAppEventBus.Listener {
            override fun onEvent(event: String, payloadJson: String) {
                br.emitEvent(event, payloadJson)
            }
        })
        wv.addJavascriptInterface(br, "__nezumiBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 仕様 §14: nezumi SDK 名前空間をページスクリプトより先に注入
                view?.evaluateJavascript(MiniAppJsBridge.SDK_JS, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // サンドボックス（§33）: ローカル配信オリジン以外への遷移は禁止
                val u = request?.url ?: return true
                return !(u.host == "127.0.0.1" && u.port == port)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val u = request?.url ?: return null
                if (u.host != "127.0.0.1" || u.port != port) return null

                val appPrefix = "/miniapp/${manifest.id}/"
                if (u.path?.startsWith(appPrefix) != false) return null

                // Vite/Manus 等のビルド成果物は /assets/... を使うことがある。
                // Mini App の Package 境界内へ解決し、外部ホストへは転送しない。
                val packageUrl = "http://127.0.0.1:$port$appPrefix${u.path.orEmpty().removePrefix("/")}"
                return runCatching {
                    val connection = (URL(packageUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }
                    WebResourceResponse(
                        connection.contentType?.substringBefore(';') ?: "application/octet-stream",
                        connection.contentEncoding ?: "UTF-8",
                        connection.inputStream
                    )
                }.getOrNull()
            }
        }

        // §13 カメラ・マイク: getUserMedia → WebView Permission → Manifest 宣言チェック
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request ?: return
                val pm = MiniAppPermissionManager.get(ctx)
                val approved = request.resources.all { res ->
                    when (res) {
                        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            manifest.permissions.contains("camera") && pm.isGranted(manifest, "camera")
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            manifest.permissions.contains("microphone") && pm.isGranted(manifest, "microphone")
                        else -> false
                    }
                }
                if (approved) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        wv.loadUrl(url)
        return wv
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniAppRunnerScreen(
    fragment: MiniAppRunnerFragment,
    appId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val appName = remember(appId) {
        MiniAppStore.get(context).get(appId)?.manifest?.name ?: appId
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(appName, color = colorResource(R.color.text_primary))
                },
                navigationIcon = {
                    // 他画面（SettingsComposeFragment 等）と同じ ic_back アイコンに統一
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.back),
                            tint = colorResource(R.color.text_primary)
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    fragment.buildRuntimeWebView(appId, onClose) ?: WebView(ctx).also { wv ->
                        // APP_NOT_INSTALLED: 仕様 §34 のエラーを簡易表示
                        wv.settings.javaScriptEnabled = false
                        wv.loadData(
                            "<html><body><h3>APP_NOT_INSTALLED</h3></body></html>",
                            "text/html; charset=utf-8",
                            "utf-8"
                        )
                    }
                }
            )
        }
    }
}
