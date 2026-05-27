package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.benchmark.BenchmarkPrompt
import com.nezumi_ai.data.benchmark.BenchmarkResult
import com.nezumi_ai.data.benchmark.BenchmarkRunner
import com.nezumi_ai.data.benchmark.BenchmarkSummary
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class BenchmarkViewModel(
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "BenchmarkViewModel"
        private const val REPEAT_COUNT = 3
    }

    data class ModelOption(
        val model: String,
        val engineModelName: String,
        val label: String
    )

    // --- UI State ---

    sealed class State {
        object Idle : State()
        data class Running(val message: String, val progress: Int, val total: Int) : State()
        data class Done(val summary: BenchmarkSummary) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _results = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val results: StateFlow<List<BenchmarkResult>> = _results.asStateFlow()

    private val _modelOptions = MutableStateFlow<List<ModelOption>>(emptyList())
    val modelOptions: StateFlow<List<ModelOption>> = _modelOptions.asStateFlow()

    private var benchmarkJob: Job? = null
    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepository.fromDatabase(NezumiAiDatabase.getInstance(appContext))
    }

    // --- 設定 ---

    /** ベンチマーク対象モデル */
    var selectedModel: String = "Gemma4-2B"

    /** 実行するプロンプトの選択（デフォルトは短文のみ） */
    var selectedPrompts: List<BenchmarkPrompt> = listOf(BenchmarkPrompt.SHORT)

    /** 繰り返し回数（1〜5） */
    var repeatCount: Int = REPEAT_COUNT

    // --- Actions ---

    fun refreshModelOptions() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                settingsRepository.initializeSettingsIfNeeded(appContext)
                selectedModel = settingsRepository.getSelectedModel()

                val options = buildList {
                    addAvailableBuiltin("Gemma4-2B", "gemma4-2b", "Gemma 4 2B")
                    addAvailableBuiltin("Gemma4-4B", "gemma4-4b", "Gemma 4 4B")
                    addAvailableBuiltin("E2B", "gemma-3n-2b", "Gemma 3n E2B")
                    addAvailableBuiltin("E4B", "gemma-3n-4b", "Gemma 3n E4B")

                    ModelFileManager.listImportedTaskModels(appContext).forEach { imported ->
                        val engine = imported.path
                        val kind = when {
                            engine.endsWith(".gguf", ignoreCase = true) -> "GGUF"
                            else -> "LiteRT"
                        }
                        add(ModelOption(imported.path, engine, "${imported.shortDisplayName} ($kind)"))
                    }
                }.distinctBy { it.model }

                _modelOptions.value = options
                if (options.none { it.model == selectedModel }) {
                    selectedModel = options.firstOrNull()?.model ?: selectedModel
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to refresh model options", e)
                _modelOptions.value = emptyList()
            }
        }
    }

    fun startBenchmark() {
        if (benchmarkJob?.isActive == true) return

        benchmarkJob = viewModelScope.launch(Dispatchers.IO) {
            _results.value = emptyList()
            _state.value = State.Running("モデルを確認中...", 0, 1)

            val modelManager = try {
                ModelManager.getInstance(appContext)
            } catch (e: Exception) {
                _state.value = State.Error("ModelManager の初期化に失敗: ${e.message}")
                return@launch
            }

            val modelName = toEngineModelName(selectedModel)
            if (!ModelFileManager.isModelAvailable(appContext, modelName)) {
                _state.value = State.Error("選択モデルが利用できません。設定画面でダウンロードまたは追加してください。")
                return@launch
            }

            // エンジン名を推定（GGUFはファイルパスが絶対パスかつ.gguf拡張子）
            val engineName = if (modelName.trim().lowercase().endsWith(".gguf") &&
                java.io.File(modelName.trim()).isAbsolute) "GGUF" else "LiteRT"

            val runner = BenchmarkRunner(appContext, modelManager)
            val config = settingsRepository.getInferenceConfigForModel(selectedModel, appContext).copy(
                enableThinking = false,
                maxTokens = 512
            ).normalized()

            _state.value = State.Running("モデルをロード中...", 0, 1)
            val loadResult = modelManager.initializeModel(modelName, config)
            if (loadResult.isFailure) {
                val message = loadResult.exceptionOrNull()?.message ?: "不明なエラー"
                _state.value = State.Error("モデルのロードに失敗しました: $message")
                return@launch
            }

            val allResults = mutableListOf<BenchmarkResult>()
            val totalRuns = selectedPrompts.size * repeatCount
            var completedRuns = 0

            // ウォームアップ
            _state.value = State.Running("ウォームアップ中...", 0, totalRuns)
            runner.warmup(config)

            // 本計測
            for (prompt in selectedPrompts) {
                for (i in 0 until repeatCount) {
                    if (!isActive()) break
                    val runLabel = "${prompt.label} ${i + 1}/$repeatCount"
                    _state.value = State.Running("計測中: $runLabel", completedRuns, totalRuns)
                    Log.d(TAG, "Running benchmark: $runLabel")

                    val result = runner.runOnce(
                        prompt = prompt,
                        config = config,
                        engineName = engineName,
                        runIndex = i
                    )
                    allResults.add(result)
                    _results.value = allResults.toList()
                    completedRuns++
                }
            }

            val summary = BenchmarkSummary(
                results = allResults,
                engineName = engineName,
                modelName = modelName
            )
            _state.value = State.Done(summary)
        }

        benchmarkJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                _state.value = State.Idle
            }
        }
    }

    fun cancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _state.value = State.Idle
    }

    private fun isActive(): Boolean = benchmarkJob?.isActive == true

    override fun onCleared() {
        super.onCleared()
        benchmarkJob?.cancel()
    }

    private fun MutableList<ModelOption>.addAvailableBuiltin(model: String, engineModelName: String, label: String) {
        if (ModelFileManager.isModelAvailable(appContext, engineModelName)) {
            add(ModelOption(model, engineModelName, label))
        }
    }

    private fun normalizeModel(model: String): String {
        val trimmed = model.trim()
        val lowered = trimmed.lowercase()
        val isLocalModelPath =
            (lowered.endsWith(".task") || lowered.endsWith(".litertlm") || lowered.endsWith(".gguf")) &&
                File(trimmed).isAbsolute

        return when {
            trimmed.equals("Gemma4-4B", ignoreCase = true) -> "Gemma4-4B"
            trimmed.equals("Gemma4-2B", ignoreCase = true) -> "Gemma4-2B"
            trimmed.equals("Gemma3n-4B", ignoreCase = true) -> "E4B"
            trimmed.equals("Gemma3n-2B", ignoreCase = true) -> "E2B"
            trimmed.equals("E4B", ignoreCase = true) -> "E4B"
            trimmed.equals("E2B", ignoreCase = true) -> "E2B"
            isLocalModelPath -> trimmed
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
            (normalized.endsWith(".task", ignoreCase = true) ||
                normalized.endsWith(".litertlm", ignoreCase = true) ||
                normalized.endsWith(".gguf", ignoreCase = true)) && File(normalized).isAbsolute -> normalized
            else -> "gemma4-2b"
        }
    }
}

class BenchmarkViewModelFactory(
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BenchmarkViewModel(appContext) as T
    }
}
