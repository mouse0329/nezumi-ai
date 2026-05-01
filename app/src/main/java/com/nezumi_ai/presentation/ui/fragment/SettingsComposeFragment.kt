package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.presentation.ui.screen.ThemeModeCard
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsComposeFragment : Fragment() {
    private lateinit var settingsRepository: SettingsRepository

    private var contextWindowInput by mutableStateOf("4096")
    private var temperatureInput by mutableStateOf("0.7")
    private var topkInput by mutableStateOf("40")
    private var maxTokensInput by mutableStateOf("1024")
    private var contextCompressionEnabled by mutableStateOf(false)
    private var contextCompressionThresholdPercent by mutableStateOf(70)
    private var userNameInput by mutableStateOf("")
    private var systemPromptInput by mutableStateOf("")
    private var backendType by mutableStateOf("CPU")
    private var gemmaThinkingEnabled by mutableStateOf(false)
    private var themeMode by mutableStateOf(PreferencesHelper.THEME_SYSTEM)
    private var errorDialogMessage by mutableStateOf<String?>(null)
    private var llamaCppThreads by mutableStateOf(InferenceConfig.getDefaultThreadCount())
    private var maxThreads by mutableStateOf(InferenceConfig.MAX_THREADS)
    private var llamaCppGpuLayers by mutableStateOf(0)
    private var llamaCppBatchSize by mutableStateOf(512)
    private var llamaCppNKeep by mutableStateOf(0)
    private var llamaCppRopeFreqBase by mutableStateOf(0.0f)
    private var llamaCppRopeFreqScale by mutableStateOf(1.0f)
    private var chatHistoryLimit by mutableStateOf(30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository(db.settingsDao(), db.chatSessionDao())
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                SettingsScreen()
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadInferenceSettings()
    }

    override fun onResume() {
        super.onResume()
        loadInferenceSettings()
    }

    @Composable
    private fun SettingsScreen() {
        errorDialogMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { errorDialogMessage = null },
                title = { Text("設定エラー") },
                text = { Text(message) },
                confirmButton = {
                    Button(onClick = { errorDialogMessage = null }) {
                        Text("OK")
                    }
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onBackButtonPressed() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = stringResource(id = R.string.back),
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                    Text(
                        text = "設定",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorResource(id = R.color.text_primary),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = colorResource(id = R.color.primary_light)
                    )
                ) {
                    ThemeModeCard(
                        currentMode = themeMode,
                        onModeSelected = {
                            if (it == themeMode) return@ThemeModeCard
                            themeMode = it
                            PreferencesHelper.setThemeMode(requireContext(), it)
                            PreferencesHelper.applyThemeMode(requireContext())
                        }
                    )
                }
            }
            item { BackendCard() }
            item { InferenceParamsCard() }
            item { PersonalizationCard() }
            item { ChatHistoryCard() }
            item { LlamaCppCard() }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        PreferencesHelper.resetInitialSetupCompleted(requireContext())
                        findNavController().navigate(R.id.setupWizardFragment)
                    }) {
                        Text(text = "セットアップを開く")
                    }
                    TextButton(onClick = { findNavController().navigate(R.id.action_settingsFragment_to_licenseFragment) }) {
                        Text(text = stringResource(id = R.string.open_license_page))
                    }
                }
            }
        }
    }

    @Composable
    private fun BackendCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "バックエンド", fontWeight = FontWeight.Bold)
                Text(
                    text = "現在のバックエンド: $backendType",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = backendType == "CPU",
                        onClick = { backendType = "CPU" },
                        label = { Text("CPU") }
                    )
                    FilterChip(
                        selected = backendType == "GPU",
                        onClick = { backendType = "GPU" },
                        label = { Text("GPU") }
                    )
                    FilterChip(
                        selected = backendType == "NPU",
                        onClick = { backendType = "NPU" },
                        label = { Text("NPU") }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Gemma 4 シンキング有効化",
                        color = colorResource(id = R.color.text_primary)
                    )
                    Switch(
                        checked = gemmaThinkingEnabled,
                        onCheckedChange = { gemmaThinkingEnabled = it }
                    )
                }
            }
        }
    }

    @Composable
    private fun InferenceParamsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "推論パラメータ", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = contextWindowInput,
                    onValueChange = { contextWindowInput = it },
                    label = { Text(stringResource(id = R.string.context_window_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = temperatureInput,
                    onValueChange = { temperatureInput = it },
                    label = { Text(stringResource(id = R.string.temperature_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = topkInput,
                    onValueChange = { topkInput = it },
                    label = { Text(stringResource(id = R.string.topk_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = maxTokensInput,
                    onValueChange = { maxTokensInput = it },
                    label = { Text(stringResource(id = R.string.max_tokens_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(id = R.string.context_compression_label),
                        color = colorResource(id = R.color.text_primary)
                    )
                    Switch(
                        checked = contextCompressionEnabled,
                        onCheckedChange = { contextCompressionEnabled = it }
                    )
                }
                if (contextCompressionEnabled) {
                    Text(
                        text = stringResource(
                            id = R.string.context_compression_threshold_format,
                            contextCompressionThresholdPercent
                        ),
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = contextCompressionThresholdPercent.toFloat(),
                        onValueChange = { value ->
                            contextCompressionThresholdPercent = value.roundToInt()
                                .coerceIn(
                                    InferenceConfig.MIN_COMPRESSION_THRESHOLD,
                                    InferenceConfig.MAX_COMPRESSION_THRESHOLD
                                )
                        },
                        valueRange = InferenceConfig.MIN_COMPRESSION_THRESHOLD.toFloat()..
                            InferenceConfig.MAX_COMPRESSION_THRESHOLD.toFloat(),
                        steps = InferenceConfig.MAX_COMPRESSION_THRESHOLD -
                            InferenceConfig.MIN_COMPRESSION_THRESHOLD - 1
                    )
                }
            }
        }
    }

    @Composable
    private fun PersonalizationCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "個人化設定", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = userNameInput,
                    onValueChange = { userNameInput = it },
                    label = { Text(stringResource(id = R.string.user_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPromptInput,
                    onValueChange = { systemPromptInput = it },
                    label = { Text(stringResource(id = R.string.system_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        }
    }

    @Composable
    private fun ChatHistoryCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "チャット履歴設定", fontWeight = FontWeight.Bold)
                
                Text(
                    text = "履歴の保存件数",
                    color = colorResource(id = R.color.text_primary)
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = chatHistoryLimit == 10,
                        onClick = { chatHistoryLimit = 10 },
                        label = { Text("10件") }
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 30,
                        onClick = { chatHistoryLimit = 30 },
                        label = { Text("30件") }
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 50,
                        onClick = { chatHistoryLimit = 50 },
                        label = { Text("50件") }
                    )
                    FilterChip(
                        selected = chatHistoryLimit == -1,
                        onClick = { chatHistoryLimit = -1 },
                        label = { Text("無制限") }
                    )
                }
                
                Text(
                    text = "古いものから自動的に削除されます",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun LlamaCppCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "llama.cpp 設定", fontWeight = FontWeight.Bold)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "CPU スレッド数: $llamaCppThreads",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Slider(
                        value = llamaCppThreads.toFloat(),
                        onValueChange = { llamaCppThreads = it.roundToInt() },
                        valueRange = 1f..maxThreads.toFloat(),
                        steps = maxOf(0, maxThreads - 2),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "GPU レイヤー数: $llamaCppGpuLayers",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Slider(
                        value = llamaCppGpuLayers.toFloat(),
                        onValueChange = { llamaCppGpuLayers = it.roundToInt() },
                        valueRange = 0f..100f,
                        steps = 99,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "0 = GPU オフロード無効",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "バッチサイズ: $llamaCppBatchSize",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Slider(
                        value = llamaCppBatchSize.toFloat(),
                        onValueChange = { llamaCppBatchSize = it.roundToInt().coerceIn(32, 2048) },
                        valueRange = 32f..2048f,
                        steps = 2016/32 - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "32〜2048。大きいほど高速だが メモリ使用量増加",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "保護トークン数（n_keep）: $llamaCppNKeep",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Slider(
                        value = llamaCppNKeep.toFloat(),
                        onValueChange = { llamaCppNKeep = it.roundToInt() },
                        valueRange = 0f..10000f,
                        steps = 199,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "0 = 無効、システムプロンプト保護",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "RoPE周波数基数: ${"%.1f".format(llamaCppRopeFreqBase)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Slider(
                        value = llamaCppRopeFreqBase,
                        onValueChange = { llamaCppRopeFreqBase = it },
                        valueRange = 0.0f..1000000.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "0 = 自動設定（推奨）",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "RoPE周波数スケール: ${"%.2f".format(llamaCppRopeFreqScale)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_primary)
                    )
                    Slider(
                        value = llamaCppRopeFreqScale,
                        onValueChange = { llamaCppRopeFreqScale = it },
                        valueRange = 0.1f..10.0f,
                        steps = 98,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "コンテキスト拡張用。1.0 = デフォルト",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            }
        }
    }

    private fun loadInferenceSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = settingsRepository.getInferenceConfig()
            val systemPrompt = settingsRepository.getSystemPrompt()
            val userName = settingsRepository.getUserName()
            val selectedModel = settingsRepository.getSelectedModel()
            val contextWindow = settingsRepository.getContextWindowForModel(selectedModel)
            val thinkingEnabled = settingsRepository.isGemmaThinkingEnabled()
            val threads = settingsRepository.getLlamaCppThreads()
            val gpuLayers = settingsRepository.getLlamaCppGpuLayers()
            val batchSize = settingsRepository.getLlamaCppBatchSize()
            val nKeep = settingsRepository.getLlamaCppNKeep()
            val ropeFreqBase = settingsRepository.getLlamaCppRopeFreqBase()
            val ropeFreqScale = settingsRepository.getLlamaCppRopeFreqScale()
            val historyLimit = settingsRepository.getChatHistoryLimit()
            contextWindowInput = contextWindow.toString()
            temperatureInput = config.temperature.toString()
            topkInput = config.maxTopK.toString()
            maxTokensInput = config.maxTokens.toString()
            contextCompressionEnabled = config.contextCompressionEnabled
            contextCompressionThresholdPercent = config.contextCompressionThresholdPercent
            userNameInput = userName
            systemPromptInput = systemPrompt
            backendType = config.backendType
            gemmaThinkingEnabled = thinkingEnabled
            themeMode = PreferencesHelper.getThemeMode(requireContext())
            maxThreads = InferenceConfig.MAX_THREADS
            llamaCppThreads = threads.coerceIn(1, maxThreads)
            llamaCppGpuLayers = gpuLayers
            llamaCppBatchSize = batchSize
            llamaCppNKeep = nKeep
            llamaCppRopeFreqBase = ropeFreqBase
            llamaCppRopeFreqScale = ropeFreqScale
            chatHistoryLimit = historyLimit
        }
    }

    private fun validateSettings(): String? {
        val temperature = temperatureInput.toFloatOrNull()
        val topK = topkInput.toIntOrNull()
        val maxTokens = maxTokensInput.toIntOrNull()
        val contextWindow = contextWindowInput.toIntOrNull()

        if (temperature == null || topK == null || maxTokens == null || contextWindow == null) {
            return "推論設定の入力値が不正です"
        }
        if (temperature !in InferenceConfig.MIN_TEMPERATURE..InferenceConfig.MAX_TEMPERATURE) {
            return "温度は ${InferenceConfig.MIN_TEMPERATURE} - ${InferenceConfig.MAX_TEMPERATURE} の範囲で入力してください"
        }
        if (topK !in InferenceConfig.MIN_TOP_K..InferenceConfig.MAX_TOP_K) {
            return "Top-K は ${InferenceConfig.MIN_TOP_K} - ${InferenceConfig.MAX_TOP_K} の範囲で入力してください"
        }
        if (maxTokens !in InferenceConfig.MIN_MAX_TOKENS..InferenceConfig.MAX_MAX_TOKENS) {
            return "Max Tokens は ${InferenceConfig.MIN_MAX_TOKENS} - ${InferenceConfig.MAX_MAX_TOKENS} の範囲で入力してください"
        }
        if (contextWindow !in 512..8192) {
            return "コンテキストは 512 - 8192 の範囲で入力してください"
        }
        if (contextCompressionThresholdPercent !in
            InferenceConfig.MIN_COMPRESSION_THRESHOLD..InferenceConfig.MAX_COMPRESSION_THRESHOLD
        ) {
            return "圧縮しきい値は ${InferenceConfig.MIN_COMPRESSION_THRESHOLD} - ${InferenceConfig.MAX_COMPRESSION_THRESHOLD} の範囲で入力してください"
        }
        return null
    }

    private suspend fun persistSettings() {
        val temperature = temperatureInput.toFloat()
        val topK = topkInput.toInt()
        val maxTokens = maxTokensInput.toInt()
        val contextWindow = contextWindowInput.toInt()

        settingsRepository.updateInferenceConfig(
            contextCompressionEnabled = contextCompressionEnabled,
            contextCompressionThresholdPercent = contextCompressionThresholdPercent,
            temperature = temperature,
            maxTopK = topK,
            maxTokens = maxTokens,
            contextWindow = contextWindow,
            backendType = backendType,
            backendTargetModel = "ALL"
        )
        settingsRepository.updateSystemPrompt(systemPromptInput)
        settingsRepository.updateUserName(userNameInput)
        settingsRepository.updateGemmaThinkingEnabled(gemmaThinkingEnabled)
        settingsRepository.updateLlamaCppThreads(llamaCppThreads)
        settingsRepository.updateLlamaCppGpuLayers(llamaCppGpuLayers)
        settingsRepository.updateLlamaCppBatchSize(llamaCppBatchSize)
        settingsRepository.updateLlamaCppNKeep(llamaCppNKeep)
        settingsRepository.updateLlamaCppRopeFreqBase(llamaCppRopeFreqBase)
        settingsRepository.updateLlamaCppRopeFreqScale(llamaCppRopeFreqScale)
        settingsRepository.updateChatHistoryLimit(chatHistoryLimit)
    }

    private fun onBackButtonPressed() {
        val error = validateSettings()
        if (error != null) {
            errorDialogMessage = error
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                persistSettings()
            }.onSuccess {
                if (isAdded) {
                    findNavController().navigateUp()
                }
            }.onFailure {
                toast("設定の保存に失敗しました: ${it.message}")
            }
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun NezumiComposeTheme(content: @Composable () -> Unit) {
        val bg = colorResource(id = R.color.bg_session_list)
        val primary = colorResource(id = R.color.primary)
        val onPrimary = colorResource(id = R.color.nezumi_on_primary)
        val primaryContainer = colorResource(id = R.color.nezumi_primary_container)
        val onPrimaryContainer = colorResource(id = R.color.nezumi_on_primary_container)
        val surface = colorResource(id = R.color.surface_card)
        val onSurface = colorResource(id = R.color.text_primary)
        val onSurfaceVariant = colorResource(id = R.color.text_secondary)

        val colorScheme = if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = primary,
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer,
                onSecondaryContainer = onPrimaryContainer,
                tertiary = primary,
                onTertiary = onPrimary,
                tertiaryContainer = primaryContainer,
                onTertiaryContainer = onPrimaryContainer,
                background = bg,
                onBackground = onSurface,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surface,
                onSurfaceVariant = onSurfaceVariant
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = primary,
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer,
                onSecondaryContainer = onPrimaryContainer,
                tertiary = primary,
                onTertiary = onPrimary,
                tertiaryContainer = primaryContainer,
                onTertiaryContainer = onPrimaryContainer,
                background = bg,
                onBackground = onSurface,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surface,
                onSurfaceVariant = onSurfaceVariant
            )
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            content = content
        )
    }
}
