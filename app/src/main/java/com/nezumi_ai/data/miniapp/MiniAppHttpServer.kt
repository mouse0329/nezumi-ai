package com.nezumi_ai.data.miniapp

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 仕様 v1.1 §7 Local HTTP Server。
 *
 * Mini App の Package を `http://127.0.0.1:<port>/miniapp/<appId>/` 配下で配信する
 * ループバック専用の極小 HTTP サーバー。依存追加を避けるため ServerSocket 直実装。
 *
 * - 127.0.0.1 にのみ bind（外部からアクセス不可）。
 * - Package は読み取り専用で配信のみ（§4）。書き込み系メソッドは 405。
 * - Path Traversal 防止: /miniapp/<appId>/ 正規化後に packageDir 外へ出る要求は 403。
 */
class MiniAppHttpServer private constructor(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    private var running = false

    val port: Int
        get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start(): Int {
        if (running && serverSocket?.isClosed == false) return port
        val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running = true
        executor.execute { acceptLoop(socket) }
        Log.i(TAG, "MiniApp HTTP server started on 127.0.0.1:${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute { runCatching { handle(client) }.onFailure { runCatching { client.close() } } }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed", e)
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        // "GET /miniapp/<appId>/index.html HTTP/1.1"
        val parts = requestLine.split(" ")
        if (parts.size < 2) { respondError(output, 400, "Bad Request"); return }
        val method = parts[0]
        // WebView は空白・日本語・括弧などをパーセントエンコードして要求する。
        // ファイル解決前にデコードしないと、同一 Package 内のアセットが 404 になる。
        val rawPath = Uri.decode(parts[1].substringBefore('?').substringBefore('#'))

        // ヘッダを読み捨てる
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }

        if (method != "GET" && method != "HEAD") {
            respondError(output, 405, "Method Not Allowed"); return
        }

        val prefix = "/miniapp/"
        if (!rawPath.startsWith(prefix)) {
            respondError(output, 404, "Not Found"); return
        }
        val rest = rawPath.removePrefix(prefix)
        val appId = rest.substringBefore('/')
        val relPath = rest.substringAfter('/', "").ifBlank {
            // /miniapp/<appId>/ → manifest の entry へ
            resolveEntry(appId) ?: run { respondError(output, 404, "Not Found"); return }
        }

        val store = MiniAppStore.get(context)
        if (!store.isInstalled(appId)) {
            respondError(output, 404, "App Not Installed"); return
        }
        val packageRoot = store.packageDir(appId).canonicalFile
        val target = File(packageRoot, relPath).canonicalFile
        // Path Traversal 防止
        if (target.path != packageRoot.path && !target.path.startsWith(packageRoot.path + File.separator)) {
            respondError(output, 403, "Forbidden"); return
        }
        if (!target.exists() || !target.isFile) {
            respondError(output, 404, "Not Found"); return
        }

        val bytes = target.readBytes()
        val mime = mimeTypeFor(target.name)
        val writer = PrintWriter(output, false)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: $mime\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("X-Content-Type-Options: nosniff\r\n")
        // サンドボックス: Mini App 配信物には厳格な CSP を既定付与（§33 Sandbox 層の一環）
        writer.print("Content-Security-Policy: default-src 'self' 'unsafe-inline' data: blob:; connect-src 'self' http://127.0.0.1:* ws://127.0.0.1:*\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        if (method != "HEAD") {
            output.write(bytes)
            output.flush()
        }
        runCatching { client.close() }
    }

    private fun resolveEntry(appId: String): String? =
        MiniAppStore.get(context).get(appId)?.manifest?.entry

    private fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "")
        return when (ext.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                ?: "application/octet-stream"
        }
    }

    private fun respondError(output: java.io.OutputStream, code: Int, message: String) {
        val body = "$code $message".toByteArray()
        val writer = PrintWriter(output, false)
        writer.print("HTTP/1.1 $code $message\r\n")
        writer.print("Content-Type: text/plain; charset=utf-8\r\n")
        writer.print("Content-Length: ${body.size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        runCatching { output.write(body); output.flush() }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString()
            if (b == '\n'.code) {
                return out.toString().removeSuffix("\r")
            }
            if (prev == '\r'.code && b == '\n'.code) break
            out.write(b)
            prev = b
        }
        return out.toString()
    }

    companion object {
        private const val TAG = "MiniAppHttpServer"

        @Volatile
        private var instance: MiniAppHttpServer? = null

        fun get(context: Context): MiniAppHttpServer =
            instance ?: synchronized(this) {
                instance ?: MiniAppHttpServer(context.applicationContext).also { instance = it }
            }
    }
}
