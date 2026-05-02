package com.nezumi_ai

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Custom Application class for manual WorkManager initialization
 * to reduce Binder thread load (Binder スレッド負荷軽減)
 */
class MyApplication : Application() {
    private val applicationScope = CoroutineScope(Dispatchers.Default)
    
    override fun onCreate() {
        super.onCreate()
        PreferencesHelper.applyThemeMode(this)
        
        // Manual WorkManager initialization to avoid default initialization
        if (!WorkManager.isInitialized()) {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
            WorkManager.initialize(this, config)
        }
        
        // Phase 14: アプリ起動時にメディアクリーンアップを実行
        // 無効な URI や孤立したメディアファイルをクリーンアップ
        cleanupMediaOnStartup()
    }
    
    /**
     * アプリ起動時のメディアクリーンアップ処理
     * - 古いメディアファイルの自動削除
     * - メディアディレクトリの初期化
     * 
     * Background スレッドで実行（UI ブロッキング防止）
     */
    private fun cleanupMediaOnStartup() {
        applicationScope.launch {
            try {
                val mediaDir = MessageMediaStore.getMediaDir(this@MyApplication)
                val maxAgeMillis = 7L * 24 * 60 * 60 * 1000  // 7日以上古いファイルを削除
                val currentTime = System.currentTimeMillis()
                
                if (mediaDir.exists()) {
                    val files = mediaDir.listFiles() ?: emptyArray()
                    var deletedCount = 0
                    var totalSize = 0L
                    
                    for (file in files) {
                        if (file.isFile) {
                            val age = currentTime - file.lastModified()
                            
                            // 7日以上古いファイルを削除
                            if (age > maxAgeMillis) {
                                totalSize += file.length()
                                if (file.delete()) {
                                    deletedCount++
                                    Log.d(TAG, "Deleted old media file: ${file.name} (age: ${age / 1000 / 60} minutes)")
                                }
                            }
                        }
                    }
                    
                    if (deletedCount > 0) {
                        Log.i(TAG, "STARTUP_CLEANUP: Deleted $deletedCount old media files (${totalSize / 1024} KB)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during media cleanup on startup", e)
            }
        }
    }
    
    companion object {
        private const val TAG = "MyApplication"
    }
}
