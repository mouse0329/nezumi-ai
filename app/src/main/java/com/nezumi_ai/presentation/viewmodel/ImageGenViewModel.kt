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
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.inference.EngineManager
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.sd.ProgressData
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.utils.PreferencesHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        loadAvailableModels()
    }

    private val _backendInfo = MutableStateFlow("")
    val backendInfo: StateFlow<String> = _backendInfo.asStateFlow()

    private fun loadAvailableModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val models = mutableListOf<String>()
            Log.d(TAG, "[loadAvailableModels] Starting model search")
            // ダウンロード済み画像生成モデルディレクトリ
            val sdModelsDir = File(getApplication<Application>().filesDir, "sd_models")
            Log.d(TAG, "[loadAvailableModels] Checking sd_models: ${sdModelsDir.absolutePath}, exists=${sdModelsDir.exists()}")
            sdModelsDir.listFiles()?.forEach { file ->
                Log.d(TAG, "[loadAvailableModels] Checking file: ${file.name}, isDir=${file.isDirectory}")
                if (isProbableSdModelDir(file)) {
                    Log.d(TAG, "[loadAvailableModels] ✓ Added: ${file.absolutePath}")
                    models.add(file.absolutePath)
                } else {
                    Log.d(TAG, "[loadAvailableModels] ✗ Rejected: ${file.absolutePath}")
                }
            }
            // アプリ専用ディレクトリ
            val appDir = getApplication<Application>().getExternalFilesDir(null)
            Log.d(TAG, "[loadAvailableModels] Checking appDir: ${appDir?.absolutePath}")
            appDir?.listFiles()?.forEach { file ->
                if (isProbableSdModelDir(file)) {
                    Log.d(TAG, "[loadAvailableModels] ✓ Added from appDir: ${file.absolutePath}")
                    models.add(file.absolutePath)
                }
            }
            // インポート済みモデルディレクトリ
            val importedDir = File(getApplication<Application>().filesDir, "models/imported")
            Log.d(TAG, "[loadAvailableModels] Checking importedDir: ${importedDir.absolutePath}")
            importedDir.listFiles()?.forEach { file ->
                if (isProbableSdModelDir(file)) {
                    Log.d(TAG, "[loadAvailableModels] ✓ Added from importedDir: ${file.absolutePath}")
                    models.add(file.absolutePath)
                }
            }
            Log.d(TAG, "[loadAvailableModels] Total models found: ${models.size}")
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
        if (!file.isDirectory) {
            Log.d(TAG, "[isProbableSdModelDir] ${file.name}: not a directory")
            return false
        }
        
        Log.d(TAG, "[isProbableSdModelDir] Checking ${file.absolutePath}")
        val files = file.listFiles()
        Log.d(TAG, "[isProbableSdModelDir] Files in ${file.name}: ${files?.map { it.name }?.joinToString(", ")}")
        
        // ネスト構造チェック: 1つのサブディレクトリのみの場合、そちらを使う
        if (files != null && files.size == 1 && files[0].isDirectory) {
            Log.d(TAG, "[isProbableSdModelDir] Detected nested structure, checking ${files[0].absolutePath}")
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
        Log.d(TAG, "[isProbableSdModelDir] ${file.name}: hasMnn=$hasMnnFiles, hasQnn=$hasQnnFiles, result=$result")
        return result
    }
    
    private fun detectModelFormat(path: String): String {
        val dir = File(path)
        if (!dir.isDirectory) return "Unknown"
        
        val hasMnn = File(dir, "unet.mnn").exists()
        val hasQnn = File(dir, "unet.bin").exists()
        
        return when {
            hasQnn -> "QNN (NPU)"
            hasMnn -> "MNN (CPU/OpenCL)"
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

    private val _resultBitmap = MutableStateFlow<Bitmap?>(null)
    val resultBitmap: StateFlow<Bitmap?> = _resultBitmap.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _progressData = MutableStateFlow<ProgressData?>(null)
    val progressData: StateFlow<ProgressData?> = _progressData.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    private var lastSavedInternalUri: String? = null
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
        _sizePx.value = listOf(256, 512, 768).minByOrNull { kotlin.math.abs(it - s) } ?: 512
    }

    private var isCancelling = false

    fun clearSnackbar() {
        _snackbar.value = null
    }

    fun cancel() {
        if (isCancelling) {
            Log.w(TAG, "[ImageGen] cancel() already in progress, ignoring")
            return
        }
        isCancelling = true
        Log.i(TAG, "[ImageGen] cancel() called")
        
        generateJob?.cancel()
        Log.i(TAG, "[ImageGen] generateJob cancelled")
        
        _currentStep.value = 0
        _progressData.value = null
        Log.i(TAG, "[ImageGen] cancel() completed")
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
        _loading.value = true
        _resultBitmap.value = null
        _currentStep.value = 0
        isCancelling = false
        val manager = ModelManager.getInstance(app)
        val threads = settingsRepository.getLlamaCppThreads().coerceAtLeast(1)
        val sz = _sizePx.value
        val totalSteps = _steps.value
        var wasCancelled = false
        try {
            runCatching { manager.unloadModel() }
            Log.i(TAG, "[ImageGen] generate() starting, acquiring LocalDream engine")
            
            val ld = EngineManager.acquireLocalDream(app, path, "auto")
            
            _currentStep.value = 0
            _progressData.value = ProgressData(0, totalSteps, 0.0f)
            
            val bmp = ld.generateImage(
                prompt = pr,
                negativePrompt = _negativePrompt.value,
                width = sz,
                height = sz,
                steps = totalSteps,
                cfg = _cfg.value,
                seed = -1L,
                onProgress = { step, steps, time ->
                    _progressData.value = ProgressData(step, steps, time)
                    _currentStep.value = step.coerceAtMost(totalSteps)
                }
            )
            
            _currentStep.value = totalSteps
            _progressData.value = ProgressData(totalSteps, totalSteps, _progressData.value?.time ?: 0.0f)
            
            if (bmp == null) {
                wasCancelled = true
                _snackbar.value = app.getString(com.nezumi_ai.R.string.image_gen_snackbar_cancelled)
            } else {
                _resultBitmap.value = bmp
                lastSavedInternalUri = MessageMediaStore.savePngBitmap(app, bmp, "imagegen_${System.currentTimeMillis()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ImageGen failed", e)
            _snackbar.value = e.message ?: "error"
        } finally {
            Log.i(TAG, "[ImageGen] finally block: cleaning up")
            isCancelling = false
            runCatching { EngineManager.releaseSdKeepNone() }
            runCatching { EngineManager.markLlmActive() }
            if (!wasCancelled) {
                runCatching { reloadChatModel(manager) }
            }
            _loading.value = false
            _currentStep.value = 0
            _progressData.value = null
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
}
