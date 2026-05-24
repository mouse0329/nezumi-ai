package com.nezumi_ai.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nezumi_ai.data.database.dao.AlarmDao
import com.nezumi_ai.data.database.dao.ChatChunkDao
import com.nezumi_ai.data.database.dao.ChatSessionDao
import com.nezumi_ai.data.database.dao.MemoryDao
import com.nezumi_ai.data.database.dao.MemorySessionDao
import com.nezumi_ai.data.database.dao.MessageDao
import com.nezumi_ai.data.database.dao.PresetDao
import com.nezumi_ai.data.database.dao.SettingsDao
import com.nezumi_ai.data.database.entity.AlarmEntity
import com.nezumi_ai.data.database.entity.ChatChunkEntity
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.database.entity.MemoryEntity
import com.nezumi_ai.data.database.entity.MemorySessionEntity
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.database.entity.SettingsEntity

@Database(
    entities = [
        ChatChunkEntity::class,
        ChatSessionEntity::class,
        MessageEntity::class,
        SettingsEntity::class,
        AlarmEntity::class,
        PresetEntity::class,
        MemoryEntity::class,
        MemorySessionEntity::class
    ],
    version = 21,
    exportSchema = false
)
abstract class NezumiAiDatabase : RoomDatabase() {
    
    abstract fun chatChunkDao(): ChatChunkDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun messageDao(): MessageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun alarmDao(): AlarmDao
    abstract fun presetDao(): PresetDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memorySessionDao(): MemorySessionDao
    
    companion object {
        @Volatile
        private var instance: NezumiAiDatabase? = null
        
        fun getInstance(context: Context): NezumiAiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NezumiAiDatabase::class.java,
                    "nezumi_ai.db"
                )
                    .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
                    // 開発中: スキーマ不一致時は再作成して起動クラッシュを回避
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        

        private val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_chunk (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        message_id INTEGER NOT NULL,
                        session_id INTEGER NOT NULL,
                        chunk_text TEXT NOT NULL,
                        embedding BLOB NOT NULL,
                        norm REAL NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(message_id) REFERENCES message(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_chunk_message_id ON chat_chunk(message_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chat_chunk_session_id ON chat_chunk(session_id)")
            }
        }

        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // isPinned カラムを追加（デフォルト値: false）
                database.execSQL("ALTER TABLE chat_session ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS preset (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT NOT NULL,
                        system_prompt TEXT NOT NULL,
                        model_id TEXT NOT NULL,
                        enabled_tools TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        is_default INTEGER NOT NULL,
                        memory_enabled INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        is_locked INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content TEXT NOT NULL,
                        embedding BLOB NOT NULL,
                        norm REAL NOT NULL,
                        importance REAL NOT NULL,
                        access_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        last_accessed_at INTEGER NOT NULL,
                        is_deleted INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        session_id TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_session (
                        id TEXT NOT NULL PRIMARY KEY,
                        last_extracted_turn INTEGER NOT NULL,
                        pending_extraction INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memory_is_deleted ON memory(is_deleted)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memory_session_id ON memory(session_id)")
            }
        }

        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE settings ADD COLUMN memorySaveMode TEXT NOT NULL DEFAULT 'LLM'")
            }
        }
    }
}
