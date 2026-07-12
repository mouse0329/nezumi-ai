package com.nezumi_ai.presentation.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.inference.EngineManager
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.sd.safety.PromptFilter
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.ImageGenerationNotificationManager
import com.nezumi_ai.sd.ProgressData
import com.nezumi_ai.sd.safety.SafetyResult
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.utils.PreferencesHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nezumi_ai.sd.GenerationQueue
import com.nezumi_ai.sd.GenerationQueueItem
import com.nezumi_ai.sd.ImageGenerationMetadata
import com.nezumi_ai.sd.SdScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import org.json.JSONObject

class ImageGenViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ImageGenViewModel"
    }

    private val settingsRepository = SettingsRepository.fromDatabase(
        NezumiAiDatabase.getInstance(application)
    )

    private val _modelPath = MutableStateFlow(PreferencesHelper.getSdModelPath(application))
    val modelPath: StateFlow<String> = _modelPath.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _selectedModelIndex = MutableStateFlow(0)
    val selectedModelIndex: StateFlow<Int> = _selectedModelIndex.asStateFlow()

    init {
        Log.d(TAG, "[ImageGen] init: Starting initialization")
        loadAvailableModels()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "[ImageGen] init: Checking safety model readiness...")
                if (!ensureSafetyModelReady(getApplication())) {
                    Log.e(TAG, "[ImageGen] init: Safety model download failed or timeout")
                } else {
                    Log.d(TAG, "[ImageGen] init: Safety model is ready")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[ImageGen] init: Error during safety model check", e)
            }
        }
    }

    private val _backendInfo = MutableStateFlow("")
    val backendInfo: StateFlow<String> = _backendInfo.asStateFlow()

    private val _selectedBackend = MutableStateFlow(PreferencesHelper.getSdBackend(application))
    val selectedBackend: StateFlow<String> = _selectedBackend.asStateFlow()

    // Perf fix: 开発ビルド以外ではホットパスのログを抑制する。
    //   isProbableSdModelDir は何百行も logcat に刷き、main thread を圧迫していた。
    //   (ログ情報は isDebugLoggable() の内側に既に集約されているとしても
     //   BuildConfig.DEBUG のときだけ verbose にする。)
    private val verboseModelScan = com.nezumi_ai.BuildConfig.DEBUG

    // Perf fix: フラグメント復帰 (onResume) の度にフルスキャンされると、
    //   生成直前に重い File スキャンが走り UI がジャンクしていた (Davey! 800ms)。
    //   一定間隔以内の連続呼び出しをデバウンスする。
    @Volatile private var lastModelScanAtMs: Long = 0L
    private var pendingScanJob: kotlinx.coroutines.Job? = null

    private fun loadAvailableModels(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastModelScanAtMs < 1500L && _availableModels.value.isNotEmpty()) {
            // 直近 1.5s 以内で既にスキャン済みならスキップ
            return
        }
        pendingScanJob?.cancel()
        pendingScanJob = viewModelScope.launch(Dispatchers.IO) {
            val models = mutableListOf<String>()
            if (verboseModelScan) Log.d(TAG, "[loadAvailableModels] Starting model search")
            // ダウンロード済み画像生成モデルディレクトリ
            val sdModelsDir = File(getApplication<Application>().filesDir, "sd_models")
            sdModelsDir.listFiles()?.forEach { file ->
                if (isProbableSdModelDir(file)) {
                    if (verboseModelScan) Log.d(TAG, "[loadAvailableModels] ✓ ${file.absolutePath}")
                    models.add(file.absolutePath)
                }
            }
            // アプリ専用ディレクトリ
            val appDir = getApplication<Application>().getExternalFilesDir(null)
            appDir?.listFiles()?.forEach { file ->
                if (isProbableSdModelDir(file)) {
                    if (verboseModelScan) Log.d(TAG, "[loadAvailableModels] ✓ appDir: ${file.absolutePath}")
                    models.add(file.absolutePath)
                }
            }
            // インポート済みモデルディレクトリ
            val importedDir = File(getApplication<Application>().filesDir, "models/imported")
            importedDir.listFiles()?.forEach { file ->
                if (isProbableSdModelDir(file)) {
                    if (verboseModelScan) Log.d(TAG, "[loadAvailableModels] ✓ imported: ${file.absolutePath}")
                    models.add(file.absolutePath)
                }
            }
            Log.i(TAG, "[loadAvailableModels] Total models found: ${models.size}")
            lastModelScanAtMs = System.currentTimeMillis()
            _availableModels.value = models
            // 現在のモデルパスが一覧にあればインデックスを設定
            val currentPath = _modelPath.value
            val index = models.indexOf(currentPath)
            if (index >= 0) {
                _selectedModelIndex.value = index
            } else if (models.isNotEmpty()) {
                _selectedModelIndex.value = 0
                _modelPath.value = models[0]
                PreferencesHelper.setSdModelPath(getApplication(), models[0])
            } else {
                _selectedModelIndex.value = 0
                _modelPath.value = ""
                PreferencesHelper.setSdModelPath(getApplication(), "")
            }
            updateBackendInfo()
        }
    }

    private fun isProbableSdModelDir(file: File): Boolean {
        if (!file.isDirectory) return false

        val files = file.listFiles() ?: return false
        // ネスト構造チェック: 1つのサブディレクトリのみの場合、そちらを使う
        if (files.size == 1 && files[0].isDirectory) {
            return isProbableSdModelDir(files[0])
        }

        // MNN形式チェック
        val hasMnnFiles = File(file, "unet.mnn").exists() &&
                         (File(file, "clip.mnn").exists() || File(file, "clip_v2.mnn").exists()) &&
                         File(file, "vae_decoder.mnn").exists() &&
                         File(file, "tokenizer.json").exists()

        // QNN形式チェック
        val hasQnnFiles = File(file, "unet.bin").exists() &&
                         (File(file, "clip.bin").exists() || File(file, "clip.mnn").exists()) &&
                         File(file, "vae_decoder.bin").exists() &&
                         File(file, "tokenizer.json").exists()

        val result = hasMnnFiles || hasQnnFiles
        if (verboseModelScan) {
            Log.d(TAG, "[isProbableSdModelDir] ${file.name}: mnn=$hasMnnFiles qnn=$hasQnnFiles -> $result")
        }
        return result
    }
    
    private fun detectModelFormat(path: String): String {
        val dir = File(path)
        if (!dir.isDirectory) return "Unknown"

        // NPU (QNN) 対応は廃止。unet.bin (旧 QNN 形式) が残っていても
        //   本エンジンでは使えないため表示上は「非対応形式」と伝え、
        //   MNN 形式のみを推論対象として扱う。
        val hasMnn = File(dir, "unet.mnn").exists()
        val hasQnn = File(dir, "unet.bin").exists()

        return when {
            hasMnn -> "MNN (CPU/GPU)"
            hasQnn -> "旧 QNN 形式 (非対応)"
            else -> "Unknown"
        }
    }
    
    private fun updateBackendInfo() {
        val path = _modelPath.value
        if (path.isEmpty()) {
            _backendInfo.value = ""
            return
        }
        
        val format = detectModelFormat(path)
        _backendInfo.value = "$format | LocalDream"
    }

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val _negativePrompt = MutableStateFlow("")
    val negativePrompt: StateFlow<String> = _negativePrompt.asStateFlow()

    private val _steps = MutableStateFlow(PreferencesHelper.getSdSteps(application))
    val steps: StateFlow<Int> = _steps.asStateFlow()

    private val _cfg = MutableStateFlow(PreferencesHelper.getSdCfg(application))
    val cfg: StateFlow<Float> = _cfg.asStateFlow()

    private val _sizePx = MutableStateFlow(512)
    val sizePx: StateFlow<Int> = _sizePx.asStateFlow()

    private val _seed = MutableStateFlow(-1L)
    val seed: StateFlow<Long> = _seed.asStateFlow()

    private val _scheduler = MutableStateFlow(SdScheduler.fromId(PreferencesHelper.getSdScheduler(application)))
    val scheduler: StateFlow<SdScheduler> = _scheduler.asStateFlow()

    private val _resultBitmap = MutableStateFlow<Bitmap?>(null)
    val resultBitmap: StateFlow<Bitmap?> = _resultBitmap.asStateFlow()

    private val _queueResultBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val queueResultBitmaps: StateFlow<List<Bitmap>> = _queueResultBitmaps.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _progressData = MutableStateFlow<ProgressData?>(null)
    val progressData: StateFlow<ProgressData?> = _progressData.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    private val _safetyVerdict = MutableStateFlow<SafetyResult.Verdict?>(null)
    val safetyVerdict: StateFlow<SafetyResult.Verdict?> = _safetyVerdict.asStateFlow()

    private val _safetyDownloading = MutableStateFlow(false)
    val safetyDownloading: StateFlow<Boolean> = _safetyDownloading.asStateFlow()

    // 0f..1f、-1f = 不明
    private val _safetyProgress = MutableStateFlow(-1f)
    val safetyProgress: StateFlow<Float> = _safetyProgress.asStateFlow()

    private val _safetyTotalBytes = MutableStateFlow(0L)
    val safetyTotalBytes: StateFlow<Long> = _safetyTotalBytes.asStateFlow()

    // ============ 一括生成キュー機能 ============
    private val _generationQueue = MutableStateFlow(GenerationQueue())
    val generationQueue: StateFlow<GenerationQueue> = _generationQueue.asStateFlow()

    private val _queueProgress = MutableStateFlow<Pair<Int, Int>?>(null)  // (現在, 合計)
    val queueProgress: StateFlow<Pair<Int, Int>?> = _queueProgress.asStateFlow()

    private val _isQueueRunning = MutableStateFlow(false)
    val isQueueRunning: StateFlow<Boolean> = _isQueueRunning.asStateFlow()

    private var queueRunJob: Job? = null
    // ==========================================

    var lastSavedInternalUri: String? = null
    private var generateJob: Job? = null

    fun refreshAvailableModels() {
        loadAvailableModels()
    }

    fun setSelectedModelIndex(index: Int) {
        if (index in _availableModels.value.indices) {
            _selectedModelIndex.value = index
            val path = _availableModels.value[index]
            _modelPath.value = path
            PreferencesHelper.setSdModelPath(getApplication(), path)
            updateBackendInfo()
        }
    }

    fun setSelectedBackend(backend: String) {
        val previous = _selectedBackend.value
        _selectedBackend.value = backend
        PreferencesHelper.setSdBackend(getApplication(), backend)
        updateBackendInfo()
        // ★ Bug fix: backend を切り替えたら即座に既存の LocalDream サーバーを停止し、
        //   次回 generate() 時に新 backend で起動し直されるようにする。
        //   これをしないと「CPU に切り替えても GPU のまま」バグが再発する。
        if (!previous.equals(backend, ignoreCase = true)) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { EngineManager.releaseSdKeepNone() }
            }
        }
    }

    fun setModelPath(p: String) {
        _modelPath.value = p
        PreferencesHelper.setSdModelPath(getApplication(), p)
        // インデックスを更新
        val index = _availableModels.value.indexOf(p)
        if (index >= 0) {
            _selectedModelIndex.value = index
        }
    }

    fun setPrompt(p: String) {
        _prompt.value = p
    }

    fun setNegativePrompt(p: String) {
        _negativePrompt.value = p
    }

    fun setSteps(s: Int) {
        val coerced = s.coerceIn(1, 50)
        _steps.value = coerced
        PreferencesHelper.setSdSteps(getApplication(), coerced)
    }

    fun setCfg(c: Float) {
        val coerced = c.coerceIn(1f, 20f)
        _cfg.value = coerced
        PreferencesHelper.setSdCfg(getApplication(), coerced)
    }

    fun setSize(s: Int) {
        val supportedSizes = listOf(128, 192, 256, 320, 384, 448, 512)
        _sizePx.value = supportedSizes.minByOrNull { kotlin.math.abs(it - s) } ?: 512
    }

    fun setSeed(s: Long) {
        _seed.value = s
    }

    fun setScheduler(scheduler: SdScheduler) {
        _scheduler.value = scheduler
        PreferencesHelper.setSdScheduler(getApplication(), scheduler.id)
    }

    private var isCancelling = false
    private var generationWakeLock: PowerManager.WakeLock? = null

    fun clearSnackbar() {
        _snackbar.value = null
    }

    private fun notificationPromptPreview(prompt: String): String {
        val trimmed = prompt.trim().replace("\n", " ")
        return if (trimmed.length <= 48) trimmed else trimmed.take(48) + "…"
    }

    private fun acquireGenerationWakeLock() {
        try {
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return
            if (generationWakeLock?.isHeld == true) return
            generationWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "nezumi_ai:ImageGen"
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire image generation WakeLock", e)
        }
    }

    private fun releaseGenerationWakeLock() {
        try {
            generationWakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release image generation WakeLock", e)
        } finally {
            generationWakeLock = null
        }
    }

    /**
     * 既存の snackbar 表示機構をそのまま使い、同一メッセージでも再表示できるようにする。
     */
    private fun showImageGenError(message: String) {
        if (_snackbar.value == message) {
            _snackbar.value = null
        }
        _snackbar.value = message
    }

    fun cancel() {
        if (isCancelling) {
            Log.w(TAG, "[ImageGen] cancel() already in progress, ignoring")
            return
        }
        isCancelling = true
        Log.i(TAG, "[ImageGen] cancel() called")
        
        EngineManager.cancelCurrentGeneration(viewModelScope)
        generateJob?.cancel()
        queueRunJob?.cancel()
        _isQueueRunning.value = false
        _loading.value = false
        _currentStep.value = 0
        _progressData.value = null
        val app = getApplication<Application>()
        ImageGenerationNotificationManager.showError(
            app,
            ImageGenerationNotificationManager.singleNotificationId(),
            "画像生成を停止しました",
            "進行中の画像生成をキャンセルしました"
        )
        ImageGenerationNotificationManager.cancelQueue(app)
        releaseGenerationWakeLock()
        Log.i(TAG, "[ImageGen] cancel() completed")
    }

    private suspend fun ensureSafetyModelReady(app: Application): Boolean {
        if (!com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED) return true
        if (ModelDownloadWorker.isSafetyModelUsable(app)) return true

        Log.i(TAG, "[ImageGen] Safety model missing, triggering download...")
        _safetyDownloading.value = true
        _safetyProgress.value = -1f
        _snackbar.value = "セーフティモデルをダウンロード中です…"

        val success = ModelDownloadWorker.awaitSafetyModelReady(
            app,
            onProgress = { downloaded, total ->
                if (total > 0L) {
                    _safetyTotalBytes.value = total
                    _safetyProgress.value = downloaded.toFloat() / total.toFloat()
                }
            }
        )

        _safetyDownloading.value = false
        _safetyProgress.value = -1f
        if (!success) {
            Log.e(TAG, "[ImageGen] Safety model download failed or timeout")
            _snackbar.value = "セーフティモデルのダウンロードがタイムアウトしました"
        }
        return success
    }

    fun generate() {
        generateJob?.cancel()
        generateJob = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val path = _modelPath.value.trim()
        if (path.isEmpty() || !File(path).isDirectory) {
            _snackbar.value = app.getString(com.nezumi_ai.R.string.image_gen_err_model_missing)
            return@launch
        }
        val pr = _prompt.value.trim()
        if (pr.isEmpty()) {
            _snackbar.value = app.getString(com.nezumi_ai.R.string.image_gen_err_prompt_empty)
            return@launch
        }

        Log.d(TAG, "[ImageGen] generate() Safety Check: enabled=${com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED}")

        // 前段：ViewModel 層でプロンプトを検査 — LocalDreamModule へ届く前にブロック
        if (com.nezumi_ai.BuildConfig.SAFETY_PROMPT_FILTER_ENABLED &&
            PromptFilter.check(pr) == PromptFilter.Result.BLOCK) {
            Log.w(TAG, "[ImageGen] Prompt blocked by PromptFilter")
            _safetyVerdict.value = SafetyResult.Verdict.BLOCK
            _snackbar.value = "プロンプトにポリシー違反のキーワードが含まれています"
            return@launch
        }
        // Safety model が未ダウンロードならダウンロード完了まで待つ (画像ガード有効時のみ)
        if (!ensureSafetyModelReady(app)) {
            return@launch
        }
        acquireGenerationWakeLock()
        _loading.value = true
        _resultBitmap.value = null
        _currentStep.value = 0
        _safetyVerdict.value = null
        isCancelling = false
        val manager = ModelManager.getInstance(app)
        val threads = settingsRepository.getLlamaCppThreads().coerceAtLeast(1)
        val sz = _sizePx.value
        val totalSteps = _steps.value
        val promptPreview = notificationPromptPreview(pr)
        ImageGenerationNotificationManager.showSingleProgress(
            app,
            step = 0,
            totalSteps = totalSteps,
            promptPreview = promptPreview,
            indeterminate = false
        )
        Log.d(TAG, "[ImageGen] requested size=$sz x $sz")
        var wasCancelled = false
        try {
            // Perf fix: EngineManager.acquireLocalDream() 内部で markSdActive 相当の処理が
            //   行われ、さらに LLM 側は下の finally で markLlmActive() によりクリーンアップされる。
            //   ここで unloadModel() を呼ぶと SD プロセスと LLM ネイティブの両方が
            //   連続して free ・ reload し、page-fault ソーシャルで 4~5GB の入れ替えが
            //   発生するため CPU バックエンドで UNET step が斜めに遅くなる。
            //   markLlmActive() の際にアンロードされるのでここでは呼ばない。
            Log.i(TAG, "[ImageGen] generate() starting, acquiring LocalDream engine")

            val backend = _selectedBackend.value
            val ld = EngineManager.acquireLocalDream(app, path, backend)
            
            _currentStep.value = 0
            _progressData.value = ProgressData(0, totalSteps, 0.0f)
            
            val result = ld.generateImageWithMetadata(
                prompt = pr,
                negativePrompt = _negativePrompt.value,
                width = sz,
                height = sz,
                steps = totalSteps,
                cfg = _cfg.value,
                seed = _seed.value,
                scheduler = _scheduler.value,
                onProgress = { step, steps, time ->
                    // 同じ step の連続更新で不必要な recomposition を避ける
                    val clamped = step.coerceAtMost(totalSteps)
                    if (_currentStep.value != clamped) {
                        _currentStep.value = clamped
                    }
                    val prev = _progressData.value
                    if (prev == null || prev.step != step || prev.totalSteps != steps || prev.time != time) {
                        _progressData.value = ProgressData(step, steps, time)
                    }
                    ImageGenerationNotificationManager.showSingleProgress(
                        app,
                        step = clamped,
                        totalSteps = steps,
                        promptPreview = promptPreview
                    )
                }
            )
            val bmp = result?.first
            val metadata = result?.second
            
            _currentStep.value = totalSteps
            _progressData.value = ProgressData(totalSteps, totalSteps, _progressData.value?.time ?: 0.0f)
            
            when {
                bmp == null && isCancelling -> {
                    wasCancelled = true
                    _snackbar.value = app.getString(com.nezumi_ai.R.string.image_gen_snackbar_cancelled)
                    ImageGenerationNotificationManager.showError(
                        app,
                        ImageGenerationNotificationManager.singleNotificationId(),
                        "画像生成を停止しました",
                        "単体画像の生成をキャンセルしました"
                    )
                }
                bmp == null -> {
                    val lastVerdict = ld.getLastSafetyVerdict()
                    if (lastVerdict == SafetyResult.Verdict.BLOCK) {
                        _safetyVerdict.value = SafetyResult.Verdict.BLOCK
                        showImageGenError("不適切なコンテンツが検出されたため表示を制限しました")
                        ImageGenerationNotificationManager.showError(
                            app,
                            ImageGenerationNotificationManager.singleNotificationId(),
                            "画像生成をブロックしました",
                            "セーフティガードにより結果の表示を制限しました"
                        )
                    } else {
                        showImageGenError("画像生成に失敗しました")
                        ImageGenerationNotificationManager.showError(
                            app,
                            ImageGenerationNotificationManager.singleNotificationId(),
                            "画像生成に失敗しました",
                            promptPreview
                        )
                    }
                }
                else -> {
                    _resultBitmap.value = bmp
                    val uri = MessageMediaStore.savePngBitmap(app, bmp, "imagegen_${System.currentTimeMillis()}")
                    lastSavedInternalUri = uri
                    ImageGenerationNotificationManager.showCompleted(
                        app,
                        ImageGenerationNotificationManager.singleNotificationId(),
                        "画像生成が完了しました",
                        promptPreview
                    )
                    if (uri != null && metadata != null) {
                        val prefs = app.getSharedPreferences("image_metadata", Context.MODE_PRIVATE)
                        prefs.edit().putString("metadata_$uri", buildMetadataJson(metadata)).apply()
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "[ImageGen] generate() job was cancelled")
            wasCancelled = true
            _snackbar.value = app.getString(com.nezumi_ai.R.string.image_gen_snackbar_cancelled)
            ImageGenerationNotificationManager.showError(
                app,
                ImageGenerationNotificationManager.singleNotificationId(),
                "画像生成を停止しました",
                "単体画像の生成をキャンセルしました"
            )
        } catch (e: java.net.SocketException) {
            Log.e(TAG, "[ImageGen] Socket closed during generation (likely due to cancellation)", e)
            wasCancelled = true
            _snackbar.value = app.getString(com.nezumi_ai.R.string.image_gen_snackbar_cancelled)
            ImageGenerationNotificationManager.showError(
                app,
                ImageGenerationNotificationManager.singleNotificationId(),
                "画像生成を停止しました",
                "単体画像の生成をキャンセルしました"
            )
        } catch (e: Exception) {
            Log.e(TAG, "ImageGen failed", e)
            showImageGenError(e.message ?: "画像生成に失敗しました")
            ImageGenerationNotificationManager.showError(
                app,
                ImageGenerationNotificationManager.singleNotificationId(),
                "画像生成に失敗しました",
                e.message ?: promptPreview
            )
        } finally {
            Log.i(TAG, "[ImageGen] finally block: cleaning up")
            isCancelling = false
            // Perf fix / クラッシュ対策:
            //   旧実装は、キャンセルされたときですら releaseSdKeepNone() を呼び SD プロセスを
            //   destroy し、直後に LLM をリロードしていた。この式だと cancel を押すタイミングで
            //   OpenCL context の破棄と LLM の mmap がぶつかりスパイクし、末尾ログ
            //   "Starting cancellation and resource cleanup..." の直後に OOM/SIGABRT で
            //   プロセスが落ちるケースがあった。
            //
            //   キャンセルされた場合は、バックエンドを生かしたままにして LLM のリロードも
            //   スキップする (ユーザーは膡しいターンで SD をやり直したいはず)。
            //   正常完了時のみ LLM をリロードし、SD は keep-warm。
            if (!wasCancelled) {
                runCatching { EngineManager.releaseSdKeepNone() }
                runCatching { EngineManager.markLlmActive() }
                runCatching { reloadChatModel(manager) }
            } else {
                Log.i(TAG, "[ImageGen] finally: cancelled path - keeping SD backend warm")
            }
            _loading.value = false
            _currentStep.value = 0
            _progressData.value = null
            releaseGenerationWakeLock()
            Log.i(TAG, "[ImageGen] finally block: cleanup completed")
        }
        }
    }

    private suspend fun reloadChatModel(manager: ModelManager) {
        val ui = normalizeModel(settingsRepository.getSelectedModel())
        val engineName = toEngineModelName(ui)
        val base = settingsRepository.getInferenceConfigForModel(ui, getApplication())
        val backend = settingsRepository.getBackendForModel(ui)
        val config = base.copy(backendType = backend).normalized()
        runCatching { manager.initializeModel(engineName, config) }
            .onFailure { Log.e(TAG, "reloadChatModel failed", it) }
    }

    private fun normalizeModel(model: String): String {
        val trimmed = model.trim()
        val lowered = trimmed.lowercase()
        val isLocalTaskPath =
            (lowered.endsWith(".task") || lowered.endsWith(".litertlm") || lowered.endsWith(".gguf")) &&
                File(trimmed).isAbsolute
        return when {
            trimmed.equals("Gemma4-4B", ignoreCase = true) -> "Gemma4-4B"
            trimmed.equals("Gemma4-2B", ignoreCase = true) -> "Gemma4-2B"
            trimmed.equals("E4B", ignoreCase = true) -> "E4B"
            trimmed.equals("E2B", ignoreCase = true) -> "E2B"
            isLocalTaskPath -> trimmed
            else -> "Gemma4-2B"
        }
    }

    private fun toEngineModelName(model: String): String {
        val normalized = normalizeModel(model)
        return when {
            normalized.equals("Gemma4-4B", ignoreCase = true) -> "gemma4-4b"
            normalized.equals("Gemma4-2B", ignoreCase = true) -> "gemma4-2b"
            normalized.equals("E4B", ignoreCase = true) -> "gemma-3n-4b"
            normalized.equals("E2B", ignoreCase = true) -> "gemma-3n-2b"
            (normalized.endsWith(".task") ||
                normalized.endsWith(".litertlm") ||
                normalized.endsWith(".gguf")) && File(normalized).isAbsolute -> normalized
            else -> "gemma4-2b"
        }
    }

    fun saveToGallery(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        val bmp = _resultBitmap.value ?: return@launch
        val name = "nezumi_sd_${System.currentTimeMillis()}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NezumiAI")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@launch
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val uri = MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    bmp,
                    name,
                    "nezumi-ai SD"
                )
                if (uri == null) {
                    _snackbar.value = "保存に失敗しました"
                    return@launch
                }
            }
            _snackbar.value = "ギャラリーに保存しました"
        } catch (e: Exception) {
            Log.e(TAG, "saveToGallery", e)
            _snackbar.value = e.message ?: "save failed"
        }
    }

    fun share(context: Context) {
        val bmp = _resultBitmap.value ?: return
        shareBitmap(context, bmp)
    }

    fun saveBitmapToGallery(context: Context, bmp: Bitmap) = viewModelScope.launch(Dispatchers.IO) {
        val name = "nezumi_sd_${System.currentTimeMillis()}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NezumiAI")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@launch
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val uri = MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    bmp,
                    name,
                    "nezumi-ai SD"
                )
                if (uri == null) {
                    _snackbar.value = "保存に失敗しました"
                    return@launch
                }
            }
            _snackbar.value = "ギャラリーに保存しました"
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToGallery", e)
            _snackbar.value = e.message ?: "save failed"
        }
    }

    fun shareBitmap(context: Context, bmp: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val uriStr = MessageMediaStore.savePngBitmap(context.applicationContext, bmp, "share_sd")
                ?: return@launch
            val uri = Uri.parse(uriStr)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(share, "共有"))
            }
        }
    }

    // ============ 一括生成キュー機能 ============

    /**
     * 生成キューを作成（1～10枚まで）
     */
    fun createGenerationQueue(count: Int, seed: Long = -1L): Boolean {
        val validCount = count.coerceIn(1, 10)
        val basePrompt = _prompt.value.trim()
        val baseNegPrompt = _negativePrompt.value
        val baseSeed = if (seed >= 0) seed else _seed.value
        val baseScheduler = _scheduler.value.id

        if (basePrompt.isEmpty()) {
            _snackbar.value = "プロンプトを入力してください"
            return false
        }

        val queueItems = mutableListOf<GenerationQueueItem>()
        for (idx in 1..validCount) {
            val itemSeed = if (baseSeed >= 0) baseSeed + (idx - 1) else -1L

            queueItems.add(
                GenerationQueueItem(
                    count = idx,
                    prompt = basePrompt,
                    negativePrompt = baseNegPrompt,
                    steps = _steps.value,
                    cfg = _cfg.value,
                    seed = itemSeed,
                    scheduler = baseScheduler
                )
            )
        }

        _generationQueue.value = GenerationQueue(items = queueItems, currentIndex = 0, isRunning = false)
        _snackbar.value = "$validCount 個の画像を生成キューに追加しました"
        Log.d(TAG, "[Queue] Created queue with $validCount items")
        return true
    }

    /**
     * キューの実行を開始
     */
    fun startQueueGeneration() {
        val queue = _generationQueue.value
        if (queue.items.isEmpty()) {
            _snackbar.value = "キューが空です"
            return
        }

        if (_isQueueRunning.value) {
            _snackbar.value = "キュー実行中です"
            return
        }

        queueRunJob?.cancel()
        acquireGenerationWakeLock()
        _loading.value = true
        _resultBitmap.value = null
        _currentStep.value = 0
        _safetyVerdict.value = null

        _queueResultBitmaps.value = emptyList()
        queueRunJob = viewModelScope.launch(Dispatchers.IO) {
            _isQueueRunning.value = true
            val app = getApplication<Application>()
            if (com.nezumi_ai.BuildConfig.SAFETY_IMAGE_GUARD_ENABLED &&
                !ensureSafetyModelReady(app)) {
                _isQueueRunning.value = false
                _loading.value = false
                _snackbar.value = "セーフティモデルのダウンロードに失敗しました"
                ImageGenerationNotificationManager.showError(
                    app,
                    ImageGenerationNotificationManager.queueNotificationId(),
                    "画像生成キューに失敗しました",
                    "セーフティモデルの準備に失敗しました"
                )
                releaseGenerationWakeLock()
                return@launch
            }

            val totalItems = queue.items.size
            val queuePromptPreview = notificationPromptPreview(queue.items.firstOrNull()?.prompt.orEmpty())
            _queueProgress.value = Pair(0, totalItems)
            ImageGenerationNotificationManager.showQueueProgress(
                app,
                itemIndex = 1,
                totalItems = totalItems,
                step = 0,
                totalSteps = queue.items.firstOrNull()?.steps ?: 1,
                promptPreview = queuePromptPreview
            )
            
            var completedCount = 0
            var failedCount = 0
            var currentQueue = queue.copy()

            for (idx in currentQueue.items.indices) {
                if (!isActive) {
                    Log.i(TAG, "[Queue] Cancelled")
                    _snackbar.value = "キュー生成がキャンセルされました"
                    break
                }

                val item = currentQueue.items[idx]
                Log.d(TAG, "[Queue] Processing ${idx + 1}/$totalItems")

                currentQueue = currentQueue.copy(
                    currentIndex = idx,
                    items = currentQueue.items.mapIndexed { itemIndex, queueItem ->
                        if (itemIndex == idx) queueItem.copy(status = GenerationQueueItem.GenerationStatus.RUNNING)
                        else queueItem
                    }
                )
                _generationQueue.value = currentQueue
                _resultBitmap.value = null

                val bmp = executeQueueItem(item, idx + 1, totalItems)
                
                // セーフティ違反をチェック
                if (_safetyVerdict.value == SafetyResult.Verdict.BLOCK) {
                    Log.w(TAG, "[Queue] Safety violation detected at item ${idx + 1}")
                    _snackbar.value = "不適切なコンテンツが検出されたため、キューを中止しました"
                    ImageGenerationNotificationManager.showError(
                        app,
                        ImageGenerationNotificationManager.queueNotificationId(),
                        "画像生成キューを停止しました",
                        "セーフティガードにより ${idx + 1} 枚目で中止しました"
                    )
                    _generationQueue.value = currentQueue.copy(
                        items = currentQueue.items.mapIndexed { itemIndex, queueItem ->
                            if (itemIndex > idx) queueItem.copy(status = GenerationQueueItem.GenerationStatus.CANCELLED)
                            else queueItem
                        }
                    )
                    break
                }
                
                if (bmp != null) {
                    completedCount++
                    _queueResultBitmaps.value = _queueResultBitmaps.value + bmp
                    currentQueue = currentQueue.copy(
                        items = currentQueue.items.mapIndexed { itemIndex, queueItem ->
                            if (itemIndex == idx) queueItem.copy(status = GenerationQueueItem.GenerationStatus.COMPLETED)
                            else queueItem
                        }
                    )
                    _generationQueue.value = currentQueue
                    Log.d(TAG, "[Queue] Item ${idx + 1} completed")
                } else {
                    failedCount++
                    currentQueue = currentQueue.copy(
                        items = currentQueue.items.mapIndexed { itemIndex, queueItem ->
                            if (itemIndex == idx) queueItem.copy(status = GenerationQueueItem.GenerationStatus.FAILED)
                            else queueItem
                        }
                    )
                    _generationQueue.value = currentQueue
                    Log.w(TAG, "[Queue] Item ${idx + 1} failed")
                }

                _queueProgress.value = Pair(completedCount + failedCount, totalItems)

                if (idx < totalItems - 1) {
                    delay(500)
                }
            }

            // Perf fix: キューが完全に終了したところで初めて SD バックエンドをリリースし、
            //   チャット側の LLM を復帰させる。キュー途中で SD をリリースしていた旧実装では
            //   毎アイテムで acquireLocalDream() がフルロードしていたことになるが、現在は
            //   同じ backend/model であれば EngineManager のキャッシュを使い回すので不要。
            runCatching { EngineManager.releaseSdKeepNone() }
            runCatching { EngineManager.markLlmActive() }
            if (_safetyVerdict.value != SafetyResult.Verdict.BLOCK) {
                ImageGenerationNotificationManager.showCompleted(
                    app,
                    ImageGenerationNotificationManager.queueNotificationId(),
                    "画像生成キューが完了しました",
                    "$completedCount / $totalItems 枚を保存しました"
                )
            }
            _isQueueRunning.value = false
            _queueProgress.value = null
            _loading.value = false
            _currentStep.value = 0
            releaseGenerationWakeLock()
        }
    }

    /**
     * キューのアイテム1つを実行
     */
    private suspend fun executeQueueItem(item: GenerationQueueItem, itemIndex: Int, totalItems: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val path = _modelPath.value.trim()

            if (path.isEmpty() || !File(path).isDirectory) {
                Log.e(TAG, "[QueueItem] Invalid model path")
                showImageGenError(app.getString(com.nezumi_ai.R.string.image_gen_err_model_missing))
                return@withContext null
            }

            if (!ensureSafetyModelReady(app)) {
                Log.e(TAG, "[QueueItem] Safety model not ready")
                showImageGenError("セーフティモデルの準備に失敗しました")
                return@withContext null
            }

            // プロンプトの前段フィルタリング
            if (com.nezumi_ai.BuildConfig.SAFETY_PROMPT_FILTER_ENABLED &&
                PromptFilter.check(item.prompt) == PromptFilter.Result.BLOCK) {
                Log.w(TAG, "[QueueItem] Prompt blocked by PromptFilter")
                _safetyVerdict.value = SafetyResult.Verdict.BLOCK
                showImageGenError("プロンプトにポリシー違反のキーワードが含まれています")
                return@withContext null
            }

            // Perf fix: キュー内は 1 枚ごとに LLM を unload/reload する必要はない。
            //   初回 acquireLocalDream() が EngineManager の mutex 内で SD に切り替える際、
            //   LLM 側はすでにリリースされる。キューの 2 枚目以降は同じ backend/model であれば
            //   サーバーを使い回し、余分なロードを避ける。
            val queueBackend = _selectedBackend.value
            val ld = EngineManager.acquireLocalDream(app, path, queueBackend)
            ld.clearLastSafetyVerdict()  // 前回の verdict をクリア

            val width = _sizePx.value
            val height = _sizePx.value

            val result = ld.generateImageWithMetadata(
                prompt = item.prompt,
                negativePrompt = item.negativePrompt,
                width = width,
                height = height,
                steps = item.steps,
                cfg = item.cfg,
                seed = item.seed,
                scheduler = SdScheduler.fromId(item.scheduler),
                onProgress = { step, totalSteps, _ ->
                    val clamped = step.coerceAtMost(totalSteps)
                    if (_currentStep.value != clamped) {
                        _currentStep.value = clamped
                    }
                    val prev = _progressData.value
                    if (prev == null || prev.step != step || prev.totalSteps != totalSteps) {
                        _progressData.value = ProgressData(step, totalSteps, 0f)
                    }
                    ImageGenerationNotificationManager.showQueueProgress(
                        app,
                        itemIndex = itemIndex,
                        totalItems = totalItems,
                        step = clamped,
                        totalSteps = totalSteps,
                        promptPreview = notificationPromptPreview(item.prompt)
                    )
                }
            )

            if (result != null) {
                val (bmp, metadata) = result
                if (bmp != null) {
                    _resultBitmap.value = bmp
                    if (metadata != null) {
                        saveImageWithMetadata(app, bmp, metadata)
                    }
                    return@withContext bmp
                } else {
                    // セーフティ違反（後段ガード）
                    Log.w(TAG, "[QueueItem] Image blocked by safety guard")
                    _safetyVerdict.value = SafetyResult.Verdict.BLOCK
                    showImageGenError("不適切なコンテンツが検出されたため表示を制限しました")
                    return@withContext null
                }
            } else {
                // result == null のケース：セーフティ違反か生成失敗
                val lastVerdict = ld.getLastSafetyVerdict()
                if (lastVerdict == SafetyResult.Verdict.BLOCK) {
                    Log.w(TAG, "[QueueItem] Image BLOCK by safety guard (verdict=${lastVerdict})")
                    _safetyVerdict.value = SafetyResult.Verdict.BLOCK
                    showImageGenError("不適切なコンテンツが検出されたため表示を制限しました")
                } else {
                    Log.w(TAG, "[QueueItem] Generation failed or safety check unavailable")
                    showImageGenError("画像生成に失敗しました")
                }
                return@withContext null
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "[QueueItem] Job was cancelled")
            throw e
        } catch (e: java.net.SocketException) {
            Log.e(TAG, "[QueueItem] Socket closed during generation (likely due to cancellation)", e)
            showImageGenError(e.message ?: "画像生成中に接続が切断されました")
            null
        } catch (e: Exception) {
            Log.e(TAG, "[QueueItem] Error", e)
            showImageGenError(e.message ?: "画像生成に失敗しました")
            null
        }
    }

    /**
     * メタデータ付きで画像を保存
     */
    private suspend fun saveImageWithMetadata(
        context: Context,
        bitmap: Bitmap,
        metadata: ImageGenerationMetadata
    ): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val filename = "sd_${metadata.timestamp}_${metadata.seed}.png"
            val uri = MessageMediaStore.savePngBitmap(context, bitmap, filename)
            
            // メタデータをSharedPreferencesに保存（画像ファイルのメタデータとして）
            // NOTE: 本来ならExifに埋め込むべきだが、ここではURIベースの管理を行う
            val prefs = context.getSharedPreferences("image_metadata", Context.MODE_PRIVATE)
            prefs.edit().putString(
                "metadata_$uri",
                buildMetadataJson(metadata)
            ).apply()
            
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image with metadata", e)
            null
        }
    }

    /**
     * メタデータをJSON文字列に変換
     */
    private fun buildMetadataJson(metadata: ImageGenerationMetadata): String {
        return org.json.JSONObject().apply {
            put("modelPath", metadata.modelPath)
            put("modelName", metadata.modelName)
            put("prompt", metadata.prompt)
            put("negativePrompt", metadata.negativePrompt)
            put("steps", metadata.steps)
            put("cfg", metadata.cfg)
            put("seed", metadata.seed)
            put("scheduler", metadata.scheduler)
            put("width", metadata.width)
            put("height", metadata.height)
            put("backend", metadata.backend)
            put("timestamp", metadata.timestamp)
            put("generationTimeMs", metadata.generationTimeMs)
        }.toString()
    }

    /**
     * キューをクリア
     */
    fun clearQueue() {
        queueRunJob?.cancel()
        _generationQueue.value = GenerationQueue()
        _queueProgress.value = null
        _isQueueRunning.value = false
        ImageGenerationNotificationManager.cancelQueue(getApplication())
        releaseGenerationWakeLock()
        _snackbar.value = "キューをクリアしました"
    }

    /**
     * キュー実行をキャンセル
     */
    fun cancelQueueExecution() {
        queueRunJob?.cancel()
        _isQueueRunning.value = false
        ImageGenerationNotificationManager.showError(
            getApplication(),
            ImageGenerationNotificationManager.queueNotificationId(),
            "画像生成キューを停止しました",
            "キュー実行をキャンセルしました"
        )
        releaseGenerationWakeLock()
        _snackbar.value = "キュー実行をキャンセルしました"
    }

    // ============ メタデータ永続化 ============

    /**
     * 画像のメタデータを取得
     */
    fun getImageMetadata(context: Context, uri: String?): ImageGenerationMetadata? {
        if (uri == null) return null
        val prefs = context.getSharedPreferences("image_metadata", Context.MODE_PRIVATE)
        val json = prefs.getString("metadata_$uri", null) ?: return null
        return parseMetadataJson(json)
    }

    /**
     * 全ての保存されたメタデータを取得
     */
    fun getAllImageMetadata(context: Context): List<Pair<String, ImageGenerationMetadata>> {
        val prefs = context.getSharedPreferences("image_metadata", Context.MODE_PRIVATE)
        return prefs.all
            .filter { it.key.startsWith("metadata_") }
            .mapNotNull { (key, value) ->
                val uri = key.removePrefix("metadata_")
                val metadata = parseMetadataJson(value as? String ?: return@mapNotNull null)
                if (metadata != null) Pair(uri, metadata) else null
            }
            .sortedByDescending { it.second.timestamp }
    }

    /**
     * メタデータをJSON文字列からパース
     */
    private fun parseMetadataJson(json: String): ImageGenerationMetadata? {
        return try {
            val obj = org.json.JSONObject(json)
            ImageGenerationMetadata(
                modelPath = obj.getString("modelPath"),
                modelName = obj.getString("modelName"),
                prompt = obj.getString("prompt"),
                negativePrompt = obj.getString("negativePrompt"),
                steps = obj.getInt("steps"),
                cfg = obj.getDouble("cfg").toFloat(),
                seed = obj.getLong("seed"),
                scheduler = obj.optString("scheduler", SdScheduler.DEFAULT.id),
                width = obj.getInt("width"),
                height = obj.getInt("height"),
                backend = obj.getString("backend"),
                timestamp = obj.getLong("timestamp"),
                generationTimeMs = obj.getLong("generationTimeMs")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse metadata JSON", e)
            null
        }
    }

    /**
     * キュー項目から生成条件を取得（再生成用）
     */
    fun getQueueItemAsNewPrompt(item: GenerationQueueItem) {
        _prompt.value = item.prompt
        _negativePrompt.value = item.negativePrompt
        _steps.value = item.steps
        _cfg.value = item.cfg
        _seed.value = item.seed
        _scheduler.value = SdScheduler.fromId(item.scheduler)
    }

    // ==========================================
}
