package com.nezumi_ai.data.miniapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 仕様 v1.1 §29 Download API。
 *
 * - ダウンロード先は App Data 内に限定（§6 ストレージ境界）。
 * - create で登録のみ行い、start/resume で実際の取得を開始する。
 * - pause は HTTP 切断ベース（Range 再開は resume で Content-Range を利用）。
 * - 状態は runtime 横断で App Data の `.downloads.json` に永続化する。
 */
class MiniAppDownloadManager(
    private val context: Context,
    private val runtimeId: String,
    private val dataRoot: File,
    private val eventBus: MiniAppEventBus
) {
    data class DownloadEntry(
        val id: String,
        val url: String,
        val destPath: String,
        var state: String = "pending", // pending | running | paused | completed | failed | cancelled
        var bytesDownloaded: Long = 0L,
        var totalBytes: Long = -1L,
        var error: String? = null
    ) {
        fun toJson() = org.json.JSONObject().apply {
            put("id", id)
            put("url", url)
            put("destPath", destPath)
            put("state", state)
            put("bytesDownloaded", bytesDownloaded)
            put("totalBytes", totalBytes)
            put("error", error ?: org.json.JSONObject.NULL)
        }

        companion object {
            fun fromJson(o: org.json.JSONObject) = DownloadEntry(
                id = o.getString("id"),
                url = o.getString("url"),
                destPath = o.getString("destPath"),
                state = o.optString("state", "pending"),
                bytesDownloaded = o.optLong("bytesDownloaded", 0L),
                totalBytes = o.optLong("totalBytes", -1L),
                error = if (o.isNull("error")) null else o.optString("error")
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun stateFile(): File = File(dataRoot, ".downloads.json")

    private fun loadEntries(): MutableMap<String, DownloadEntry> {
        val f = stateFile()
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).associate {
                val e = DownloadEntry.fromJson(arr.getJSONObject(it))
                e.id to e
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun persist(entries: Map<String, DownloadEntry>) {
        val arr = org.json.JSONArray()
        entries.values.forEach { arr.put(it.toJson()) }
        runCatching { stateFile().writeText(arr.toString()) }
            .onFailure { Log.w(TAG, "failed to persist downloads", it) }
    }

    fun create(url: String, destPath: String): DownloadEntry {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw MiniAppException("INVALID_INPUT", "URL は http(s):// である必要があります")
        }
        // 書き込み先は App Data 内に限定
        resolveDest(destPath)
        val entry = DownloadEntry(id = UUID.randomUUID().toString(), url = url, destPath = destPath)
        val entries = loadEntries()
        entries[entry.id] = entry
        persist(entries)
        return entry
    }

    fun get(id: String): DownloadEntry =
        loadEntries()[id] ?: throw MiniAppException("NOT_FOUND", "ダウンロードが見つかりません: $id")

    fun list(): List<DownloadEntry> = loadEntries().values.sortedBy { it.id }

    fun start(id: String) = launch(id, resume = false)
    fun resume(id: String) = launch(id, resume = true)

    fun pause(id: String) {
        pauseFlags[id] = true
        jobs[id]?.cancel()
    }

    fun cancel(id: String) {
        cancelFlags[id] = true
        pauseFlags.remove(id)
        jobs[id]?.cancel()
        update(id) { it.state = "cancelled" }
    }

    private fun launch(id: String, resume: Boolean) {
        if (jobs[id]?.isActive == true) return
        cancelFlags[id] = false
        pauseFlags[id] = false
        val job = scope.launch {
            runDownload(id, resume)
        }
        jobs[id] = job
    }

    private fun update(id: String, block: (DownloadEntry) -> Unit) {
        val entries = loadEntries()
        val e = entries[id] ?: return
        block(e)
        persist(entries)
    }

    private suspend fun runDownload(id: String, resume: Boolean) {
        val entry = get(id)
        val dest = resolveDest(entry.destPath)
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            update(id) { it.state = "running" }
            eventBus.emit("download.progress", org.json.JSONObject().put("id", id).put("state", "running").toString())

            val existing = if (resume && part.exists()) part.length() else 0L
            val request = Request.Builder().url(entry.url).apply {
                if (existing > 0) header("Range", "bytes=$existing-")
            }.build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw MiniAppException("DOWNLOAD_FAILED", "HTTP ${resp.code}")
                }
                val body = resp.body ?: throw MiniAppException("DOWNLOAD_FAILED", "レスポンスが空です")
                val total = if (resp.code == 206) existing + body.contentLength() else body.contentLength()
                update(id) { it.totalBytes = total; it.bytesDownloaded = existing }

                dest.parentFile?.mkdirs()
                val append = resume && resp.code == 206 && existing > 0
                FileOutputStream(part, append).use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var downloaded = existing
                    var lastEmit = 0L
                    while (true) {
                        if (cancelFlags[id] == true) {
                            update(id) { it.state = "cancelled" }
                            part.delete()
                            return
                        }
                        if (pauseFlags[id] == true) {
                            update(id) { it.state = "paused"; it.bytesDownloaded = downloaded }
                            return
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 500) {
                            lastEmit = now
                            update(id) { it.bytesDownloaded = downloaded }
                            eventBus.emit(
                                "download.progress",
                                org.json.JSONObject().put("id", id)
                                    .put("state", "running")
                                    .put("bytesDownloaded", downloaded)
                                    .put("totalBytes", total).toString()
                            )
                        }
                    }
                }
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            update(id) { it.state = "completed"; it.bytesDownloaded = dest.length() }
            eventBus.emit("download.progress", org.json.JSONObject().put("id", id).put("state", "completed").toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (pauseFlags[id] == true) {
                update(id) { it.state = "paused" }
            } else if (cancelFlags[id] != true) {
                update(id) { it.state = "paused" } // scope 破棄等による中断は paused として保持
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed: $id", e)
            update(id) { it.state = "failed"; it.error = e.message }
            eventBus.emit(
                "download.progress",
                org.json.JSONObject().put("id", id).put("state", "failed").put("error", e.message ?: "").toString()
            )
        }
    }

    private fun resolveDest(destPath: String): File {
        val root = dataRoot.canonicalFile
        val target = File(root, destPath.removePrefix("/")).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw MiniAppException("FILE_ACCESS_DENIED", "App Data 境界外へのダウンロードは禁止されています")
        }
        return target
    }

    fun destroy() {
        scope.cancel()
        jobs.clear()
    }

    companion object {
        private const val TAG = "MiniAppDownloadMgr"
    }
}
