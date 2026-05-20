package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.repository.SettingsRepository

import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsComposeFragment : Fragment() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var memoryRepository: MemoryRepository

    private var contextWindowInput by mutableStateOf("4096")
    private var temperatureInput by mutableStateOf("0.7")
    private var topkInput by mutableStateOf("40")
    private var maxTokensInput by mutableStateOf("1024")
    private var contextCompressionEnabled by mutableStateOf(false)
    private var contextCompressionThresholdPercent by mutableStateOf(70)
    private var preloadMemoryWarningThresholdPercent by mutableStateOf(250)
    private var userNameInput by mutableStateOf("")
    private var systemPromptInput by mutableStateOf("")
    private var selectedModel by mutableStateOf("E2B")
    private var backendType by mutableStateOf("CPU")
    private var themeMode by mutableStateOf(PreferencesHelper.THEME_SYSTEM)
    private var errorDialogMessage by mutableStateOf<String?>(null)
    private var versionDialogVisible by mutableStateOf(false)
    private var aboutDialogVisible by mutableStateOf(false)
    private var llamaCppThreads by mutableStateOf(InferenceConfig.getDefaultThreadCount())
    private var maxThreads by mutableStateOf(InferenceConfig.MAX_THREADS)
    private var llamaCppGpuLayers by mutableStateOf(0)
    private var llamaCppBatchSize by mutableStateOf(512)
    private var llamaCppNKeep by mutableStateOf(0)
    private var llamaCppRopeFreqBase by mutableStateOf(0.0f)
    private var llamaCppRopeFreqScale by mutableStateOf(1.0f)
    private var chatHistoryLimit by mutableStateOf(30)
    private var sdSteps by mutableStateOf(8)
    private var sdCfg by mutableStateOf(7.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository(db.settingsDao(), db.chatSessionDao())
        memoryRepository = MemoryRepository(db.memoryDao())
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

        if (versionDialogVisible) {
            VersionInfoDialog(
                onDismiss = { versionDialogVisible = false }
            )
        }
        if (aboutDialogVisible) {
            AboutDialog(
                onDismiss = { aboutDialogVisible = false }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.statusBarsPadding()) }
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
            
            item { GeneralSettingsCard() }
            item { PersonalizationCard() }
            item { InferenceParamsCard() }
            item { ImageGenSettingsCard() }
            item { MemoryManagementCard() }
            item { ChatHistoryCard() }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { aboutDialogVisible = true }) {
                        Text(text = "このアプリについて")
                    }
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
    private fun GeneralSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "全般設定", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                
                // Theme Mode Selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "テーマ (現在: ${when(themeMode) {
                            PreferencesHelper.THEME_LIGHT -> "ライト"
                            PreferencesHelper.THEME_DARK -> "ダーク"
                            else -> "システム"
                        }})",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = themeMode == PreferencesHelper.THEME_SYSTEM,
                            onClick = {
                                themeMode = PreferencesHelper.THEME_SYSTEM
                                PreferencesHelper.setThemeMode(requireContext(), PreferencesHelper.THEME_SYSTEM)
                                PreferencesHelper.applyThemeMode(requireContext())
                            },
                            label = { Text("システム") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = themeMode == PreferencesHelper.THEME_LIGHT,
                            onClick = {
                                themeMode = PreferencesHelper.THEME_LIGHT
                                PreferencesHelper.setThemeMode(requireContext(), PreferencesHelper.THEME_LIGHT)
                                PreferencesHelper.applyThemeMode(requireContext())
                            },
                            label = { Text("ライト") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = themeMode == PreferencesHelper.THEME_DARK,
                            onClick = {
                                themeMode = PreferencesHelper.THEME_DARK
                                PreferencesHelper.setThemeMode(requireContext(), PreferencesHelper.THEME_DARK)
                                PreferencesHelper.applyThemeMode(requireContext())
                            },
                            label = { Text("ダーク") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                Divider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                
                // Backend Selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "バックエンド (現在: $backendType)",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = backendType == "CPU",
                            onClick = { backendType = "CPU" },
                            label = { Text("CPU") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = backendType == "GPU",
                            onClick = { backendType = "GPU" },
                            label = { Text("GPU") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = backendType == "NPU",
                            onClick = { backendType = "NPU" },
                            label = { Text("NPU") },
                            modifier = Modifier.weight(1f)
                        )
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
                TextButton(
                    onClick = { versionDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("llama.cpp / LiteRT-LM バージョンを確認")
                }
            }
        }
    }

    @Composable
    private fun InferenceParamsCard() {
        // モデル別のコンテキスト最大値
        val maxContextWindow = if (selectedModel.equals("Gemma4-2B", ignoreCase = true) || 
                                    selectedModel.equals("Gemma4-4B", ignoreCase = true)) {
            8192
        } else {
            4096
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "推論パラメータ", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                
                // コンテキストサイズと最大トークン数を2列グリッド
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = contextWindowInput,
                        onValueChange = { contextWindowInput = it },
                        label = { Text("コンテキストサイズ") },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = maxTokensInput,
                        onValueChange = { maxTokensInput = it },
                        label = { Text("最大トークン数") },
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                        singleLine = true
                    )
                }
                
                // Temperature Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "温度 (Temperature)",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = temperatureInput,
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = temperatureInput.toFloatOrNull() ?: 0.7f,
                        onValueChange = { temperatureInput = String.format("%.1f", it) },
                        valueRange = 0f..1.5f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Top-K Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Top-K",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = topkInput,
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = topkInput.toIntOrNull()?.toFloat() ?: 40f,
                        onValueChange = { topkInput = it.toInt().toString() },
                        valueRange = 1f..100f,
                        steps = 98,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                Divider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                
                // Context Compression Toggle and Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "コンテキスト圧縮 (ベータ版)",
                            color = colorResource(id = R.color.text_primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Switch(
                        checked = contextCompressionEnabled,
                        onCheckedChange = { contextCompressionEnabled = it }
                    )
                }
                
                if (contextCompressionEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "圧縮しきい値",
                                color = colorResource(id = R.color.text_secondary),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${contextCompressionThresholdPercent}%",
                                color = colorResource(id = R.color.primary),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
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
                                InferenceConfig.MIN_COMPRESSION_THRESHOLD - 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "メモリ使用量がこの割合を超えると自動圧縮",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "プリロードメモリ警告閾値",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${preloadMemoryWarningThresholdPercent}%",
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = preloadMemoryWarningThresholdPercent.toFloat(),
                        onValueChange = { value ->
                            preloadMemoryWarningThresholdPercent = value.roundToInt().coerceIn(
                                MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT,
                                MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT
                            )
                        },
                        valueRange = MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT.toFloat()..
                            MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT.toFloat(),
                        steps = MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT -
                            MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "モデルサイズが利用可能な空きメモリのこの割合を超えると警告します",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                // Advanced Llama.cpp Settings (Collapsible)
                var expanded by remember { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Divider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.2f), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "高度な Llama.cpp 設定",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (expanded) "▼" else "▶",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    
                    if (expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // CPU スレッド数
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "CPU スレッド数",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = llamaCppThreads.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppThreads.toFloat(),
                                    onValueChange = { llamaCppThreads = it.roundToInt() },
                                    valueRange = 1f..maxThreads.toFloat(),
                                    steps = maxOf(0, maxThreads - 2),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // GPU レイヤー数
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "GPU レイヤー数 (Offload)",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = llamaCppGpuLayers.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppGpuLayers.toFloat(),
                                    onValueChange = { llamaCppGpuLayers = it.roundToInt() },
                                    valueRange = 0f..128f,
                                    steps = 127,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // バッチサイズ
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "バッチサイズ",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = llamaCppBatchSize.toString(),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppBatchSize.toFloat(),
                                    onValueChange = { llamaCppBatchSize = it.roundToInt().coerceIn(32, 2048) },
                                    valueRange = 32f..2048f,
                                    steps = 2016/32 - 1,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // RoPE周波数基数
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "RoPE周波数基数",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedTextField(
                                    value = String.format("%.1f", llamaCppRopeFreqBase),
                                    onValueChange = { newValue ->
                                        newValue.toFloatOrNull()?.let { llamaCppRopeFreqBase = it }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Text(
                                    text = "0 = 自動設定",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            // RoPE周波数スケール
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "RoPE周波数スケール",
                                        color = colorResource(id = R.color.text_secondary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = String.format("%.2f", llamaCppRopeFreqScale),
                                        color = colorResource(id = R.color.primary),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Slider(
                                    value = llamaCppRopeFreqScale,
                                    onValueChange = { llamaCppRopeFreqScale = it },
                                    valueRange = 0.5f..5.0f,
                                    steps = 44,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "1.0 = デフォルト",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "個人化設定", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "ユーザー名",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = userNameInput,
                        onValueChange = { userNameInput = it },
                        placeholder = { Text("ユーザー名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "システムプロンプト",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = systemPromptInput,
                        onValueChange = { systemPromptInput = it },
                        placeholder = { Text("AIの振る舞いやルールを入力...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp),
                        minLines = 3
                    )
                }
            }
        }
    }

    @Composable
    private fun ImageGenSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "画像生成設定", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                
                // ステップ数 Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ステップ数",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$sdSteps / 50",
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = sdSteps.toFloat(),
                        onValueChange = { sdSteps = it.toInt() },
                        valueRange = 1f..50f,
                        steps = 48,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // CFG Scale Slider
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CFG スケール",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format("%.1f", sdCfg),
                            color = colorResource(id = R.color.primary),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = sdCfg,
                        onValueChange = { sdCfg = it },
                        valueRange = 1f..20f,
                        steps = 38,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable
    private fun MemoryManagementCard() {
        val memories by memoryRepository.observeMemories().collectAsState(initial = emptyList())
        var confirmDeleteAll by remember { mutableStateOf(false) }

        if (confirmDeleteAll) {
            AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                title = { Text("メモリを全削除") },
                text = { Text("保存済みメモリをすべて削除します。") },
                confirmButton = {
                    Button(onClick = {
                        viewLifecycleOwner.lifecycleScope.launch {
                            memoryRepository.softDeleteAll()
                            confirmDeleteAll = false
                            toast("メモリを削除しました")
                        }
                    }) {
                        Text("削除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteAll = false }) {
                        Text("キャンセル")
                    }
                }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "メモリ管理", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                        Text(
                            text = "${memories.size}件のメモリ",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        enabled = memories.isNotEmpty(),
                        onClick = { confirmDeleteAll = true }
                    ) {
                        Text("全削除")
                    }
                }

                if (memories.isEmpty()) {
                    Text(
                        text = "保存されたメモリはありません",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    memories.take(20).forEach { memory ->
                        Divider(color = colorResource(id = R.color.text_secondary).copy(alpha = 0.14f), thickness = 1.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = memory.content,
                                    color = colorResource(id = R.color.text_primary),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "重要度 ${String.format("%.2f", memory.importance)} / 参照 ${memory.accessCount}回",
                                    color = colorResource(id = R.color.text_secondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    memoryRepository.softDelete(memory.id)
                                }
                            }) {
                                Text("削除")
                            }
                        }
                    }
                    if (memories.size > 20) {
                        Text(
                            text = "最新20件を表示中",
                            color = colorResource(id = R.color.text_secondary),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "チャット履歴管理", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                
                Text(
                    text = "履歴保存件数",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = chatHistoryLimit == 10,
                        onClick = { chatHistoryLimit = 10 },
                        label = { Text("10") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 30,
                        onClick = { chatHistoryLimit = 30 },
                        label = { Text("30") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == 50,
                        onClick = { chatHistoryLimit = 50 },
                        label = { Text("50") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = chatHistoryLimit == -1,
                        onClick = { chatHistoryLimit = -1 },
                        label = { Text("無制限") },
                        modifier = Modifier.weight(1f)
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
            val model = settingsRepository.getSelectedModel()
            selectedModel = model
            val contextWindow = settingsRepository.getContextWindowForModel(model)
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
            preloadMemoryWarningThresholdPercent = settingsRepository.getPreloadMemoryWarningThresholdPercent()
            contextCompressionEnabled = config.contextCompressionEnabled
            contextCompressionThresholdPercent = config.contextCompressionThresholdPercent
            userNameInput = userName
            systemPromptInput = systemPrompt
            backendType = config.backendType
            themeMode = PreferencesHelper.getThemeMode(requireContext())
            maxThreads = InferenceConfig.MAX_THREADS
            llamaCppThreads = threads.coerceIn(1, maxThreads)
            llamaCppGpuLayers = gpuLayers
            llamaCppBatchSize = batchSize
            llamaCppNKeep = nKeep
            llamaCppRopeFreqBase = ropeFreqBase
            llamaCppRopeFreqScale = ropeFreqScale
            chatHistoryLimit = historyLimit
            sdSteps = PreferencesHelper.getSdSteps(requireContext())
            sdCfg = PreferencesHelper.getSdCfg(requireContext())
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
        // モデル別のコンテキストウィンドウ制限を確認
        val maxContextWindow = if (selectedModel.equals("Gemma4-2B", ignoreCase = true) || 
                                    selectedModel.equals("Gemma4-4B", ignoreCase = true)) {
            8192
        } else {
            4096
        }
        if (contextWindow !in 512..maxContextWindow) {
            return "コンテキストは 512 - $maxContextWindow の範囲で入力してください"
        }
        if (contextCompressionThresholdPercent !in
            InferenceConfig.MIN_COMPRESSION_THRESHOLD..InferenceConfig.MAX_COMPRESSION_THRESHOLD
        ) {
            return "圧縮しきい値は ${InferenceConfig.MIN_COMPRESSION_THRESHOLD} - ${InferenceConfig.MAX_COMPRESSION_THRESHOLD} の範囲で入力してください"
        }
        if (preloadMemoryWarningThresholdPercent !in
            MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT..MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT
        ) {
            return "プリロードメモリ警告閾値は ${MemoryObserver.MIN_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT} - ${MemoryObserver.MAX_PRELOAD_MEMORY_WARNING_THRESHOLD_PERCENT} の範囲で設定してください"
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
        settingsRepository.updatePreloadMemoryWarningThresholdPercent(preloadMemoryWarningThresholdPercent)
        settingsRepository.updateSystemPrompt(systemPromptInput)
        settingsRepository.updateUserName(userNameInput)
        settingsRepository.updateLlamaCppThreads(llamaCppThreads)
        settingsRepository.updateLlamaCppGpuLayers(llamaCppGpuLayers)
        settingsRepository.updateLlamaCppBatchSize(llamaCppBatchSize)
        settingsRepository.updateLlamaCppNKeep(llamaCppNKeep)
        settingsRepository.updateLlamaCppRopeFreqBase(llamaCppRopeFreqBase)
        settingsRepository.updateLlamaCppRopeFreqScale(llamaCppRopeFreqScale)
        settingsRepository.updateChatHistoryLimit(chatHistoryLimit)
        PreferencesHelper.setSdSteps(requireContext(), sdSteps)
        PreferencesHelper.setSdCfg(requireContext(), sdCfg)
    }

    @Composable
    private fun VersionInfoDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("推論エンジンのバージョン") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LiteRT-LM: ${BuildConfig.LITERTLM_VERSION}")
                    Text("llama.cpp: ${BuildConfig.LLAMACPP_VERSION}")
                    Text(
                        "※実行時には内部 JNI / モデル対応により挙動が変わる場合があります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        )
    }

    @Composable
    private fun AboutDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("このアプリについて") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nezumi AI")
                    Text("バージョン: ${BuildConfig.VERSION_NAME}")
                    Text("ビルド: ${BuildConfig.VERSION_CODE}")
                    Text(
                        "Nezumi AI は端末上でのAI推論とチャット体験を提供します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        )
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
