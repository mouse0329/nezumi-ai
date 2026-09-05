package com.nezumi_ai.data.miniapp

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 仕様 v1.1 §9 Runtime Context。
 *
 * Mini App の実行インスタンス（WebView 1つ = 1 runtime）に紐付くコンテキスト。
 * mode は v1.1 で temporary が廃止されたため常に "installed"。
 */
data class MiniAppRuntimeContext(
    val appId: String,
    val appVersion: String,
    val runtimeId: String = UUID.randomUUID().toString(),
    val mode: String = "installed",
    val origin: String = "miniapp://$appId"
)

/**
 * 仕様 §12 Permission の実行時管理。
 *
 * - Manifest 未宣言 → PERMISSION_NOT_DECLARED
 * - 状態: granted / denied / prompt（未決定）
 * - ユーザーによる許可/拒否は App Data 側ではなく SharedPreferences に永続化する
 *   （Package は不変であり、権限状態はユーザー判断の記録であってアプリ資産ではないため）。
 */
class MiniAppPermissionManager(private val context: Context) {

    enum class State(val wire: String) {
        GRANTED("granted"),
        DENIED("denied"),
        PROMPT("prompt"),
        UNAVAILABLE("unavailable");

        companion object {
            fun fromWire(s: String?): State? = entries.firstOrNull { it.wire == s }
        }
    }

    private fun prefs(appId: String) = context.getSharedPreferences(
        "miniapp_perms_${MiniAppStore.sanitize(appId)}", Context.MODE_PRIVATE
    )

    fun getState(manifest: MiniAppManifest, permission: String): State {
        if (permission !in manifest.permissions) {
            throw MiniAppException("PERMISSION_NOT_DECLARED", "権限 '$permission' が manifest に宣言されていません")
        }
        return State.fromWire(prefs(manifest.id).getString(permission, null)) ?: State.PROMPT
    }

    fun isGranted(manifest: MiniAppManifest, permission: String): Boolean =
        runCatching { getState(manifest, permission) == State.GRANTED }.getOrDefault(false)

    fun grant(manifest: MiniAppManifest, permission: String) {
        if (permission !in manifest.permissions) {
            throw MiniAppException("PERMISSION_NOT_DECLARED", "権限 '$permission' が manifest に宣言されていません")
        }
        prefs(manifest.id).edit().putString(permission, State.GRANTED.wire).apply()
    }

    fun deny(manifest: MiniAppManifest, permission: String) {
        prefs(manifest.id).edit().putString(permission, State.DENIED.wire).apply()
    }

    /** RPC ディスパッチ時の権限チェック。未許可なら PERMISSION_DENIED を投げる。 */
    fun requireGranted(manifest: MiniAppManifest, permission: String) {
        if (!isGranted(manifest, permission)) {
            throw MiniAppException("PERMISSION_DENIED", "権限 '$permission' が許可されていません")
        }
    }

    companion object {
        @Volatile
        private var instance: MiniAppPermissionManager? = null

        fun get(context: Context): MiniAppPermissionManager =
            instance ?: synchronized(this) {
                instance ?: MiniAppPermissionManager(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 仕様 §17/§18 Tool Registry（Mini App 提供ツールのランタイム束縛）。
 *
 * - Mini App が register したツールは runtimeId に紐付き、runtime 終了で自動削除（§18）。
 * - 永続 Registry には保存しない。
 */
object MiniAppToolRegistry {

    private const val TAG = "MiniAppToolRegistry"

    data class MiniAppTool(
        val runtimeId: String,
        val appId: String,
        val name: String,
        val description: String,
        val parametersSchema: JSONObject
    )

    private val tools = ConcurrentHashMap<String, MutableList<MiniAppTool>>() // runtimeId -> tools

    fun register(runtime: MiniAppRuntimeContext, tool: MiniAppTool) {
        tools.getOrPut(runtime.runtimeId) { mutableListOf() }.add(tool)
        Log.d(TAG, "miniapp tool registered: ${tool.name} (runtime=${runtime.runtimeId})")
    }

    fun listForRuntime(runtimeId: String): List<MiniAppTool> = tools[runtimeId].orEmpty()

    /** runtime 終了時に必ず呼ぶ（§18 終了で自動削除）。 */
    fun clearRuntime(runtimeId: String) {
        tools.remove(runtimeId)
        Log.d(TAG, "miniapp tools cleared for runtime=$runtimeId")
    }
}

/**
 * 仕様 §31 Events API のランタイム内イベントバス。
 * WebView 側へは `nezumi.events` の JS 経由で配信する（MiniAppJsBridge が中継）。
 */
class MiniAppEventBus {
    interface Listener {
        fun onEvent(event: String, payloadJson: String)
    }

    private val listeners = mutableListOf<Listener>()

    @Synchronized
    fun add(listener: Listener) {
        listeners.add(listener)
    }

    @Synchronized
    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun emit(event: String, payloadJson: String) {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { runCatching { it.onEvent(event, payloadJson) } }
    }
}
