package com.nezumi_ai.data.inference.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 別プロセスの推論サービス (:gguf / :litert) との bind/unbind・強制終了・再接続を
 * 共通化した接続マネージャ。
 *
 * RemoteGgufInferenceEngine / RemoteLiteRtInferenceEngine の両アダプタから
 * 利用する。コード重複を避けるため、プロセス間で対称な終了ロジックはここに集約する。
 *
 * スレッド方針:
 * - 公開 suspend メソッドは呼び出し側のコンテキストで動く
 * - Binder 呼び出しでブロックするものは [withContext] (Dispatchers.IO) 内で実行
 * - [getEngineStatus] / [isAvailable] 等の同期 getter は [runBlocking] でラップする
 *   (AIInferenceEngine にはない「同期型」アクセサの互換実装のため)
 */
class RemoteEngineConnection(
    private val context: Context,
    private val serviceComponent: ComponentName,
    private val tag: String
) {
    private val bindMutex = Mutex()

    @Volatile
    private var engine: IRemoteInferenceEngine? = null

    @Volatile
    private var connection: ServiceConnection? = null

    /**
     * bind が成功するまでの間、onServiceConnected を待つための Deferred。
     * null の間は未接続。
     */
    @Volatile
    private var boundDeferred: CompletableDeferred<IRemoteInferenceEngine>? = null

    @Volatile
    private var lastKnownPid: Int = -1

    val isBound: Boolean
        get() = engine != null

    /**
     * サービスへ bind し、[IRemoteInferenceEngine] を返す。
     * 既に接続済みなら既存の engine をそのまま返す。
     *
     * @throws DeadObjectException サービス側プロセスが死亡していて bind に失敗した場合
     */
    suspend fun getService(): IRemoteInferenceEngine = bindMutex.withLock {
        engine?.let { return it }

        val deferred = CompletableDeferred<IRemoteInferenceEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val e = IRemoteInferenceEngine.Stub.asInterface(service)
                if (e == null) {
                    deferred.completeExceptionally(
                        IllegalStateException("$tag: failed to cast binder to IRemoteInferenceEngine")
                    )
                    return
                }
                engine = e
                lastKnownPid = runCatching { e.remotePid }.getOrDefault(-1)
                Log.i(tag, "service connected: pid=$lastKnownPid")
                deferred.complete(e)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(tag, "service disconnected (process died or unbound)")
                engine = null
                lastKnownPid = -1
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(tag, "binding died; will rebind on next request")
                engine = null
                lastKnownPid = -1
            }

            override fun onNullBinding(name: ComponentName?) {
                deferred.completeExceptionally(
                    IllegalStateException("$tag: onNullBinding (service returned null IBinder)")
                )
            }
        }

        val intent = Intent().setComponent(serviceComponent)
        val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!ok) {
            context.unbindService(conn)
            throw IllegalStateException("$tag: bindService returned false for $serviceComponent")
        }

        connection = conn
        boundDeferred = deferred

        return try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            // タイムアウト等: bind だけは残っているので後始末する
            runCatching { context.unbindService(conn) }
            connection = null
            boundDeferred = null
            throw t
        }
    }

    /**
     * 現在接続中の [IRemoteInferenceEngine] を返す。未接続なら bind する。
     */
    private suspend fun requireService(): IRemoteInferenceEngine = getService()

    /**
     * サービスから切断する。プロセス自体は生かしたまま。
     * プロセスも終了させたい場合は [shutdownProcess] を使うこと。
     */
    suspend fun disconnect() = bindMutex.withLock {
        val conn = connection ?: return@withLock
        engine = null
        connection = null
        boundDeferred = null
        lastKnownPid = -1
        runCatching { context.unbindService(conn) }
            .onFailure { Log.w(tag, "unbindService failed", it) }
        Log.i(tag, "disconnected")
    }

    /**
     * 接続先プロセスを強制終了する (計画書 5.4 / 5.5)。
     *
     * 手順:
     *  1. まずサービスに requestProcessExit() を送り、プロセス自身に
     *     「クリーンアップしてから killProcess(myPid())」を要求する (自殺方式)。
     *  2. 猶予時間待っても死亡しなければ、フォールバックとして
     *     こちら側から Process.killProcess(pid) を送る。
     *  3. 最後に unbind して参照をクリアする。
     *
     * これにより Vulkan / OpenCL / TFLite GPU delegate 等の
     * 「Kotlin/JNI 層では安全に解放できないネイティブ状態」を
     * OS のプロセス回収に委ねられる。
     */
    suspend fun shutdownProcess() {
        val currentEngine: IRemoteInferenceEngine?
        val pid: Int
        bindMutex.withLock {
            currentEngine = engine
            pid = lastKnownPid
        }

        if (currentEngine == null || pid <= 0) {
            Log.d(tag, "shutdownProcess: no active process (already dead or never bound)")
            disconnect()
            return
        }

        Log.i(tag, "shutdownProcess: requesting remote pid=$pid to exit")
        // 自殺要求は失敗しても先へ進む (既に死んでいる場合など)
        runCatching { currentEngine.requestProcessExit() }
            .onFailure { Log.w(tag, "requestProcessExit failed (process may already be dead)", it) }

        // 猶予: サービス側でアンロード+killProcess が走るのを待つ
        val exited = waitForProcessDeath(pid, SELF_KILL_GRACE_MS)
        if (!exited) {
            Log.w(tag, "remote pid=$pid did not exit within ${SELF_KILL_GRACE_MS}ms; sending killProcess")
            runCatching { Process.killProcess(pid) }
                .onFailure { Log.w(tag, "killProcess($pid) failed", it) }
            waitForProcessDeath(pid, FORCE_KILL_GRACE_MS)
        }

        disconnect()
        Log.i(tag, "shutdownProcess: completed for pid=$pid (selfExited=$exited)")
    }

    private suspend fun waitForProcessDeath(pid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessAlive(pid)) return true
            delay(PROCESS_DEATH_POLL_MS)
        }
        return !isProcessAlive(pid)
    }

    private fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        // Android 公開 API には「シグナル0で生存確認」相当が無いため、
        // /proc/<pid> の存在で判定する。同一 UID の自分のアプリのプロセスであれば
        // hidepid 環境でも参照できる。
        return runCatching { java.io.File("/proc/$pid").exists() }.getOrDefault(false)
    }

    // ─── AIInferenceEngine 相当の委譲メソッド ─────────────────────

    suspend fun loadModel(modelName: String, config: android.os.Bundle): Result<Unit> =
        withContext(Dispatchers.IO) {
            val service = requireService()
            val result = CompletableDeferred<Result<Unit>>()
            try {
                service.loadModel(modelName, config, object : IRemoteResultCallback.Stub() {
                    override fun onSuccess() {
                        result.complete(Result.success(Unit))
                    }

                    override fun onError(message: String?) {
                        result.complete(
                            Result.failure(RuntimeException(message ?: "remote loadModel failed"))
                        )
                    }
                })
                result.await()
            } catch (t: Throwable) {
                handleRemoteException(t, "loadModel")
            }
        }.also { invalidateEngineStatusCache() }

    suspend fun unloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        val service = requireService()
        val result = CompletableDeferred<Result<Unit>>()
        try {
            service.unloadModel(object : IRemoteResultCallback.Stub() {
                override fun onSuccess() {
                    result.complete(Result.success(Unit))
                }

                override fun onError(message: String?) {
                    result.complete(
                        Result.failure(RuntimeException(message ?: "remote unloadModel failed"))
                    )
                }
            })
            result.await()
        } catch (t: Throwable) {
            handleRemoteException(t, "unloadModel")
        }
    }.also { invalidateEngineStatusCache() }

    suspend fun cancelInference() = withContext(Dispatchers.IO) {
        runCatching { requireService().cancelInference() }
            .onFailure { Log.w(tag, "cancelInference failed", it) }
        Unit
    }

    fun isAvailableSync(): Boolean = runBlocking(Dispatchers.IO) {
        runCatching { requireService().isAvailable }.getOrDefault(false)
    }

    // Bug fix(#binder-thread-pool-starvation): getEngineStatus (AIDL code 7)
    // は同期 Binder 呼び出しで、ストリーミング中の _messages 再emit 等から
    // 集中して呼ばれるとリモートプロセスの Binder スレッドプール (15 本) を
    // 埋め尽くす。呼び出し側 (ChatViewModel) のスロットリングに加えて、
    // ここでも直近の結果を短時間キャッシュし、連打を吸収する。
    // メーター/TPS 表示は目安用途なので数秒程度の遅延は問題にならない。
    private val engineStatusCacheLock = Any()
    @Volatile private var cachedEngineStatus: android.os.Bundle? = null
    @Volatile private var cachedEngineStatusAtMs: Long = 0L

    fun getEngineStatusSync(): android.os.Bundle {
        val now = System.currentTimeMillis()
        synchronized(engineStatusCacheLock) {
            val cached = cachedEngineStatus
            if (cached != null && now - cachedEngineStatusAtMs < ENGINE_STATUS_CACHE_TTL_MS) {
                return cached
            }
        }
        val fresh = runBlocking(Dispatchers.IO) {
            runCatching { requireService().engineStatus }.getOrElse { android.os.Bundle() }
        }
        synchronized(engineStatusCacheLock) {
            cachedEngineStatus = fresh
            cachedEngineStatusAtMs = System.currentTimeMillis()
        }
        return fresh
    }

    /** モデル状態が変わったタイミングでステータスキャッシュを破棄する。 */
    private fun invalidateEngineStatusCache() {
        synchronized(engineStatusCacheLock) {
            cachedEngineStatus = null
            cachedEngineStatusAtMs = 0L
        }
    }

    // ─── GGUF 固有 ────────────────────────────────────────────────

    fun clearKvCacheIfLoadedSync() = runBlocking(Dispatchers.IO) {
        runCatching { requireService().clearKvCacheIfLoaded() }
            .onFailure { Log.w(tag, "clearKvCacheIfLoaded failed", it) }
        Unit
    }

    fun requestForceClearBeforeNextInferenceSync() = runBlocking(Dispatchers.IO) {
        runCatching { requireService().requestForceClearBeforeNextInference() }
            .onFailure { Log.w(tag, "requestForceClearBeforeNextInference failed", it) }
        Unit
    }

    suspend fun formatWithGgufChatTemplate(messagesJson: String, enableThinking: Boolean): String =
        awaitString("formatWithGgufChatTemplate") { service, cb ->
            service.formatWithGgufChatTemplate(messagesJson, enableThinking, cb)
        }

    suspend fun formatWithJinjaChatTemplate(
        messagesJson: String,
        chatTemplate: String,
        enableThinking: Boolean
    ): String = awaitString("formatWithJinjaChatTemplate") { service, cb ->
        service.formatWithJinjaChatTemplate(messagesJson, chatTemplate, enableThinking, cb)
    }

    /**
     * @return {"content":..., "reasoning_content":...} 形式の JSON 文字列。
     * 失敗時・テンプレ未適用時は null。
     */
    suspend fun parseWithGgufChatTemplate(output: String, isPartial: Boolean): String? =
        runCatching {
            awaitString("parseWithGgufChatTemplate") { service, cb ->
                service.parseWithGgufChatTemplate(output, isPartial, cb)
            }
        }.getOrNull()?.takeIf { it != "{}" && it.isNotBlank() }

    // ─── LiteRT-LM 固有 ──────────────────────────────────────────

    fun markSessionHasMediaSync(sessionId: Long) = runBlocking(Dispatchers.IO) {
        runCatching { requireService().markSessionHasMedia(sessionId) }
            .onFailure { Log.w(tag, "markSessionHasMedia failed", it) }
        Unit
    }

    fun clearSessionMediaHistorySync(sessionId: Long) = runBlocking(Dispatchers.IO) {
        runCatching { requireService().clearSessionMediaHistory(sessionId) }
            .onFailure { Log.w(tag, "clearSessionMediaHistory failed", it) }
        Unit
    }

    suspend fun forceReset(): Result<Unit> = withContext(Dispatchers.IO) {
        val service = requireService()
        val result = CompletableDeferred<Result<Unit>>()
        try {
            service.forceReset(object : IRemoteResultCallback.Stub() {
                override fun onSuccess() {
                    result.complete(Result.success(Unit))
                }

                override fun onError(message: String?) {
                    result.complete(
                        Result.failure(RuntimeException(message ?: "remote forceReset failed"))
                    )
                }
            })
            result.await()
        } catch (t: Throwable) {
            handleRemoteException(t, "forceReset")
        }
    }

    fun probeGpuBackendSync(backend: String): Boolean = runBlocking(Dispatchers.IO) {
        runCatching { requireService().probeGpuBackend(backend) }.getOrDefault(false)
    }

    fun getCompiledGpuBackendsSync(): Set<String> = runBlocking(Dispatchers.IO) {
        runCatching { requireService().compiledGpuBackends?.toSet() }.getOrNull() ?: emptySet()
    }

    /**
     * :gguf プロセスで TTS デバッグ合成を実行する。設定画面のデバッグ機能から利用。
     */
    suspend fun ttsSynthesize(
        modelPath: String,
        tokenizerPath: String,
        text: String,
        speakerPath: String?,
        outPath: String,
        nThreads: Int,
        nPredict: Int,
        seed: Int
    ): String = withContext(Dispatchers.IO) {
        val service = requireService()
        val result = CompletableDeferred<String>()
        service.ttsSynthesize(
            modelPath, tokenizerPath, text, speakerPath, outPath,
            nThreads, nPredict, seed,
            object : IRemoteStringCallback.Stub() {
                override fun onResult(value: String?) {
                    result.complete(value ?: "{\"ok\":false,\"error\":\"empty result\"}")
                }

                override fun onError(message: String?) {
                    result.completeExceptionally(
                        RuntimeException(message ?: "ttsSynthesize failed")
                    )
                }
            }
        )
        result.await()
    }

    // ─── 内部ヘルパー ────────────────────────────────────────────

    private suspend fun awaitString(
        opName: String,
        block: (IRemoteInferenceEngine, IRemoteStringCallback) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val service = requireService()
        val result = CompletableDeferred<String>()
        try {
            block(service, object : IRemoteStringCallback.Stub() {
                override fun onResult(value: String?) {
                    result.complete(value ?: "")
                }

                override fun onError(message: String?) {
                    result.completeExceptionally(
                        RuntimeException(message ?: "$opName failed")
                    )
                }
            })
            result.await()
        } catch (t: Throwable) {
            when (t) {
                is DeadObjectException -> {
                    Log.w(tag, "$opName: remote process died")
                    invalidateConnection()
                    throw t
                }
                else -> throw t
            }
        }
    }

    private fun handleRemoteException(t: Throwable, op: String): Result<Unit> {
        return when (t) {
            is DeadObjectException -> {
                Log.w(tag, "$op: remote process died")
                invalidateConnection()
                Result.failure(t)
            }
            else -> {
                Log.e(tag, "$op failed", t)
                Result.failure(t)
            }
        }
    }

    /**
     * DeadObject 検知後に接続状態をクリアし、次回呼び出しで rebind できるようにする。
     */
    private fun invalidateConnection() {
        engine = null
        lastKnownPid = -1
        // ServiceConnection は bind し直すので明示的に unbind しなくてもよいが、
        // 古い connection が生きたままだと二重管理になるため、可能なら切る。
        val conn = connection
        connection = null
        boundDeferred = null
        if (conn != null) {
            runCatching { context.unbindService(conn) }
        }
    }

    companion object {
        private const val BIND_TIMEOUT_MS = 15_000L
        /** getEngineStatus のキャッシュ TTL。連打吸収用の短い窓。 */
        private const val ENGINE_STATUS_CACHE_TTL_MS = 2_000L
        private const val SELF_KILL_GRACE_MS = 1_500L
        private const val FORCE_KILL_GRACE_MS = 1_500L
        private const val PROCESS_DEATH_POLL_MS = 50L
    }
}
