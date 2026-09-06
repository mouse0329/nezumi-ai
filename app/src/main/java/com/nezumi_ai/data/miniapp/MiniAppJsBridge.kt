package com.nezumi_ai.data.miniapp

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.util.Log

/**
 * WebView ↔ RPC Dispatcher の橋渡し。
 *
 * - JS 側からは `window.__nezumiBridge.postMessage(json)` で RPC を受け取る。
 * - 応答は `window.__nezumiOnRpcResponse(json)` / `window.__nezumiOnStream(...)` /
 *   `window.__nezumiOnEvent(...)` へ JS 側コールバックとして返す。
 * - WebView 側には仕様 §14 の `nezumi` SDK 名前空間を注入する。
 */
class MiniAppJsBridge(
    private val webView: WebView,
    private val dispatcher: MiniAppRpcDispatcher
) : MiniAppRpcDispatcher.ResultSink {

    init {
        dispatcher.sink = this
    }

    /** JS → Native。RPC リクエストを受け取る。 */
    @JavascriptInterface
    fun postMessage(requestJson: String) {
        dispatcher.dispatch(requestJson)
    }

    // ----- ResultSink（Native → JS） -----

    override fun onResult(id: Long, responseJson: String) {
        evaluate("window.__nezumiOnRpcResponse($responseJson);")
    }

    override fun onStreamChunk(requestId: String, chunkJson: String, done: Boolean) {
        evaluate("window.__nezumiOnStream($chunkJson, $done);")
    }

    fun emitEvent(event: String, payloadJson: String) {
        evaluate("window.__nezumiOnEvent(${org.json.JSONObject.quote(event)}, $payloadJson);")
    }

    private fun evaluate(js: String) {
        webView.post {
            runCatching { webView.evaluateJavascript(js, null) }
                .onFailure { Log.w(TAG, "evaluateJavascript failed", it) }
        }
    }

    companion object {
        private const val TAG = "MiniAppJsBridge"

        /**
         * 仕様 §14 の nezumi SDK 名前空間を構成する JS。
         * WebView ロード前に [WebView.evaluateJavascript] または
         * [androidx.webkit.WebViewCompat.addDocumentStartJavaScript] で注入する。
         */
        val SDK_JS: String = """
(function () {
  if (window.nezumi) return;
  var __rpcId = 0;
  var __pending = {};
  var __streamCallbacks = {};
  var __eventListeners = {};

  function __call(method, params) {
    return new Promise(function (resolve, reject) {
      var id = ++__rpcId;
      __pending[id] = { resolve: resolve, reject: reject };
      window.__nezumiBridge.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
    });
  }

  // ストリーム専用: requestId(=RPC id)を先に確定させ、送信前にコールバックを登録する
  function __callStream(method, params, callback) {
    return new Promise(function (resolve, reject) {
      var id = ++__rpcId;
      var requestId = String(id);
      if (callback) __streamCallbacks[requestId] = callback;
      __pending[id] = {
        resolve: function (r) { resolve(r); },
        reject: function (e) { delete __streamCallbacks[requestId]; reject(e); }
      };
      window.__nezumiBridge.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
    });
  }

  window.__nezumiOnRpcResponse = function (res) {
    var p = __pending[res.id];
    if (!p) return;
    delete __pending[res.id];
    if (res.ok) { p.resolve(res.result); }
    else { var e = new Error(res.error && res.error.message || 'rpc error'); e.code = res.error && res.error.code; p.reject(e); }
  };

  window.__nezumiOnStream = function (chunk, done) {
    var cb = __streamCallbacks[chunk.requestId];
    if (cb) { cb(chunk.delta || '', done); if (done) delete __streamCallbacks[chunk.requestId]; }
  };

  window.__nezumiOnEvent = function (event, payload) {
    var list = __eventListeners[event] || [];
    list.forEach(function (cb) { try { cb(payload); } catch (e) {} });
  };

  function base64ToArrayBuffer(b64) {
    var bin = atob(b64);
    var len = bin.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
    return bytes.buffer;
  }
  function arrayBufferToBase64(buf) {
    var bytes = new Uint8Array(buf);
    var bin = '';
    for (var i = 0; i < bytes.byteLength; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
  }

  window.nezumi = {
    app: {
      getInfo: function () { return __call('app.getInfo'); },
      getRuntimeInfo: function () { return __call('app.getRuntimeInfo'); },
      getHostInfo: function () { return __call('app.getHostInfo'); },
      close: function () { return __call('app.close'); }
    },
    permissions: {
      list: function () { return __call('permissions.list').then(function (r) { return r.permissions; }); },
      get: function (name) { return __call('permissions.get', { name: name }).then(function (r) { return r.state; }); },
      request: function (name) { return __call('permissions.request', { name: name }).then(function (r) { return r.state; }); }
    },
    ai: {
      listModels: function () { return __call('ai.listModels').then(function (r) { return r.models; }); },
      loadModel: function (options) { return __call('ai.loadModel', options); },
      generate: function (options) { return __call('ai.generate', options); },
      stream: function (options, callback) {
        return __callStream('ai.stream', options, callback);
      },
      stop: function (requestId) { return __call('ai.stop', { requestId: requestId }); }
    },
    tools: {
      list: function () { return __call('tools.list').then(function (r) { return r.tools; }); },
      call: function (name, args) { return __call('tools.call', { name: name, args: args || {} }); },
      register: function (tool) { return __call('tools.register', tool); }
    },
    mcp: {
      listServers: function () { return __call('mcp.listServers').then(function (r) { return r.servers; }); },
      listTools: function (serverId) { return __call('mcp.listTools', { serverId: serverId }).then(function (r) { return r.tools; }); }
    },
    models: {
      list: function () { return __call('models.list').then(function (r) { return r.models; }); },
      get: function (id) { return __call('models.get', { id: id }); },
      exists: function (id) { return __call('models.exists', { id: id }).then(function (r) { return r.exists; }); }
    },
    engines: {
      list: function () { return __call('engines.list').then(function (r) { return r.engines; }); },
      listBackends: function (engineId) { return __call('engines.listBackends', { engineId: engineId }).then(function (r) { return r.backends; }); },
      probeMemory: function (modelId) { return __call('engines.probeMemory', { modelId: modelId }); }
    },
    device: {
      getInfo: function () { return __call('device.getInfo'); },
      getMemoryInfo: function () { return __call('device.getMemoryInfo'); }
    },
    storage: {
      get: function (key) { return __call('storage.get', { key: key }).then(function (r) { return r.value === null ? null : r.value; }); },
      set: function (key, value) { return __call('storage.set', { key: key, value: value }); },
      has: function (key) { return __call('storage.has', { key: key }).then(function (r) { return r.exists; }); },
      delete: function (key) { return __call('storage.delete', { key: key }); },
      keys: function () { return __call('storage.keys').then(function (r) { return r.keys; }); },
      clear: function () { return __call('storage.clear'); },
      getUsage: function () { return __call('storage.getUsage'); }
    },
    files: {
      list: function (path) { return __call('files.list', { path: path }).then(function (r) { return r.entries; }); },
      exists: function (path) { return __call('files.exists', { path: path }).then(function (r) { return r.exists; }); },
      read: function (path) { return __call('files.read', { path: path }).then(function (r) { return base64ToArrayBuffer(r.data); }); },
      readRange: function (path, offset, length) { return __call('files.readRange', { path: path, offset: offset, length: length }).then(function (r) { return base64ToArrayBuffer(r.data); }); },
      readText: function (path) { return __call('files.read', { path: path }).then(function (r) { return new TextDecoder().decode(base64ToArrayBuffer(r.data)); }); },
      write: function (path, data) {
        var b64 = (typeof data === 'string') ? btoa(unescape(encodeURIComponent(data))) : arrayBufferToBase64(data);
        return __call('files.write', { path: path, data: b64 });
      },
      delete: function (path) { return __call('files.delete', { path: path }); },
      stat: function (path) { return __call('files.stat', { path: path }); }
    },
    events: {
      on: function (event, callback) {
        (__eventListeners[event] = __eventListeners[event] || []).push(callback);
        return function () {
          var list = __eventListeners[event] || [];
          var i = list.indexOf(callback);
          if (i >= 0) list.splice(i, 1);
        };
      },
      once: function (event, callback) {
        var off = window.nezumi.events.on(event, function (payload) { off(); callback(payload); });
        return off;
      }
    },
    image: {
      listModels: function () { return __call('image.listModels').then(function (r) { return r.models; }); },
      getModel: function (id) { return __call('image.getModel', { id: id }); },
      generate: function (options) { return __call('image.generate', options); },
      cancel: function () { return __call('image.cancel'); }
    },
    onnx: {
      open: function (options) { return __call('onnx.open', options).then(function (r) { return r.sessionId; }); },
      getInputs: function (sessionId) { return __call('onnx.getInputs', { sessionId: sessionId }).then(function (r) { return r.inputs; }); },
      getOutputs: function (sessionId) { return __call('onnx.getOutputs', { sessionId: sessionId }).then(function (r) { return r.outputs; }); },
      createTensor: function (sessionId, shape, data, dtype) {
        var b64 = (data instanceof ArrayBuffer) ? arrayBufferToBase64(data) : data;
        return __call('onnx.createTensor', { sessionId: sessionId, shape: shape, data: b64, dtype: dtype || 'float32' }).then(function (r) { return r.tensorId; });
      },
      run: function (sessionId, inputs) { return __call('onnx.run', { sessionId: sessionId, inputs: inputs }).then(function (r) { return r.outputs; }); },
      disposeTensor: function (tensorId) { return __call('onnx.disposeTensor', { tensorId: tensorId }); },
      close: function (sessionId) { return __call('onnx.close', { sessionId: sessionId }); }
    },
    download: {
      create: function (options) { return __call('download.create', options).then(function (r) { return r.download; }); },
      get: function (id) { return __call('download.get', { id: id }).then(function (r) { return r.download; }); },
      list: function () { return __call('download.list').then(function (r) { return r.downloads; }); },
      start: function (id) { return __call('download.start', { id: id }); },
      pause: function (id) { return __call('download.pause', { id: id }); },
      resume: function (id) { return __call('download.resume', { id: id }); },
      cancel: function (id) { return __call('download.cancel', { id: id }); }
    },
    miniApps: {
      list: function () { return __call('miniApps.list').then(function (r) { return r.apps; }); },
      get: function (id) { return __call('miniApps.get', { id: id }); }
    }
  };
})();
""".trimIndent()
    }
}
