package com.nezumi_ai.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Hugging Face 由来の GGUF / モデルメタデータ（ファイル一覧、README、mmproj 候補）を
 * 端末側で軽量にキャッシュするユーティリティ。
 *
 * Bug fix: モデル管理画面を開くたびに HF API へ問い合わせていたため、表示までに
 * 数百〜数千ミリ秒のラグが発生していた。読み取りは多くてもアプリ起動中数十回程度なので、
 * - メモリキャッシュ（プロセス寿命）
 * - ディスクキャッシュ（端末再起動を跨いで再利用）
 * の二段構成でキャッシュし、TTL を持たせて陳腐化を防ぐ。
 *
 * 値そのものはバイナリではなく文字列なので、`SharedPreferences` または小さな単独ファイル
 * への書き出しで十分。ここでは衝突回避と単純化のため `cacheDir` 配下のテキストファイル
 * として保存する。
 */
object HfMetadataCache {
    private const val TAG = "HfMetadataCache"

    /** デフォルト TTL: 6 時間。HF 側で頻繁に書き換わるものではないため十分に長め。 */
    const val DEFAULT_TTL_MS: Long = 6L * 60L * 60L * 1000L

    /** メモリ上の即時キャッシュ。プロセス起動中は HF へ触らなくて済むようにする。 */
    private val memCache = ConcurrentHashMap<String, Entry>()

    private data class Entry(val value: String, val expireAt: Long)

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, "hf_metadata_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun keyToFile(context: Context, key: String): File {
        // ファイル名衝突を避けるためハッシュ化する。
        val safe = key.hashCode().toString(16).let { if (it.startsWith("-")) "n${it.substring(1)}" else it }
        return File(cacheDir(context), "$safe.txt")
    }

    /**
     * キャッシュから値を取得する。期限切れ・存在しない場合は null。
     */
    fun get(context: Context, key: String, ttlMs: Long = DEFAULT_TTL_MS): String? {
        val now = System.currentTimeMillis()
        memCache[key]?.let { entry ->
            if (entry.expireAt >= now) return entry.value
            memCache.remove(key)
        }
        return try {
            val file = keyToFile(context, key)
            if (!file.exists()) return null
            // ファイルの mtime + ttl で期限判定。
            val age = now - file.lastModified()
            if (age > ttlMs) {
                file.delete()
                return null
            }
            val text = file.readText(Charsets.UTF_8)
            memCache[key] = Entry(text, file.lastModified() + ttlMs)
            text
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read cache entry: $key", t)
            null
        }
    }

    /**
     * 値をキャッシュに保存する。
     */
    fun put(context: Context, key: String, value: String, ttlMs: Long = DEFAULT_TTL_MS) {
        val expireAt = System.currentTimeMillis() + ttlMs
        memCache[key] = Entry(value, expireAt)
        runCatching {
            val file = keyToFile(context, key)
            file.writeText(value, Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "Failed to write cache entry: $key", it)
        }
    }

    /**
     * 取得関数 [loader] の結果をキャッシュ越しに返すヘルパ。
     * 取得失敗時に古いキャッシュがあればそれを返す（ベストエフォート）。
     */
    suspend fun getOrLoad(
        context: Context,
        key: String,
        ttlMs: Long = DEFAULT_TTL_MS,
        loader: suspend () -> String
    ): String {
        get(context, key, ttlMs)?.let { return it }
        return try {
            val loaded = loader()
            put(context, key, loaded, ttlMs)
            loaded
        } catch (t: Throwable) {
            // 期限切れでも残っていれば fallback として返す。
            val fallback = runCatching {
                val file = keyToFile(context, key)
                if (file.exists()) file.readText(Charsets.UTF_8) else null
            }.getOrNull()
            if (fallback != null) {
                Log.w(TAG, "Loader failed; falling back to stale cache for key=$key", t)
                fallback
            } else {
                throw t
            }
        }
    }

    /** 指定キーを無効化する（モデル設定変更後などに利用）。 */
    fun invalidate(context: Context, key: String) {
        memCache.remove(key)
        runCatching { keyToFile(context, key).delete() }
    }

    /** すべてのキャッシュを破棄する。 */
    fun clearAll(context: Context) {
        memCache.clear()
        runCatching {
            val dir = cacheDir(context)
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
