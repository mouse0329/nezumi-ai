package com.nezumi_ai.desktop.data.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

/**
 * データベースファクトリー (Exposed使用)
 * Android RoomのようなAPI感覚で使用可能
 */
object DatabaseFactory {
    private lateinit var database: Database
    
    fun init() {
        val dbPath = getDbPath()
        database = Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC"
        )
        
        transaction(database) {
            SchemaUtils.create(Messages, ChatSessions)
        }
        
        println("Database initialized at: $dbPath")
    }
    
    private fun getDbPath(): String {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".nezumi-ai")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return File(appDir, "nezumi-ai.db").absolutePath
    }
}

// テーブル定義 (Android Roomのエンティティに相当)
object Messages : Table("messages") {
    val id = varchar("id", 36)
    val sessionId = varchar("session_id", 36)
    val content = text("content")
    val isUser = bool("is_user")
    val timestamp = long("timestamp")
    
    override val primaryKey = PrimaryKey(id)
}

object ChatSessions : Table("chat_sessions") {
    val id = varchar("id", 36)
    val title = varchar("title", 255)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    
    override val primaryKey = PrimaryKey(id)
}
