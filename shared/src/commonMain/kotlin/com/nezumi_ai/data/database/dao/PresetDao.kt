package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.PresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: PresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(presets: List<PresetEntity>)

    @Update
    suspend fun update(preset: PresetEntity)

    @Delete
    suspend fun delete(preset: PresetEntity)

    /**
     * 並び順: sort_order 昇順を最優先に、不明ときは created_at 昇順を使う。
     * ドラッグ&ドロップで自由に並び替えられるように、is_default による固定は行わない。
     */
    @Query("SELECT * FROM preset ORDER BY sort_order ASC, is_locked ASC, created_at ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM preset ORDER BY sort_order ASC, is_locked ASC, created_at ASC")
    suspend fun getAll(): List<PresetEntity>

    /** 名前検索・タグフィルタ用（UI 側で差分ストリームを処理するために Flow も提供）。 */
    @Query("SELECT * FROM preset WHERE name LIKE :pattern OR tags_csv LIKE :pattern ORDER BY sort_order ASC, is_locked ASC, created_at ASC")
    fun searchByNameOrTag(pattern: String): Flow<List<PresetEntity>>

    /** 並び替え UI 向けに sort_order を一括更新するための軽量クエリ。 */
    @Query("UPDATE preset SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Long, updatedAt: Long)

    /** タグ保存用の軽量クエリ。 */
    @Query("UPDATE preset SET tags_csv = :tagsCsv, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTagsCsv(id: String, tagsCsv: String, updatedAt: Long)

    @Query("SELECT * FROM preset WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PresetEntity?

    @Query("SELECT * FROM preset WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): PresetEntity?

    @Query("SELECT COUNT(*) FROM preset")
    suspend fun count(): Int

    @Query("DELETE FROM preset WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
