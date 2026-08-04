package com.nezumi_ai

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.CacheManager
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.utils.LocaleHelper
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

    // i18n: Application の Context にもロケールを適用しておくと、
    // applicationContext 経由で getString() された場合も選択した言語で
    // リソースが引けるようになる。
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        PreferencesHelper.applyThemeMode(this)

        // デバッグタブでの事後閲覧用に、自プロセスの logcat を常時バックグラウンド収集する。
        //   サイズローテーションで古いログから自動的に消えていくため、
        //   ディスクを圧迫し続けることはない（詳細は LogcatRecorder 参照）。
        //   BuildConfig.DEBUG 時のみ動作させ、リリースビルドではオーバーヘッドを避ける。
        if (BuildConfig.DEBUG) {
            com.nezumi_ai.utils.LogcatRecorder.start(this)
        }

 // 起動直後フリーズ対策: Room DB インスタンスと SharedPreferences を
        //   バックグラウンド (IO) で先に warmup する。
        //   これにより ChatFragment.onViewCreated がメインスレッドで呼ぶ
        //   NezumiAiDatabase.getInstance() の synchronized(this) 内の
        //   Room.databaseBuilder().build() や、SharedPreferences の初回
        //   ディスク読み込みが UI スレッドを止めるのを防止する。
        //   fire-and-forget: 失敗しても既存の遅延初期化パスで復旧できる。
        warmupStorageAsync()
        
        // Initialize VOICEVOX (フラグが false の場合はスタブが返るだけで何もしない)
        voicevoxManager = VoicevoxManager(this)
        if (com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
            // ダウンロード完了ブロードキャストを受けて自動初期化するレシーバーを常駐させる
            registerVoicevoxModelReadyReceiver()
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
            // ファイルが揃っていない場合は自動初期化しない。
            // ファイル未取得のまま initialize() を呼ぶと、以前はここで同期ダウンロードが走り、
                //   UI 側のワーカー DL と並走して releaseInternal() と open が交错し、
                //   "Null pointer in rust value from Java" (VoiceModelFile.rsDrop) でクラッシュしていた。
            if (!voicevoxManager.isModelFileReady() || !voicevoxManager.isDictionaryReady()) {
                Log.i(TAG, "initializeVoicevox: files not ready; will initialize after Worker download completes")
                return@launch
            }
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

    /**
     * Room DB と SharedPreferences を IO スレッドで事前 warmup する。
     * これにより UI スレッドから初めて触る際のブロッキングを回避する。
     */
    private fun warmupStorageAsync() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                // Room の getInstance() は synchronized(this) 内で
                // databaseBuilder().build() を実行するため、
                // メインスレッドで走ると数十〜数百 ms 止まりうる。
                NezumiAiDatabase.getInstance(this@MyApplication)

                // よく使う SharedPreferences をあらかじめ読み込ませる。
                // getString/getBoolean の初回は該当ファイルの load 完了まで
                // ブロックするため、ここで一度触っておく。
                PreferencesHelper.getCurrentPresetId(this@MyApplication)
                PreferencesHelper.isStopKeyboardLearningEnabled(this@MyApplication)
                PreferencesHelper.isInitialSetupCompleted(this@MyApplication)
                // MCP: レジストリを初期ロードし、アクティブなプリセットの MCP サーバーに先行接続しておく。
                //   これにより初回ターンから tools/list が利用でき、 SSE 契約も開始される。
                runCatching {
                    val toolPrefs = com.nezumi_ai.data.inference.ToolPreferences(this@MyApplication)
                    val ids = toolPrefs.getActiveMcpServerIds()
                    if (ids.isNotEmpty()) {
                        com.nezumi_ai.data.mcp.McpToolRegistry
                            .get(this@MyApplication)
                            .refresh(ids, force = true)
                    }
                }.onFailure { Log.w(TAG, "MCP registry warmup failed (non-fatal)", it) }
                Log.d(TAG, "Storage warmup completed on IO thread")
            } catch (e: Exception) {
                Log.w(TAG, "Storage warmup failed (non-fatal)", e)
            }
        }
    }
    
    fun getVoicevoxManager(): VoicevoxManager = voicevoxManager

    /**
     * 任意の styleId への切替をアプリ全体に適用する。
     *
     * - styleId の保存
     * - 必要であれば .vvm の切替 (自動ダウンロード)
     * - 既に目的 .vvm がインストール済みならその場で自動初期化
     *
     * UI 側はこの API だけ呼べばよい（旧 setSelectedStyleId + 手動 initialize 両方を呼ぶ必要なし）。
     */
    fun selectVoicevoxStyle(styleId: Int) {
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) return
        val hostEntry = VoicevoxManager.catalogEntryForStyle(styleId)
            ?: run {
                Log.w(TAG, "selectVoicevoxStyle: unknown styleId=$styleId")
                return
            }
        val previousModel = voicevoxManager.getSelectedModelFileName()
        val sameModel = hostEntry.fileName == previousModel
        val needsDictionary = !voicevoxManager.isDictionaryReady()
        val needsModel = !voicevoxManager.isModelFileReady() || !sameModel

        // 別の .vvm へ切替える場合は setSelectedStyleId 内で releaseInternal() が走るので
        // 先に DL 必要性を確定させる。
        voicevoxManager.setSelectedStyleId(styleId)

        if (needsModel || needsDictionary) {
            // ダウンロードをキューし、自動初期化は Worker 完了後のブロードキャストに一本化する。
            //   ここで initialize() を先行させないのが重要: DL並走で rust の
            //   VoiceModelFile がごつごつになるのを防ぐ。
            val enqueued = ModelDownloadWorker.enqueueVoicevoxModel(
                context = this,
                fileName = hostEntry.fileName,
                url = hostEntry.url,
                displayName = hostEntry.displayName,
                needsDictionary = needsDictionary
            )
            Log.i(TAG, "selectVoicevoxStyle: enqueued=$enqueued fileName=${hostEntry.fileName} needsDictionary=$needsDictionary")
            return
        }

        // ボイスファイルも辞書も完備しているケースのみ、その場で初期化する。
        applicationScope.launch {
            voicevoxManager.initialize()
        }
    }

    private fun registerVoicevoxModelReadyReceiver() {
        val filter = IntentFilter(ModelDownloadWorker.ACTION_VOICEVOX_MODEL_READY)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ModelDownloadWorker.ACTION_VOICEVOX_MODEL_READY) return
                Log.i(TAG, "VOICEVOX model ready broadcast received. Auto-initializing.")
                applicationScope.launch {
                    // ダウンロード完了を受けた後の自動初期化。
                    // ファイルが本当に完成していることを確認してから呼ぶ。
                    if (!voicevoxManager.isModelFileReady() || !voicevoxManager.isDictionaryReady()) {
                        Log.w(TAG, "MODEL_READY 受信したがファイルが揃っていないため initialize をスキップ")
                        return@launch
                    }
                    // クリーンな状態から新しい .vvm をロードするため、一度 release してから initialize()
                    voicevoxManager.release()
                    voicevoxManager.initialize()
                }
            }
        }
        // Android 14+ は RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED の明示が必要
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    companion object {
        private const val TAG = "MyApplication"
    }
}
