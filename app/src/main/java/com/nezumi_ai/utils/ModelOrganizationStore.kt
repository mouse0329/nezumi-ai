package com.nezumi_ai.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * モデル管理画面の「フォルダ整理・名前検索フィルタ・並び替え」の永続化レイヤ。
 *
 * Bug fix(#8): モデル数が増えるとフラットな一覧では辛いので、ユーザーが「フォルダ」
 * （任意のラベル）にモデルをドラッグして仕分けでき、検索・並び替え条件も持続させる。
 *
 * 既存の `ImportedModelCapabilityStore` は「モデル個別の能力フラグ」を扱うため、
 * UI 表示専用の組織情報は本ストアに切り出して責務を分離する。
 *
 * - フォルダ: 文字列ラベルの順序付きリスト。順序は UI 上の並び順と一致する。
 * - 各モデル: 所属フォルダ名（または `""` で未分類）と表示用のソート優先度を持つ。
 * - グローバル設定: 現在の検索クエリ、並び替えキー、昇降順を保存する（再起動後も復元）。
 */
object ModelOrganizationStore {
    private const val PREF_NAME = "model_organization"
    private const val KEY_FOLDERS = "folders_json"
    private const val KEY_ASSIGNMENTS = "assignments_json"
    private const val KEY_SORT_KEY = "sort_key"
    private const val KEY_SORT_ASC = "sort_ascending"
    private const val KEY_SEARCH_QUERY = "search_query"

    enum class SortKey { NAME, SIZE, RECENT, FOLDER }

    data class Assignment(
        val folder: String = "",
        val sortOrder: Long = Long.MAX_VALUE
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ---- フォルダ操作 ----

    fun listFolders(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_FOLDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setFolders(context: Context, folders: List<String>) {
        val arr = JSONArray()
        folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }

    fun addFolder(context: Context, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = listFolders(context).toMutableList()
        if (current.contains(trimmed)) return
        current.add(trimmed)
        setFolders(context, current)
    }

    fun renameFolder(context: Context, oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || oldName == trimmed) return
        val folders = listFolders(context).toMutableList()
        val idx = folders.indexOf(oldName)
        if (idx < 0) return
        folders[idx] = trimmed
        setFolders(context, folders)
        // 既存の assignment を新名に書き換える。
        val map = readAssignments(context).toMutableMap()
        map.forEach { (id, a) ->
            if (a.folder == oldName) map[id] = a.copy(folder = trimmed)
        }
        writeAssignments(context, map)
    }

    fun removeFolder(context: Context, name: String) {
        val folders = listFolders(context).toMutableList()
        if (!folders.remove(name)) return
        setFolders(context, folders)
        // assignment から該当フォルダを未分類に戻す。
        val map = readAssignments(context).toMutableMap()
        map.forEach { (id, a) ->
            if (a.folder == name) map[id] = a.copy(folder = "")
        }
        writeAssignments(context, map)
    }

    // ---- モデル割り当て ----

    private fun readAssignments(context: Context): Map<String, Assignment> {
        val raw = prefs(context).getString(KEY_ASSIGNMENTS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, Assignment>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = obj.optJSONObject(k) ?: continue
                out[k] = Assignment(
                    folder = a.optString("folder", ""),
                    sortOrder = a.optLong("sortOrder", Long.MAX_VALUE)
                )
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun writeAssignments(context: Context, map: Map<String, Assignment>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, JSONObject().apply {
                put("folder", v.folder)
                put("sortOrder", v.sortOrder)
            })
        }
        prefs(context).edit().putString(KEY_ASSIGNMENTS, obj.toString()).apply()
    }

    fun getAssignment(context: Context, modelId: String): Assignment {
        return readAssignments(context)[modelId] ?: Assignment()
    }

    fun setFolder(context: Context, modelId: String, folder: String) {
        val map = readAssignments(context).toMutableMap()
        val current = map[modelId] ?: Assignment()
        map[modelId] = current.copy(folder = folder.trim())
        writeAssignments(context, map)
    }

    fun setSortOrder(context: Context, modelId: String, sortOrder: Long) {
        val map = readAssignments(context).toMutableMap()
        val current = map[modelId] ?: Assignment()
        map[modelId] = current.copy(sortOrder = sortOrder)
        writeAssignments(context, map)
    }

    /** ドラッグ&ドロップで得た順序を `(index+1)` で順に振り直す。 */
    fun reorder(context: Context, orderedIds: List<String>) {
        val map = readAssignments(context).toMutableMap()
        orderedIds.forEachIndexed { index, id ->
            val current = map[id] ?: Assignment()
            map[id] = current.copy(sortOrder = (index + 1).toLong())
        }
        writeAssignments(context, map)
    }

    // ---- 検索・並び替え UI 状態 ----

    fun getSearchQuery(context: Context): String = prefs(context).getString(KEY_SEARCH_QUERY, "") ?: ""
    fun setSearchQuery(context: Context, q: String) {
        prefs(context).edit().putString(KEY_SEARCH_QUERY, q).apply()
    }

    fun getSortKey(context: Context): SortKey {
        val raw = prefs(context).getString(KEY_SORT_KEY, SortKey.NAME.name) ?: SortKey.NAME.name
        return runCatching { SortKey.valueOf(raw) }.getOrDefault(SortKey.NAME)
    }

    fun setSortKey(context: Context, key: SortKey) {
        prefs(context).edit().putString(KEY_SORT_KEY, key.name).apply()
    }

    fun isSortAscending(context: Context): Boolean = prefs(context).getBoolean(KEY_SORT_ASC, true)
    fun setSortAscending(context: Context, ascending: Boolean) {
        prefs(context).edit().putBoolean(KEY_SORT_ASC, ascending).apply()
    }

    // ---- 汎用フィルタ・ソートユーティリティ ----

    /**
     * モデル管理画面側で、 `(モデルID, 表示名, サイズバイト数, 更新時刻)` の Quad リストを渡すと、
     * 検索クエリ・現在の並び替え条件・現在選択中のフォルダで絞り込んだ結果を返す。
     *
     * `folderFilter` が空文字列のときは「すべて」、 "__unassigned__" のときは未分類のみを返す。
     */
    data class ModelKey(
        val modelId: String,
        val displayName: String,
        val sizeBytes: Long = 0,
        val lastUpdated: Long = 0
    )

    const val FOLDER_UNASSIGNED = "__unassigned__"

    fun filterAndSort(
        context: Context,
        items: List<ModelKey>,
        folderFilter: String = "",
        query: String = getSearchQuery(context),
        sortKey: SortKey = getSortKey(context),
        ascending: Boolean = isSortAscending(context)
    ): List<ModelKey> {
        val assignments = readAssignments(context)
        val q = query.trim().lowercase()
        val filtered = items.filter { item ->
            val a = assignments[item.modelId] ?: Assignment()
            val folderMatch = when (folderFilter) {
                "" -> true
                FOLDER_UNASSIGNED -> a.folder.isEmpty()
                else -> a.folder == folderFilter
            }
            if (!folderMatch) return@filter false
            if (q.isEmpty()) return@filter true
            item.displayName.lowercase().contains(q) || item.modelId.lowercase().contains(q)
        }
        val cmp = Comparator<ModelKey> { lhs, rhs ->
            val la = assignments[lhs.modelId] ?: Assignment()
            val ra = assignments[rhs.modelId] ?: Assignment()
            val primary = when (sortKey) {
                SortKey.NAME -> lhs.displayName.compareTo(rhs.displayName, ignoreCase = true)
                SortKey.SIZE -> lhs.sizeBytes.compareTo(rhs.sizeBytes)
                SortKey.RECENT -> rhs.lastUpdated.compareTo(lhs.lastUpdated) // 新しい順がデフォルト
                SortKey.FOLDER -> {
                    val byFolder = la.folder.compareTo(ra.folder)
                    if (byFolder != 0) byFolder else la.sortOrder.compareTo(ra.sortOrder)
                }
            }
            if (primary != 0) primary else la.sortOrder.compareTo(ra.sortOrder)
        }
        val sorted = filtered.sortedWith(cmp)
        return if (ascending) sorted else sorted.reversed()
    }
}
