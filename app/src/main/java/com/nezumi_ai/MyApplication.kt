package com.nezumi_ai

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.CacheManager
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.voicevox.VoicevoxManager
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
    private lateinit var voicevoxManager: VoicevoxManager
    
    override fun onCreate() {
        super.onCreate()
        PreferencesHelper.applyThemeMode(this)
        
        // Initialize VOICEVOX (フラグが false の場合はスタブが返るだけで何もしない)
        voicevoxManager = VoicevoxManager(this)
        if (com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
            initializeVoicevox()
        } else {
            Log.i(TAG, "VOICEVOX is disabled (VoicevoxFeatureFlag.ENABLED=false). Skipping initialization.")
        }
        
        // CacheManager を初期化（前回ロードしたモデル名の復元）
        CacheManager.initialize(this)
        
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

        // Initialize embedding backend detection (ONNX model presence) in background
        applicationScope.launch(Dispatchers.IO) {
            try {
                com.nezumi_ai.data.memory.MemoryTextEmbedder.initializeAsync(this@MyApplication)
                Log.d(TAG, "MemoryTextEmbedder initialized (background)")
            } catch (e: Exception) {
                Log.w(TAG, "MemoryTextEmbedder initialization failed", e)
            }
        }

        initializePresetDefaults()
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
                    val files = mediaDir.listFiles() ?: emptyArray<File>()
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

    private fun initializeVoicevox() {
        applicationScope.launch {
            val success = voicevoxManager.initialize()
            if (success) {
                Log.i(TAG, "VOICEVOX initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize VOICEVOX")
            }
        }
    }

    private fun initializePresetDefaults() {
        applicationScope.launch {
            try {
                val database = NezumiAiDatabase.getInstance(this@MyApplication)
                PresetRepository(database.presetDao(), this@MyApplication)
                    .initializeDefaultsIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize preset defaults", e)
            }
        }
    }
    
    fun getVoicevoxManager(): VoicevoxManager = voicevoxManager
    
    companion object {
        private const val TAG = "MyApplication"
    }
}
