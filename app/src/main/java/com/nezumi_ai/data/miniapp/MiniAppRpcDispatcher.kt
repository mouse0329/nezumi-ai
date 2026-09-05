package com.nezumi_ai.data.miniapp

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.graphics.Bitmap
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.data.inference.EngineManager
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.OpenClAvailability
import com.nezumi_ai.data.inference.VulkanAvailability
import com.nezumi_ai.data.mcp.McpPreferences
import com.nezumi_ai.data.mcp.McpToolRegistry
import com.nezumi_ai.sd.SdScheduler
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 仕様 v1.1 §10 RPC Dispatcher。
 *
 * `Validate Request → Identify Runtime → Identify App → Check Manifest
 *  → Check Permission → Check Sandbox → Execute → Return`
 *
 * WebView (nezumi JS SDK) からの RPC リクエスト JSON を検証・権限チェックのうえ
 * 既存のネイティブ層 (ModelManager / MCP / LocalDreamModule / ファイルシステム) へ中継する。
 * Mini App から直接 Android API を呼ばせない（§10）。
 */
class MiniAppRpcDispatcher(
    private val context: Context,
    private val runtime: MiniAppRuntimeContext,
    private val manifest: MiniAppManifest,
    private val eventBus: MiniAppEventBus,
    private val closeCallback: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val permissionManager = MiniAppPermissionManager.get(context)
    private val store = MiniAppStore.get(context)
    private val sessionCounter = AtomicLong(System.currentTimeMillis())

    /** requestId → キャンセル要求フラグ（ai.stop 用）。 */
    private val cancelledRequests = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** §27 ONNX: runtime 束縛のセッション/テンソル管理。 */
    private val onnxManager by lazy {
        MiniAppOnnxManager(context, runtime.runtimeId, store.dataDir(runtime.appId))
    }

    /** §29 Download: runtime 束縛のダウンロード管理。 */
    private val downloadManager by lazy {
        MiniAppDownloadManager(context, runtime.runtimeId, store.dataDir(runtime.appId), eventBus)
    }

    interface ResultSink {
        fun onResult(id: Long, responseJson: String)
        fun onStreamChunk(requestId: String, chunkJson: String, done: Boolean)
    }

    var sink: ResultSink? = null

    /** WebView 破棄時に呼ぶ。 §18: Mini App Tool の自動削除。 */
    fun destroy() {
        MiniAppToolRegistry.clearRuntime(runtime.runtimeId)
        runCatching { onnxManager.destroy() }
        runCatching { downloadManager.destroy() }
        scope.cancel()
    }

    // ---------------------------------------------------------------------
    // エントリポイント
    // ---------------------------------------------------------------------

    fun dispatch(requestJson: String) {
        val req = try {
            JSONObject(requestJson)
        } catch (e: Exception) {
            sink?.onResult(-1, errorResponse(-1, "PACKAGE_INVALID", "RPC リクエストがJSONとして不正です"))
            return
        }
        val id = req.optLong("id", -1)
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()

        if (method.isBlank()) {
            sink?.onResult(id, errorResponse(id, "PACKAGE_INVALID", "method が指定されていません"))
            return
        }

        scope.launch {
            val response = try {
                val result = handle(method, params, id)
                successResponse(id, result)
            } catch (e: MiniAppException) {
                errorResponse(id, e.code, e.message ?: e.code)
            } catch (e: CancellationException) {
                // ai.stop / runtime 破棄によるキャンセルも呼び出し側へ結果を返してから再スローする
                sink?.onResult(id, errorResponse(id, "CANCELLED", "キャンセルされました"))
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "RPC handler failed: $method", e)
                errorResponse(id, "INTERNAL_ERROR", e.message ?: "internal error")
            }
            sink?.onResult(id, response)
        }
    }

    // ---------------------------------------------------------------------
    // メソッドディスパッチ
    // ---------------------------------------------------------------------

    private suspend fun handle(method: String, params: JSONObject, requestId: Long): JSONObject {
        // Identify App: ランタイムのアプリがまだ installed であることを毎回確認（§35.5.4）
        if (!store.isInstalled(runtime.appId)) {
            throw MiniAppException("APP_NOT_INSTALLED", "アプリはアンインストールされました")
        }
        return when (method) {
            // §15 nezumi.app
            "app.getInfo" -> handleAppGetInfo()
            "app.getRuntimeInfo" -> handleAppGetRuntimeInfo()
            "app.getHostInfo" -> handleAppGetHostInfo()
            "app.close" -> { closeCallback(); JSONObject() }

            // §12 permissions
            "permissions.list" -> handlePermissionsList()
            "permissions.get" -> handlePermissionsGet(params)
            "permissions.request" -> handlePermissionsRequest(params)

            // §16 nezumi.ai
            "ai.listModels" -> handleAiListModels()
            "ai.loadModel" -> handleAiLoadModel(params)
            "ai.generate" -> handleAiGenerate(params, requestId.toString(), stream = false)
            "ai.stream" -> handleAiGenerate(params, requestId.toString(), stream = true)
            "ai.stop" -> handleAiStop(params)

            // §17/§18 tools
            "tools.list" -> handleToolsList()
            "tools.call" -> handleToolsCall(params)
            "tools.register" -> handleToolsRegister(params)

            // §19 mcp
            "mcp.listServers" -> handleMcpListServers()
            "mcp.listTools" -> handleMcpListTools(params)

            // §20/§21 models（読み出しのみ。書き込み系は要承認 UI フローが未整備のため未実装）
            "models.list" -> handleModelsList()
            "models.get" -> handleModelsGet(params)
            "models.exists" -> handleModelsExists(params)

            // §22/§23/§24 engines
            "engines.list" -> handleEnginesList()
            "engines.listBackends" -> handleEnginesListBackends(params)
            "engines.probeMemory" -> handleEnginesProbeMemory()

            // §29 Memory Pressure Guard（ai.loadModel からも利用される）
            "device.getInfo" -> handleDeviceInfo()
            "device.getMemoryInfo" -> handleDeviceMemoryInfo()

            // §28 nezumi.image
            "image.listModels" -> handleImageListModels()
            "image.getModel" -> handleImageGetModel(params)
            "image.generate" -> handleImageGenerate(params, requestId.toString())
            "image.cancel" -> handleImageCancel()

            // §27 nezumi.onnx / tensor
            "onnx.open" -> handleOnnxOpen(params)
            "onnx.getInputs" -> handleOnnxIo(params, inputs = true)
            "onnx.getOutputs" -> handleOnnxIo(params, inputs = false)
            "onnx.createTensor" -> handleOnnxCreateTensor(params)
            "onnx.run" -> handleOnnxRun(params)
            "onnx.disposeTensor" -> handleOnnxDisposeTensor(params)
            "onnx.close" -> handleOnnxClose(params)

            // §29 Download API
            "download.create" -> handleDownloadCreate(params)
            "download.get" -> JSONObject().put("download", downloadManager.get(params.optString("id", "")).toJson())
            "download.list" -> JSONObject().put("downloads", JSONArray().apply {
                downloadManager.list().forEach { put(it.toJson()) }
            })
            "download.start" -> { downloadManager.start(params.optString("id", "")); JSONObject().put("ok", true) }
            "download.pause" -> { downloadManager.pause(params.optString("id", "")); JSONObject().put("ok", true) }
            "download.resume" -> { downloadManager.resume(params.optString("id", "")); JSONObject().put("ok", true) }
            "download.cancel" -> { downloadManager.cancel(params.optString("id", "")); JSONObject().put("ok", true) }

            // §30 storage / files
            "storage.get" -> handleStorageGet(params)
            "storage.set" -> handleStorageSet(params)
            "storage.has" -> handleStorageHas(params)
            "storage.delete" -> handleStorageDelete(params)
            "storage.keys" -> handleStorageKeys()
            "storage.clear" -> handleStorageClear()
            "storage.getUsage" -> handleStorageGetUsage()
            "files.list" -> handleFilesList(params)
            "files.exists" -> handleFilesExists(params)
            "files.read" -> handleFilesRead(params)
            "files.write" -> handleFilesWrite(params)
            "files.delete" -> handleFilesDelete(params)
            "files.stat" -> handleFilesStat(params)

            // §31 miniApps
            "miniApps.list" -> handleMiniAppsList()
            "miniApps.get" -> handleMiniAppsGet(params)

            else -> throw MiniAppException("METHOD_NOT_FOUND", "未対応のメソッドです: $method")
        }
    }

    // ---------------------------------------------------------------------
    // §15 app
    // ---------------------------------------------------------------------

    private fun handleAppGetInfo(): JSONObject = JSONObject().apply {
        put("id", manifest.id)
        put("name", manifest.name)
        put("version", manifest.version)
        put("publisher", manifest.publisher)
        put("mode", "installed")
    }

    private fun handleAppGetRuntimeInfo(): JSONObject = JSONObject().apply {
        put("appId", runtime.appId)
        put("appVersion", runtime.appVersion)
        put("runtimeId", runtime.runtimeId)
        put("mode", runtime.mode)
        put("origin", runtime.origin)
    }

    /** Nezumi AI クライアント（本体）のバージョン情報。Mini App が本体の機能対応状況を判定するために使用。 */
    private fun handleAppGetHostInfo(): JSONObject = JSONObject().apply {
        put("appName", "Nezumi AI")
        put("packageName", context.packageName)
        put("versionName", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("miniAppPlatformVersion", "1.1")
        put("sdkInt", Build.VERSION.SDK_INT)
    }

    // ---------------------------------------------------------------------
    // §12 permissions
    // ---------------------------------------------------------------------

    private fun handlePermissionsList(): JSONObject {
        val arr = JSONArray()
        manifest.permissions.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p)
                put("state", permissionManager.getState(manifest, p).wire)
            })
        }
        return JSONObject().put("permissions", arr)
    }

    private fun handlePermissionsGet(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        return JSONObject().put("state", permissionManager.getState(manifest, name).wire)
    }

    /**
     * permissions.request は UI 同意が必要なため、ここでは「現時点の状態」を返す。
     * 実際の同意ダイアログは WebView 側 [com.nezumi_ai.presentation.ui.fragment.MiniAppRunnerFragment]
     * の PermissionRequestHandler 経由で prompt される。
     */
    private fun handlePermissionsRequest(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val current = permissionManager.getState(manifest, name)
        if (current == MiniAppPermissionManager.State.GRANTED) {
            return JSONObject().put("state", current.wire)
        }
        // ランタイム側 UI フックへ委譲（同期ブロックはしない。denied のまま返し、
        // WebView 側でユーザーが再操作した際に再度呼ばれる設計）
        throw MiniAppException("PERMISSION_DENIED", "権限 '$name' は未許可です。本体の権限ダイアログで許可してください")
    }

    // ---------------------------------------------------------------------
    // §16 ai
    // ---------------------------------------------------------------------

    /**
     * モデル一覧（§20 ModelInfo）。
     * ビルトイン Gemma（filesDir/models/）とユーザーインポート済みモデル
     * （filesDir/models/imported/）を列挙する。配置元は HF/インポートのみ（§20）。
     */
    private fun handleAiListModels(): JSONObject {
        val arr = JSONArray()
        // ビルトイン Gemma 系（ダウンロード済みのもののみ）
        ModelFileManager.LocalModel.entries.forEach { m ->
            if (!ModelFileManager.isDownloaded(context, m)) return@forEach
            val fileName = ModelFileManager.modelFileName(m)
            arr.put(modelInfo(id = fileName, name = fileName, size = ModelFileManager.modelFile(context, m).length()))
        }
        // ユーザーインポートモデル
        ModelFileManager.listImportedTaskModels(context).forEach { im ->
            arr.put(modelInfo(id = im.fileNameStem, name = im.shortDisplayName, size = File(im.path).length()))
        }
        return JSONObject().put("models", arr)
    }

    private fun modelInfo(id: String, name: String, size: Long): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("format", when {
            id.endsWith(".gguf") -> "gguf"
            id.endsWith(".onnx") -> "onnx"
            else -> "litert"
        })
        put("size", size)
        put("capabilities", JSONObject().apply {
            put("textGeneration", true)
            put("chat", true)
            put("streaming", true)
        })
    }

    /** 実際に利用可能なモデル id かを判定（ビルトイン or インポート済み）。 */
    private fun resolveModelId(id: String): Boolean {
        if (ModelFileManager.LocalModel.entries.any { ModelFileManager.modelFileName(it) == id && ModelFileManager.isDownloaded(context, it) }) return true
        if (ModelFileManager.listImportedTaskModels(context).any { it.fileNameStem == id }) return true
        return ModelFileManager.isModelAvailable(context, id)
    }

    private suspend fun handleAiLoadModel(params: JSONObject): JSONObject {
        permissionManager.requireAnyGranted(manifest, "ai.loadModel", "ai")
        val modelId = params.optString("id", params.optString("model", ""))
        if (modelId.isBlank()) throw MiniAppException("MODEL_NOT_FOUND", "model が指定されていません")

        // §29 Memory Pressure Guard: strict（デフォルト）では警告レベル以上で停止
        val memorySafety = params.optString("memorySafety", "strict")
        val allowLowMemory = params.optBoolean("allowLowMemory", false)
        val memStatus = MemoryObserver.getMemoryStatus(context)
        val tight = memStatus.isLowMemory || memStatus.usedPercent >= 85
        if (tight && memorySafety == "strict" && !allowLowMemory) {
            throw MiniAppException(
                "MEMORY_PRESSURE_WARNING",
                "メモリが不足しています（使用率 ${memStatus.usedPercent}%）。memorySafety:'force' で強行できます",
                details = mapOf("pressureLevel" to memStatus.level.name.lowercase())
            )
        }

        val modelManager = ModelManager.getInstance(context)
        val config = inferenceConfigFrom(params)
        val result = modelManager.initializeModel(modelId, config)
        if (result.isFailure) {
            throw MiniAppException(
                "MODEL_LOAD_FAILED",
                result.exceptionOrNull()?.message ?: "モデルのロードに失敗しました"
            )
        }
        eventBus.emit("model.loaded", JSONObject().put("model", modelId).toString())
        return JSONObject().apply {
            put("model", modelId)
            put("loaded", true)
        }
    }

    private suspend fun handleAiGenerate(params: JSONObject, requestId: String, stream: Boolean): JSONObject {
        permissionManager.requireAnyGranted(manifest, "ai.generate", "ai")
        val prompt = params.optString("prompt", "")
        if (prompt.isBlank()) throw MiniAppException("PACKAGE_INVALID", "prompt が指定されていません")

        val modelId = params.optString("model", "")
        val modelManager = ModelManager.getInstance(context)
        if (modelId.isNotBlank() && !modelManager.isSameModelLoaded(modelId)) {
            // 未ロードならロードを試みる（memory guard 経由）
            handleAiLoadModel(JSONObject(params.toString()).put("id", modelId))
        }

        val config = inferenceConfigFrom(params)
        val sessionId = sessionCounter.incrementAndGet()
        cancelledRequests[requestId] = false
        val sb = StringBuilder()
        return try {
            modelManager.runInference(sessionId, prompt, config)
                .catch { e ->
                    if (e is CancellationException || cancelledRequests[requestId] == true) {
                        // キャンセル: 途中までの結果で終了
                        return@catch
                    }
                    throw e
                }
                .collect { chunk ->
                    if (cancelledRequests[requestId] == true) {
                        throw CancellationException("cancelled by ai.stop")
                    }
                    sb.append(chunk)
                    if (stream) {
                        sink?.onStreamChunk(
                            requestId,
                            JSONObject().put("requestId", requestId).put("delta", chunk).toString(),
                            done = false
                        )
                    }
                }
            if (stream) {
                sink?.onStreamChunk(requestId, JSONObject().put("requestId", requestId).toString(), done = true)
            }
            JSONObject().apply {
                put("requestId", requestId)
                put("text", sb.toString())
            }
        } finally {
            cancelledRequests.remove(requestId)
        }
    }

    private suspend fun handleAiStop(params: JSONObject): JSONObject {
        val requestId = params.optString("requestId", "")
        cancelledRequests[requestId] = true
        runCatching { ModelManager.getInstance(context).cancelInference() }
        return JSONObject().put("stopped", true)
    }

    /** §25 LLM 推論パラメータ → InferenceConfig への変換（User Settings がデフォルト、per-request が最優先）。 */
    private fun inferenceConfigFrom(params: JSONObject): InferenceConfig {
        val base = InferenceConfig()
        return base.copy(
            contextWindow = params.optInt("contextLength", base.contextWindow),
            temperature = if (params.has("temperature")) params.optDouble("temperature").toFloat() else base.temperature,
            topP = if (params.has("topP")) params.optDouble("topP").toFloat() else base.topP,
            maxTopK = params.optInt("topK", base.maxTopK),
            maxTokens = params.optInt("maxTokens", base.maxTokens),
            customStopTokens = params.optJSONArray("stop")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: base.customStopTokens
        )
    }

    // ---------------------------------------------------------------------
    // §17/§18/§19 tools / mcp
    // ---------------------------------------------------------------------

    private fun handleToolsList(): JSONObject {
        permissionManager.requireGranted(manifest, "tools.list")
        val arr = JSONArray()
        // builtin（代表）: Mini App 経由で公開するもののみ
        arr.put(toolInfo("builtin.files.read", "アプリデータ内のファイルを読む", "builtin"))
        arr.put(toolInfo("builtin.models.list", "モデル一覧を取得", "builtin"))
        // mcp ツール
        if (permissionManager.isGranted(manifest, "tools.call")) {
            McpToolRegistry.get(context).currentTools().forEach { t ->
                arr.put(toolInfo(t.qualifiedName, t.description, "mcp").apply {
                    put("provider", JSONObject().put("id", t.serverId).put("name", t.serverName))
                })
            }
        }
        // miniapp ツール（この runtime が登録したもの）
        MiniAppToolRegistry.listForRuntime(runtime.runtimeId).forEach { t ->
            arr.put(toolInfo(t.name, t.description, "miniapp"))
        }
        return JSONObject().put("tools", arr)
    }

    private fun toolInfo(name: String, description: String, source: String): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("source", source)
        }

    private suspend fun handleToolsCall(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "tools.call")
        val name = params.optString("name", "")
        val args = params.optJSONObject("args") ?: JSONObject()

        when {
            name == "builtin.models.list" -> return handleModelsList()
            name == "builtin.files.read" -> return handleFilesRead(
                JSONObject().put("path", args.optString("path", ""))
            )
            name.startsWith("mcp__") -> {
                val argMap = mutableMapOf<String, Any?>()
                for (k in args.keys()) argMap[k] = args.get(k)
                val result = McpToolRegistry.get(context).callQualified(name, argMap)
                if (!result.success) {
                    throw MiniAppException("TOOL_CALL_FAILED", result.errorMessage ?: "MCP ツール呼び出しに失敗しました")
                }
                return JSONObject().apply {
                    put("text", result.resultText ?: "")
                    if (result.rawResult != null) put("raw", result.rawResult)
                }
            }
            else -> {
                // miniapp ツール: WebView 側実装への呼び出しは JS ブリッジ双方向化が必要なため、
                // 現段階では未対応（§18 の runtime 束縛登録までは実装済み）。
                throw MiniAppException("TOOL_CALL_FAILED", "未対応のツールです: $name")
            }
        }
    }

    private fun handleToolsRegister(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "tools.register")
        val name = params.optString("name", "")
        if (name.isBlank()) throw MiniAppException("PACKAGE_INVALID", "tool name が必要です")
        MiniAppToolRegistry.register(
            runtime,
            MiniAppToolRegistry.MiniAppTool(
                runtimeId = runtime.runtimeId,
                appId = runtime.appId,
                name = name,
                description = params.optString("description", ""),
                parametersSchema = params.optJSONObject("parameters") ?: JSONObject()
            )
        )
        return JSONObject().put("registered", true)
    }

    private fun handleMcpListServers(): JSONObject {
        permissionManager.requireGranted(manifest, "mcp.list")
        val arr = JSONArray()
        McpPreferences.get(context).getServers().forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("transport", s.transport.id)
                put("enabled", s.enabled)
            })
        }
        return JSONObject().put("servers", arr)
    }

    private fun handleMcpListTools(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "mcp.list")
        val serverId = params.optString("serverId", "")
        val arr = JSONArray()
        McpToolRegistry.get(context).currentTools()
            .filter { serverId.isBlank() || it.serverId == serverId }
            .forEach { t ->
                arr.put(toolInfo(t.name, t.description, "mcp").apply {
                    put("qualifiedName", t.qualifiedName)
                })
            }
        return JSONObject().put("tools", arr)
    }

    // ---------------------------------------------------------------------
    // §20/§21 models
    // ---------------------------------------------------------------------

    private fun handleModelsList(): JSONObject = handleAiListModels()

    private fun handleModelsGet(params: JSONObject): JSONObject {
        val id = params.optString("id", "")
        if (!resolveModelId(id)) {
            throw MiniAppException("MODEL_NOT_FOUND", "モデルが見つかりません: $id")
        }
        val builtin = ModelFileManager.LocalModel.entries.firstOrNull { ModelFileManager.modelFileName(it) == id }
        val size = when {
            builtin != null -> ModelFileManager.modelFile(context, builtin).length()
            else -> ModelFileManager.listImportedTaskModels(context)
                .firstOrNull { it.fileNameStem == id }?.let { File(it.path).length() } ?: 0L
        }
        return modelInfo(id = id, name = id, size = size)
    }

    private fun handleModelsExists(params: JSONObject): JSONObject {
        val id = params.optString("id", "")
        return JSONObject().put("exists", resolveModelId(id))
    }

    // ---------------------------------------------------------------------
    // §22/§23/§24 engines
    // ---------------------------------------------------------------------

    private fun handleEnginesList(): JSONObject {
        val arr = JSONArray()
        arr.put(JSONObject().put("id", "llama.cpp").put("type", "llm"))
        arr.put(JSONObject().put("id", "litert").put("type", "llm"))
        arr.put(JSONObject().put("id", "image").put("type", "image"))
        return JSONObject().put("engines", arr)
    }

    private fun handleEnginesListBackends(params: JSONObject): JSONObject {
        val engineId = params.optString("engineId", "llama.cpp")
        val arr = JSONArray()
        when (engineId) {
            "llama.cpp" -> {
                arr.put(backend("cpu", true))
                arr.put(backend("opencl", OpenClAvailability.isAvailable(), "DRIVER_NOT_FOUND"))
                arr.put(backend("vulkan", VulkanAvailability.isAvailable(), "DRIVER_NOT_FOUND"))
            }
            "litert" -> {
                arr.put(backend("cpu", true))
                arr.put(backend("gpu", true))
                arr.put(backend("npu", false, "SOC_NOT_SUPPORTED"))
            }
            "image" -> {
                arr.put(backend("cpu", true))
                arr.put(backend("opencl", OpenClAvailability.isAvailable(), "DRIVER_NOT_FOUND"))
            }
            else -> throw MiniAppException("BACKEND_NOT_AVAILABLE", "未知のエンジンです: $engineId")
        }
        return JSONObject().put("backends", arr)
    }

    private fun backend(type: String, available: Boolean, unavailableReason: String? = null): JSONObject =
        JSONObject().apply {
            put("id", type)
            put("type", type)
            put("available", available)
            if (!available && unavailableReason != null) put("reason", unavailableReason)
        }

    private suspend fun handleEnginesProbeMemory(): JSONObject {
        val status = MemoryObserver.getMemoryStatus(context)
        val sys = MemoryObserver.getSystemMemoryInfo(context)
        return JSONObject().apply {
            put("canLoad", status.usedPercent < 90)
            put("warning", status.usedPercent >= 80)
            put("pressureLevel", status.level.name.lowercase())
            put("memory", JSONObject().apply {
                put("totalMemory", sys.totalMemoryMB * 1024L * 1024L)
                put("availableMemory", sys.availableMemoryMB * 1024L * 1024L)
            })
        }
    }

    // ---------------------------------------------------------------------
    // §31 device
    // ---------------------------------------------------------------------

    private fun handleDeviceInfo(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
    }

    private fun handleDeviceMemoryInfo(): JSONObject {
        val sys = MemoryObserver.getSystemMemoryInfoSync(context)
        return JSONObject().apply {
            put("totalMemory", sys.totalMemoryMB * 1024L * 1024L)
            put("availableMemory", sys.availableMemoryMB * 1024L * 1024L)
        }
    }

    // ---------------------------------------------------------------------
    // §30 storage（App Data 内 settings.json を KV ストアとして使用）
    // ---------------------------------------------------------------------

    private fun storageFile(): File {
        val dir = store.dataDir(runtime.appId)
        if (!dir.exists()) {
            throw MiniAppException("STORAGE_NOT_AVAILABLE", "App Data が初期化されていません")
        }
        return File(dir, "settings.json")
    }

    private fun readStorageJson(): JSONObject {
        val f = storageFile()
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeStorageJson(obj: JSONObject) {
        runCatching { storageFile().writeText(obj.toString()) }
            .onFailure { throw MiniAppException("STORAGE_NOT_AVAILABLE", "ストレージへの書き込みに失敗しました", cause = it) }
    }

    private fun handleStorageGet(params: JSONObject): JSONObject {
        val key = params.optString("key", "")
        val obj = readStorageJson()
        return JSONObject().put("value", if (obj.has(key)) obj.get(key) else JSONObject.NULL)
    }

    private fun handleStorageSet(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "storage")
        val key = params.optString("key", "")
        val obj = readStorageJson()
        obj.put(key, if (params.has("value")) params.get("value") else JSONObject.NULL)
        writeStorageJson(obj)
        return JSONObject().put("ok", true)
    }

    private fun handleStorageHas(params: JSONObject): JSONObject =
        JSONObject().put("exists", readStorageJson().has(params.optString("key", "")))

    private fun handleStorageDelete(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "storage")
        val obj = readStorageJson()
        obj.remove(params.optString("key", ""))
        writeStorageJson(obj)
        return JSONObject().put("ok", true)
    }

    private fun handleStorageKeys(): JSONObject {
        val obj = readStorageJson()
        val arr = JSONArray()
        for (k in obj.keys()) arr.put(k)
        return JSONObject().put("keys", arr)
    }

    private fun handleStorageClear(): JSONObject {
        permissionManager.requireGranted(manifest, "storage")
        writeStorageJson(JSONObject())
        return JSONObject().put("ok", true)
    }

    /** App Data のストレージ使用量（§30）。settings.json・cache・user-data の内訳つき。 */
    private fun handleStorageGetUsage(): JSONObject {
        val dataDir = store.dataDir(runtime.appId)
        if (!dataDir.exists()) {
            throw MiniAppException("STORAGE_NOT_AVAILABLE", "App Data が初期化されていません")
        }
        val settings = storageFile().let { if (it.exists()) it.length() else 0L }
        val cache = dirSize(File(dataDir, "cache"))
        val userData = dirSize(File(dataDir, "user-data"))
        return JSONObject().apply {
            put("totalBytes", settings + cache + userData)
            put("settingsBytes", settings)
            put("cacheBytes", cache)
            put("userDataBytes", userData)
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    // ---------------------------------------------------------------------
    // §30 files（App Data 内に限定。Package 書き込み禁止 / 他 App 干渉禁止 = §6）
    // ---------------------------------------------------------------------

    /**
     * サンドボックス境界（§6）: App Data ルート配下のみ許可。
     * `models/` への書き込みは FILE_ACCESS_DENIED（§20、必ず nezumi.models 経由）。
     */
    private fun resolveDataPath(path: String, forWrite: Boolean): File {
        if (path.startsWith("models/") || path.startsWith("/models")) {
            if (forWrite) {
                throw MiniAppException(
                    "FILE_ACCESS_DENIED",
                    "models/ への直接書き込みは禁止です。nezumi.models API を使用してください"
                )
            }
            // 読み出しは Global モデルストレージへ（§37: Global Model 読み出し無許可OK）
            return safeResolve(File(context.filesDir, "models"), path.removePrefix("/").removePrefix("models/"))
        }
        val root = store.dataDir(runtime.appId)
        if (!root.exists()) {
            throw MiniAppException("STORAGE_NOT_AVAILABLE", "App Data が初期化されていません")
        }
        return safeResolve(root, path)
    }

    private fun safeResolve(root: File, relative: String): File {
        val cleaned = relative.removePrefix("/")
        val target = File(root, cleaned).canonicalFile
        val rootCanonical = root.canonicalFile
        if (target.path != rootCanonical.path && !target.path.startsWith(rootCanonical.path + File.separator)) {
            throw MiniAppException("FILE_ACCESS_DENIED", "App Data 境界外へのアクセスは禁止されています")
        }
        return target
    }

    private fun handleFilesList(params: JSONObject): JSONObject {
        val dir = resolveDataPath(params.optString("path", ""), forWrite = false)
        if (!dir.exists()) return JSONObject().put("entries", JSONArray())
        if (!dir.isDirectory) throw MiniAppException("FILE_ACCESS_DENIED", "ディレクトリではありません")
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("isDirectory", f.isDirectory)
                put("size", if (f.isFile) f.length() else 0L)
                put("lastModified", f.lastModified())
            })
        }
        return JSONObject().put("entries", arr)
    }

    private fun handleFilesExists(params: JSONObject): JSONObject =
        JSONObject().put("exists", resolveDataPath(params.optString("path", ""), false).exists())

    private fun handleFilesRead(params: JSONObject): JSONObject {
        val f = resolveDataPath(params.optString("path", ""), forWrite = false)
        if (!f.exists() || !f.isFile) throw MiniAppException("FILE_NOT_FOUND", "ファイルが見つかりません")
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        return JSONObject().put("data", b64)
    }

    private fun handleFilesWrite(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "files.write")
        val f = resolveDataPath(params.optString("path", ""), forWrite = true)
        val b64 = params.optString("data", "")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return JSONObject().put("ok", true)
    }

    private fun handleFilesDelete(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "files.write")
        val f = resolveDataPath(params.optString("path", ""), forWrite = true)
        if (!f.exists()) throw MiniAppException("FILE_NOT_FOUND", "ファイルが見つかりません")
        f.deleteRecursively()
        return JSONObject().put("ok", true)
    }

    private fun handleFilesStat(params: JSONObject): JSONObject {
        val f = resolveDataPath(params.optString("path", ""), forWrite = false)
        if (!f.exists()) throw MiniAppException("FILE_NOT_FOUND", "ファイルが見つかりません")
        return JSONObject().apply {
            put("isDirectory", f.isDirectory)
            put("size", if (f.isFile) f.length() else 0L)
            put("lastModified", f.lastModified())
        }
    }

    // ---------------------------------------------------------------------
    // §28 nezumi.image（モデルリスト取得のみ。DL不可 / §28.5 Safety Pipeline 経由必須）
    // ---------------------------------------------------------------------

    /** sd_models/ 配下の有効なモデルフォルダを列挙（本体の ImageGenViewModel と同じ配置）。 */
    private fun listSdModelDirs(): List<File> {
        val root = File(context.filesDir, "sd_models")
        return root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
    }

    private fun sdModelInfo(dir: File): JSONObject {
        val isSdxl = dir.listFiles()?.any { it.name.contains("sdxl", ignoreCase = true) } == true
        return JSONObject().apply {
            put("id", dir.name)
            put("name", dir.name)
            put("type", if (isSdxl) "sdxl" else "sd1.5")
            put("supportedSchedulers", JSONArray().apply {
                listOf("euler", "euler_a", "ddim", "dpm", "dpmpp_2m", "dpmpp_2m_karras", "lcm", "unipc").forEach { put(it) }
            })
            put("supportedBackends", JSONArray().apply { put("cpu"); put("opencl") })
        }
    }

    private fun handleImageListModels(): JSONObject {
        val arr = JSONArray()
        listSdModelDirs().forEach { arr.put(sdModelInfo(it)) }
        return JSONObject().put("models", arr)
    }

    private fun handleImageGetModel(params: JSONObject): JSONObject {
        val id = params.optString("id", "")
        val dir = listSdModelDirs().firstOrNull { it.name == id }
            ?: throw MiniAppException("MODEL_NOT_FOUND", "画像生成モデルが見つかりません: $id")
        return sdModelInfo(dir)
    }

    private suspend fun handleImageGenerate(params: JSONObject, requestId: String): JSONObject {
        permissionManager.requireGranted(manifest, "image.generate")
        val modelId = params.optString("model", "")
        val prompt = params.optString("prompt", "")
        if (prompt.isBlank()) throw MiniAppException("INVALID_INPUT", "prompt が必要です")

        // モデル解決: 指定があればその id、なければ本体設定の選択中モデル → sd_models 先頭の順
        val modelDir = when {
            modelId.isNotBlank() -> listSdModelDirs().firstOrNull { it.name == modelId }
                ?: throw MiniAppException("MODEL_NOT_FOUND", "画像生成モデルが見つかりません: $modelId")
            else -> {
                val configured = PreferencesHelper.getSdModelPath(context).takeIf { it.isNotBlank() }?.let { File(it) }
                when {
                    configured != null && configured.isDirectory -> configured
                    else -> listSdModelDirs().firstOrNull()
                        ?: throw MiniAppException("MODEL_NOT_FOUND", "画像生成モデルがありません")
                }
            }
        }

        // §28 解像度制約: SD1.5 は 256–512、SDXL は 640–1024。範囲外は RESOLUTION_OUT_OF_RANGE
        val isSdxl = sdModelInfo(modelDir).optString("type") == "sdxl"
        val width = params.optInt("width", if (isSdxl) 1024 else 512)
        val height = params.optInt("height", if (isSdxl) 1024 else 512)
        val (minRes, maxRes) = if (isSdxl) 640 to 1024 else 256 to 512
        if (width < minRes || width > maxRes || height < minRes || height > maxRes) {
            throw MiniAppException(
                "RESOLUTION_OUT_OF_RANGE",
                "解像度が範囲外です（${if (isSdxl) "SDXL" else "SD1.5"} は ${minRes}–${maxRes}）: ${width}x${height}"
            )
        }

        val backend = if (PreferencesHelper.getSdBackend(context).isNotBlank())
            PreferencesHelper.getSdBackend(context) else "auto"
        val dream = try {
            EngineManager.acquireLocalDream(context, modelDir.absolutePath, backend)
        } catch (e: Exception) {
            throw MiniAppException("ENGINE_INIT_FAILED", "画像生成エンジンの初期化に失敗しました: ${e.message}", cause = e)
        }

        val scheduler = SdScheduler.entries.firstOrNull {
            it.id.equals(params.optString("scheduler", ""), ignoreCase = true)
        } ?: SdScheduler.DEFAULT

        dream.clearLastSafetyVerdict()
        val bitmap: Bitmap? = dream.generateImage(
            prompt = prompt,
            negativePrompt = params.optString("negativePrompt", ""),
            width = width,
            height = height,
            steps = params.optInt("steps", PreferencesHelper.getSdSteps(context)),
            cfg = if (params.has("cfgScale")) params.optDouble("cfgScale").toFloat() else PreferencesHelper.getSdCfg(context),
            seed = if (params.has("seed")) params.optLong("seed") else System.currentTimeMillis(),
            scheduler = scheduler,
            onProgress = { current, total, _ ->
                eventBus.emit(
                    "image.progress",
                    JSONObject().put("requestId", requestId)
                        .put("step", current).put("totalSteps", total).toString()
                )
            }
        )

        // §28.5: null は本体 Safety Pipeline の BLOCK/fail-safe。理由の詳細は開示しない
        if (bitmap == null) {
            val verdict = dream.getLastSafetyVerdict()
            throw MiniAppException(
                "CONTENT_POLICY_VIOLATION",
                "本体の安全対策により生成がブロックされました"
            )
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return JSONObject().apply {
            put("requestId", requestId)
            put("image", "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            put("width", bitmap.width)
            put("height", bitmap.height)
        }
    }

    private fun handleImageCancel(): JSONObject {
        // シグネチャ上 scope が必要だが、RPC 呼び出しスレッドをブロックしないよう内部で非同期実行される
        EngineManager.cancelCurrentGeneration(scope)
        return JSONObject().put("cancelled", true)
    }

    // ---------------------------------------------------------------------
    // §27 nezumi.onnx / tensor
    // ---------------------------------------------------------------------

    private fun handleOnnxOpen(params: JSONObject): JSONObject {
        permissionManager.requireAnyGranted(manifest, "ai.loadModel", "ai")
        val id = onnxManager.open(params.optString("model", ""))
        return JSONObject().put("sessionId", id)
    }

    private fun handleOnnxIo(params: JSONObject, inputs: Boolean): JSONObject {
        val list = if (inputs) onnxManager.getInputs(params.optString("sessionId", ""))
        else onnxManager.getOutputs(params.optString("sessionId", ""))
        val arr = JSONArray()
        list.forEach { m ->
            @Suppress("UNCHECKED_CAST")
            val shapeList = m["shape"] as? List<Any> ?: emptyList()
            val shapeArrJson = JSONArray()
            shapeList.forEach { shapeArrJson.put(it) }
            arr.put(JSONObject().apply {
                put("name", m["name"])
                put("shape", shapeArrJson)
                put("dtype", m["dtype"])
            })
        }
        return JSONObject().put(if (inputs) "inputs" else "outputs", arr)
    }

    private fun handleOnnxCreateTensor(params: JSONObject): JSONObject {
        val shapeArr = params.optJSONArray("shape")
            ?: throw MiniAppException("INVALID_INPUT", "shape が必要です")
        val shape = (0 until shapeArr.length()).map { shapeArr.getLong(it) }
        val id = onnxManager.createTensor(
            params.optString("sessionId", ""),
            shape,
            params.optString("data", "")
        )
        return JSONObject().put("tensorId", id)
    }

    private fun handleOnnxRun(params: JSONObject): JSONObject {
        val inputsObj = params.optJSONObject("inputs") ?: JSONObject()
        val inputs = mutableMapOf<String, String>()
        for (k in inputsObj.keys()) inputs[k] = inputsObj.getString(k)
        val outputs = onnxManager.run(params.optString("sessionId", ""), inputs)
        val outObj = JSONObject()
        outputs.forEach { (k, v) -> outObj.put(k, v) }
        return JSONObject().put("outputs", outObj)
    }

    private fun handleOnnxDisposeTensor(params: JSONObject): JSONObject {
        onnxManager.disposeTensor(params.optString("tensorId", ""))
        return JSONObject().put("ok", true)
    }

    private fun handleOnnxClose(params: JSONObject): JSONObject {
        onnxManager.closeSession(params.optString("sessionId", ""))
        return JSONObject().put("ok", true)
    }

    // ---------------------------------------------------------------------
    // §29 Download API
    // ---------------------------------------------------------------------

    private fun handleDownloadCreate(params: JSONObject): JSONObject {
        permissionManager.requireGranted(manifest, "download")
        val entry = downloadManager.create(
            url = params.optString("url", ""),
            destPath = params.optString("destPath", "user-data/" + params.optString("url", "").substringAfterLast('/'))
        )
        return JSONObject().put("download", entry.toJson())
    }

    // ---------------------------------------------------------------------
    // §31 miniApps（v1.1: 起動は Mini App Manager 経由のみ。ここでは参照系のみ）
    // ---------------------------------------------------------------------

    private fun handleMiniAppsList(): JSONObject {
        val arr = JSONArray()
        store.list().forEach { app ->
            arr.put(JSONObject().apply {
                put("id", app.manifest.id)
                put("name", app.manifest.name)
                put("version", app.manifest.version)
                put("publisher", app.manifest.publisher)
                put("trusted", app.trusted)
                put("devMode", app.devMode)
            })
        }
        return JSONObject().put("apps", arr)
    }

    private fun handleMiniAppsGet(params: JSONObject): JSONObject {
        val id = params.optString("id", "")
        val app = store.get(id) ?: throw MiniAppException("APP_NOT_FOUND", "アプリが見つかりません: $id")
        return JSONObject().apply {
            put("id", app.manifest.id)
            put("name", app.manifest.name)
            put("version", app.manifest.version)
            put("publisher", app.manifest.publisher)
            put("permissions", JSONArray().apply { app.manifest.permissions.forEach { put(it) } })
            put("installedAt", app.installedAt)
        }
    }

    // ---------------------------------------------------------------------
    // レスポンス組み立て（§8）
    // ---------------------------------------------------------------------

    private fun successResponse(id: Long, result: JSONObject): String =
        JSONObject().apply {
            put("id", id)
            put("ok", true)
            put("result", result)
        }.toString()

    private fun errorResponse(id: Long, code: String, message: String): String =
        JSONObject().apply {
            put("id", id)
            put("ok", false)
            put("error", JSONObject().put("code", code).put("message", message))
        }.toString()

    companion object {
        private const val TAG = "MiniAppRpcDispatcher"
    }
}
