package com.nezumi_ai.data.inference

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * セッション単位でのリソース（Conversation、KVCache など）を管理。
 * - セッション開始/終了を明確に宣言
 * - Conversation インスタンスの厳密な 1:1 管理
 * - セッション ID の自動生成・チェック
 * - セッション遷移時のリソース完全クリーンアップ
 * - タイムアウト機構（10分以上アクティビティがないセッションを自動終了）
 */
class SessionResourceManager {
    
    // セッション ID の自動採番用
    private val sessionIdCounter = AtomicLong(System.currentTimeMillis())
    
    // セッション タイムアウト（10 分）
    private val SESSION_TIMEOUT_MS = 10 * 60 * 1000
    
    companion object {
        private const val TAG = "SessionResourceManager"
        
        /**
         * 新しいセッション ID を生成
         */
        fun generateSessionId(): Long = System.currentTimeMillis()
    }
    
    /**
     * セッション内のリソース
     */
    private data class SessionResource(
        val sessionId: Long,
        val conversation: Conversation? = null,
        val createdTimeMs: Long = System.currentTimeMillis(),
        var lastAccessTimeMs: Long = System.currentTimeMillis()
    ) {
        val isExpired: Boolean
            get() {
                val idleTimeMs = System.currentTimeMillis() - lastAccessTimeMs
                // 10 分以上アクティビティがない場合は期限切れ
                return idleTimeMs > (10 * 60 * 1000)
            }
    }
    
    private val sessions = ConcurrentHashMap<Long, SessionResource>()
    private val sessionMutex = Mutex()
    
    // セッション ID の値域チェック
    private var lastKnownSessionId: Long = 0
    
    /**
     * セッション開始を宣言し、セッション管理に登録
     * @return セッション ID
     */
    suspend fun createSession(): Long {
        return sessionMutex.withLock {
            val sessionId = generateSessionId()
            
            // 古いセッションをクリーンアップ
            cleanupExpiredSessions()
            
            // 新しいセッション作成
            sessions[sessionId] = SessionResource(sessionId = sessionId)
            lastKnownSessionId = sessionId
            
            Log.i(TAG, "Session created: $sessionId (total: ${sessions.size} active)")
            sessionId
        }
    }
    
    /**
     * セッションを終了し、リソースを完全にクリーンアップ
     */
    suspend fun endSession(sessionId: Long) {
        return sessionMutex.withLock {
            val resource = sessions.remove(sessionId)
            if (resource != null) {
                cleanupSessionResources(resource)
                Log.i(TAG, "Session ended: $sessionId (total: ${sessions.size} active)")
            } else {
                Log.w(TAG, "Attempted to end non-existent session: $sessionId")
            }
        }
    }
    
    /**
     * セッションがまだ有効かチェック
     */
    suspend fun isSessionValid(sessionId: Long): Boolean {
        return sessionMutex.withLock {
            val resource = sessions[sessionId] ?: return false
            
            if (resource.isExpired) {
                Log.w(TAG, "Session expired: $sessionId")
                sessions.remove(sessionId)
                cleanupSessionResources(resource)
                return false
            }
            
            return true
        }
    }
    
    /**
     * セッション ID の値が正常な範囲内かチェック
     * （バッファオーバーフロー攻撃や不正な ID からの防御）
     */
    suspend fun validateSessionId(sessionId: Long): Boolean {
        return sessionMutex.withLock {
            // セッション ID は自動生成値（システム時刻以上）
            // または既存のセッション ID でなければならない
            val isValid = sessionId >= 0 && sessionId <= lastKnownSessionId + 1000
            if (!isValid) {
                Log.w(TAG, "Invalid session ID range: $sessionId (last known: $lastKnownSessionId)")
            }
            return isValid
        }
    }
    
    /**
     * セッションをアクセス（タッチ）して、タイムアウトを延長
     */
    suspend fun touchSession(sessionId: Long) {
        return sessionMutex.withLock {
            val resource = sessions[sessionId]
            if (resource != null) {
                resource.lastAccessTimeMs = System.currentTimeMillis()
            }
        }
    }
    
    /**
     * Conversation をセッションに関連付ける
     */
    suspend fun attachConversation(sessionId: Long, conversation: Conversation) {
        return sessionMutex.withLock {
            val resource = sessions[sessionId] ?: run {
                Log.e(TAG, "Cannot attach conversation: session not found ($sessionId)")
                return
            }
            
            // 既存の conversation がある場合はクローズ
            resource.conversation?.let {
                try {
                    it.close()
                    Log.d(TAG, "Previous conversation closed for session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close previous conversation", e)
                }
            }
            
            sessions[sessionId] = resource.copy(
                conversation = conversation,
                lastAccessTimeMs = System.currentTimeMillis()
            )
            Log.d(TAG, "Conversation attached to session $sessionId")
        }
    }
    
    /**
     * セッションの Conversation を取得
     */
    suspend fun getConversation(sessionId: Long): Conversation? {
        return sessionMutex.withLock {
            val resource = sessions[sessionId]
            resource?.conversation?.also {
                // アクセス時刻を更新
                resource.lastAccessTimeMs = System.currentTimeMillis()
            }
        }
    }
    
    /**
     * 期限切れセッションを自動クリーンアップ
     */
    private suspend fun cleanupExpiredSessions() {
        val expiredIds = sessions
            .filter { it.value.isExpired }
            .map { it.key }
        
        expiredIds.forEach { sessionId ->
            val resource = sessions.remove(sessionId)
            if (resource != null) {
                cleanupSessionResources(resource)
                Log.i(TAG, "Expired session cleaned up: $sessionId")
            }
        }
    }
    
    /**
     * セッションリソースの完全なクリーンアップ
     */
    private fun cleanupSessionResources(resource: SessionResource) {
        try {
            resource.conversation?.let {
                try {
                    it.close()
                    Log.d(TAG, "Conversation closed for session ${resource.sessionId}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing conversation", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during session resource cleanup", e)
        }
    }
    
    /**
     * 全セッションを終了（アプリ終了時に呼び出し）
     */
    suspend fun shutdownAll() {
        return sessionMutex.withLock {
            val sessionIds = sessions.keys.toList()
            sessionIds.forEach { sessionId ->
                val resource = sessions.remove(sessionId)
                if (resource != null) {
                    cleanupSessionResources(resource)
                }
            }
            Log.i(TAG, "All sessions shutdown")
        }
    }
    
    /**
     * 現在のアクティブセッション数（デバッグ用）
     */
    suspend fun getActiveSessionCount(): Int {
        return sessionMutex.withLock { sessions.size }
    }
    
    /**
     * セッション情報をダンプ（デバッグ用）
     */
    suspend fun dumpSessionInfo(): String {
        return sessionMutex.withLock {
            val sb = StringBuilder("Active Sessions:\n")
            sessions.forEach { (sessionId, resource) ->
                val idleMs = System.currentTimeMillis() - resource.lastAccessTimeMs
                val hasConv = resource.conversation != null
                sb.append("  Session $sessionId: idle=${idleMs}ms, conversation=$hasConv\n")
            }
            sb.toString()
        }
    }
}
