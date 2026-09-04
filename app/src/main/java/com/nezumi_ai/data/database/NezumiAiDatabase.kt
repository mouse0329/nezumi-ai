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
import com.nezumi_ai.data.database.dao.ToolCallHistoryDao
import com.nezumi_ai.data.database.entity.AlarmEntity
import com.nezumi_ai.data.database.entity.ChatChunkEntity
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.database.entity.MemoryEntity
import com.nezumi_ai.data.database.entity.MemorySessionEntity
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.database.entity.PresetEntity
import com.nezumi_ai.data.database.entity.SettingsEntity
import com.nezumi_ai.data.database.entity.ToolCallHistoryEntity

@Database(
    entities = [
        ChatChunkEntity::class,
        ChatSessionEntity::class,
        MessageEntity::class,
        SettingsEntity::class,
        AlarmEntity::class,
        PresetEntity::class,
        MemoryEntity::class,
        MemorySessionEntity::class,
        ToolCallHistoryEntity::class
    ],
    version = 33,
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
    abstract fun toolCallHistoryDao(): ToolCallHistoryDao
    
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
                    .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33)
                    // 開発中: スキーマ不一致時は再作成して起動クラッシュを回避
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        


        private val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE memory ADD COLUMN rga_uid TEXT NOT NULL DEFAULT \'\'"
                )
                database.execSQL(
                    "ALTER TABLE memory ADD COLUMN rga_prev_uid TEXT"
                )
                database.execSQL("""
                    UPDATE memory SET rga_uid = (
                        lower(hex(randomblob(4))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(6)))
                    ) WHERE rga_uid = ''
                """.trimIndent())
            }
        }

        private val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE settings ADD COLUMN topP REAL NOT NULL DEFAULT 0.95")
                database.execSQL("ALTER TABLE settings ADD COLUMN llamaCppKvUnified INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE preset ADD COLUMN tool_calling_enabled INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "UPDATE preset SET tool_calling_enabled = 1 WHERE id = 'default_nezumi_ai'"
                )
            }
        }

        /**
         * Bug fix(#7): プリセットに sort_order / tags_csv カラムを追加し、
         * 「並び替え・タグフィルタ・名前検索」を DB レベルでサポートする。
         */
        private val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE preset ADD COLUMN sort_order INTEGER NOT NULL DEFAULT " + Long.MAX_VALUE.toString()
                )
                database.execSQL(
                    "ALTER TABLE preset ADD COLUMN tags_csv TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
 * 応答バリアント機能 (同一プロンプトに対する複数候補回答) のためのカラム追加。
         * - parentUserMessageId: assistant メッセージの元になった user メッセージの id (nullable)
         * - variantIndex: 同じ parent を共有する応答の並び順 (初回=0)
         */
        private val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE message ADD COLUMN parentUserMessageId INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE message ADD COLUMN variantIndex INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

 // v28: TTFT (最初のトークンまでの時間) カラムを追加
        private val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE message ADD COLUMN ttftMs INTEGER"
                )
            }
        }

        // v29: プリセットごとの MCP サーバー参照を保持するカラムを追加
        private val MIGRATION_28_29 = object : androidx.room.migration.Migration(28, 29) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE preset ADD COLUMN mcp_server_ids TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        private val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Performance optimization settings
                database.execSQL("ALTER TABLE settings ADD COLUMN mtpEnabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE settings ADD COLUMN mtpDraftTokens INTEGER NOT NULL DEFAULT 5")
                database.execSQL("ALTER TABLE settings ADD COLUMN flashAttentionEnabled INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE settings ADD COLUMN dynamicBatchSizeEnabled INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE settings ADD COLUMN promptBatchSize INTEGER NOT NULL DEFAULT 512")
                database.execSQL("ALTER TABLE settings ADD COLUMN generationBatchSize INTEGER NOT NULL DEFAULT 128")
                database.execSQL("ALTER TABLE settings ADD COLUMN kvCacheOptimizationEnabled INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE settings ADD COLUMN contextShiftEnabled INTEGER NOT NULL DEFAULT 1")
            }
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

        /**
         * v30: 新規インストール時のメモリ保存モードデフォルトを
         *   'LLM' → 'TOOL_ONLY' へ切り替える。
         *
         * 既存ユーザーの選択は尊重するため、UPDATE は行わない。
         * 新規レコードの DEFAULT 値は SettingsEntity / SettingsDao の
         * insertDefaultIfEmpty() 側で TOOL_ONLY を使うように切り替える。
         */
        private val MIGRATION_29_30 = object : androidx.room.migration.Migration(29, 30) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // no-op migration: schema は変わらず、バージョンバンプのみ。
                // (新規導入カラムはこの先予定しているためのプレースホルダー)
            }
        }

        private val MIGRATION_32_33 = object : androidx.room.migration.Migration(32, 33) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE settings ADD COLUMN llamaCppGpuBackend TEXT NOT NULL DEFAULT 'CPU'")
            }
        }

        private val MIGRATION_31_32 = object : androidx.room.migration.Migration(31, 32) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE preset ADD COLUMN skills_enabled INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE preset ADD COLUMN hidden_skill_names TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private val MIGRATION_30_31 = object : androidx.room.migration.Migration(30, 31) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS tool_call_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sessionId INTEGER NOT NULL,
                        sessionName TEXT,
                        toolName TEXT NOT NULL,
                        query TEXT,
                        success INTEGER NOT NULL DEFAULT 1,
                        resultSummary TEXT,
                        messageId INTEGER
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_history_timestamp ON tool_call_history(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_history_sessionId ON tool_call_history(sessionId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_history_toolName ON tool_call_history(toolName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_history_query ON tool_call_history(query)")
            }
        }
    }
}
