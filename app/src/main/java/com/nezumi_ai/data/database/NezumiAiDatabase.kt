package com.nezumi_ai.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nezumi_ai.data.database.dao.AlarmDao
import com.nezumi_ai.data.database.dao.ChatSessionDao
import com.nezumi_ai.data.database.dao.MessageDao
import com.nezumi_ai.data.database.dao.SettingsDao
import com.nezumi_ai.data.database.entity.AlarmEntity
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.database.entity.SettingsEntity

@Database(
    entities = [ChatSessionEntity::class, MessageEntity::class, SettingsEntity::class, AlarmEntity::class],
    version = 11,
    exportSchema = false
)
abstract class NezumiAiDatabase : RoomDatabase() {
    
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun messageDao(): MessageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun alarmDao(): AlarmDao
    
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
                    // 開発中: スキーマ不一致時は再作成して起動クラッシュを回避
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
