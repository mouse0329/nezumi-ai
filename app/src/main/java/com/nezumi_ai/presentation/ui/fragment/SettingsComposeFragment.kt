package com.nezumi_ai.presentation.ui.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.dao.AlarmDao
import com.nezumi_ai.data.database.entity.AlarmEntity
import com.nezumi_ai.data.inference.HfAuthManager
import com.nezumi_ai.data.inference.HfOAuthManager
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.NezumiTool
import com.nezumi_ai.data.inference.ToolPreferences
import com.nezumi_ai.data.inference.ProjectConfig
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.tools.ToolSystemController
import com.nezumi_ai.presentation.ui.screen.ThemeModeCard
import com.nezumi_ai.utils.ImportedModelCapabilities
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

class SettingsComposeFragment : Fragment() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var alarmDao: AlarmDao
    private lateinit var toolPreferences: ToolPreferences
    private var authService: AuthorizationService? = null
    private var pendingDownloadPermissionModel: ModelFileManager.LocalModel? = null

    private var hfLinked by mutableStateOf(false)
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
    private var importedTasks by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())
    private var managedAlarms by mutableStateOf<List<AlarmEntity>>(emptyList())
    private var toolEnabled by mutableStateOf<Map<NezumiTool, Boolean>>(emptyMap())
    private var isImportingModel by mutableStateOf(false)
    private var capabilityDialogModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var capabilityDialogImageEnabled by mutableStateOf(false)
    private var capabilityDialogAudioEnabled by mutableStateOf(false)

    private val modelStates = mutableStateMapOf<ModelFileManager.LocalModel, ModelUiState>()

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            val authResponse = AuthorizationResponse.fromIntent(data)
            val authError = AuthorizationException.fromIntent(data)
            if (authError != null) {
                toast("OAuth失敗: ${authError.errorDescription}")
                return@registerForActivityResult
            }
            if (authResponse == null) {
                toast("OAuthレスポンスが取得できませんでした")
                return@registerForActivityResult
            }
            exchangeToken(authResponse)
        }

    private val importTaskLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            isImportingModel = true
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ModelFileManager.importTaskFromUri(requireContext(), uri)
                }
                result.onSuccess {
                    toast("モデルを追加しました: ${it.name}")
                    refreshImportedTasks()
                    val imported = ModelFileManager.ImportedTaskModel(
                        name = it.nameWithoutExtension,
                        path = it.absolutePath
                    )
                    capabilityDialogModel = imported
                    capabilityDialogImageEnabled = false
                    capabilityDialogAudioEnabled = false
                }.onFailure {
                    toast("追加失敗: ${it.message}")
                }
                isImportingModel = false
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            pendingDownloadPermissionModel?.let { runModelDownload(it) }
            pendingDownloadPermissionModel = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository(db.settingsDao(), db.chatSessionDao())
        alarmDao = db.alarmDao()
        toolPreferences = ToolPreferences(requireContext())
        authService = AuthorizationService(requireContext())
        ModelFileManager.LocalModel.entries.forEach { modelStates[it] = ModelUiState(titleFor(it)) }
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
        renderHfTokenState()
        loadInferenceSettings()
        loadToolSettings()
        observeManagedAlarms()
        refreshImportedTasks()
        refreshModelStatus()
        observeDownloadWork()
    }

    @Composable
    private fun SettingsScreen() {
        if (isImportingModel) {
            ImportingDialog()
        }
        capabilityDialogModel?.let { model ->
            ImportedCapabilityDialog(model)
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
                    IconButton(onClick = { findNavController().navigateUp() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = stringResource(id = R.string.back),
                            tint = colorResource(id = R.color.text_primary)
                        )
                    }
                    Text(
                        text = stringResource(id = R.string.action_settings),
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
            item { HfCard() }
            item { InferenceCard() }
            item { ToolSettingsCard() }
            item { AlarmSettingsCard() }
            items(ModelFileManager.LocalModel.entries.toList(), key = { it.name }) { model ->
                ModelCard(model)
            }
            items(importedTasks, key = { it.path }) { model ->
                ImportedModelCard(model)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importTaskLauncher.launch(arrayOf("*/*")) }) {
                        Text(text = stringResource(id = R.string.import_task_model))
                    }
                    TextButton(onClick = { findNavController().navigate(R.id.action_settingsFragment_to_licenseFragment) }) {
                        Text(text = stringResource(id = R.string.open_license_page))
                    }
                }
            }
        }
    }

    @Composable
    private fun ImportingDialog() {
        Dialog(onDismissRequest = {}) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colorResource(id = R.color.primary)
                    )
                    Text(
                        text = stringResource(id = R.string.import_task_loading),
                        color = colorResource(id = R.color.text_primary)
                    )
                }
            }
        }
    }

    @Composable
    private fun ImportedCapabilityDialog(model: ModelFileManager.ImportedTaskModel) {
        Dialog(onDismissRequest = {}) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "追加モデルの機能設定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = model.name,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("画像入力を有効化")
                        Switch(
                            checked = capabilityDialogImageEnabled,
                            onCheckedChange = { capabilityDialogImageEnabled = it }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("音声入力を有効化")
                        Switch(
                            checked = capabilityDialogAudioEnabled,
                            onCheckedChange = { capabilityDialogAudioEnabled = it }
                        )
                    }
                    Text(
                        text = "標準は画像・音声とも無効です",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = {
                            ImportedModelCapabilityStore.set(
                                requireContext(),
                                model.path,
                                ImportedModelCapabilities(
                                    imageEnabled = capabilityDialogImageEnabled,
                                    audioEnabled = capabilityDialogAudioEnabled
                                )
                            )
                            capabilityDialogModel = null
                            toast("モデル機能設定を保存しました")
                        }) {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HfCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(id = R.string.hf_token_title), fontWeight = FontWeight.Bold)
                Text(
                    text = if (hfLinked) stringResource(id = R.string.hf_auth_linked) else stringResource(id = R.string.hf_auth_not_linked),
                    color = colorResource(id = R.color.text_secondary)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startOAuthLogin() }) { Text(stringResource(id = R.string.hf_oauth_login)) }
                    TextButton(onClick = { logoutHf() }, enabled = hfLinked) { Text(stringResource(id = R.string.hf_oauth_logout)) }
                }
            }
        }
    }

    @Composable
    private fun InferenceCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(id = R.string.inference_title), fontWeight = FontWeight.Bold)
                OutlinedTextField(value = contextWindowInput, onValueChange = { contextWindowInput = it }, label = { Text(stringResource(id = R.string.context_window_label)) })
                OutlinedTextField(value = temperatureInput, onValueChange = { temperatureInput = it }, label = { Text(stringResource(id = R.string.temperature_label)) })
                OutlinedTextField(value = topkInput, onValueChange = { topkInput = it }, label = { Text(stringResource(id = R.string.topk_label)) })
                OutlinedTextField(value = maxTokensInput, onValueChange = { maxTokensInput = it }, label = { Text(stringResource(id = R.string.max_tokens_label)) })
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
                Text(
                    text = stringResource(
                        id = R.string.context_compression_threshold_format,
                        contextCompressionThresholdPercent
                    ),
                    color = colorResource(id = R.color.text_secondary)
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
                        InferenceConfig.MIN_COMPRESSION_THRESHOLD - 1,
                    enabled = contextCompressionEnabled
                )
                OutlinedTextField(value = userNameInput, onValueChange = { userNameInput = it }, label = { Text(stringResource(id = R.string.user_name_label)) })
                OutlinedTextField(value = systemPromptInput, onValueChange = { systemPromptInput = it }, label = { Text(stringResource(id = R.string.system_prompt_label)) })
                Text(
                    text = "現在のバックエンド: $backendType",
                    color = colorResource(id = R.color.text_secondary)
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
                        label = { Text("GPU+CPU") }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Gemma 4 シンキングを有効化",
                        color = colorResource(id = R.color.text_primary)
                    )
                    Switch(
                        checked = gemmaThinkingEnabled,
                        onCheckedChange = {
                            gemmaThinkingEnabled = it
                            viewLifecycleOwner.lifecycleScope.launch {
                                settingsRepository.updateGemmaThinkingEnabled(it)
                            }
                        }
                    )
                }
                Button(onClick = { saveInferenceSettings() }) {
                    Text(stringResource(id = R.string.save_inference))
                }
            }
        }
    }

    @Composable
    private fun ToolSettingsCard() {
        val setAlarmEnabled = toolEnabled[NezumiTool.SET_ALARM] ?: false
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "ツール設定", fontWeight = FontWeight.Bold)
                NezumiTool.entries.forEach { tool ->
                    val enabled = toolEnabled[tool] ?: (tool == NezumiTool.GET_TIME)
                    val canToggle = tool != NezumiTool.LIST_ALARMS || setAlarmEnabled
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = tool.displayName, color = colorResource(id = R.color.text_primary))
                        Switch(
                            checked = enabled,
                            enabled = canToggle,
                            onCheckedChange = { checked -> updateToolEnabled(tool, checked) }
                        )
                    }
                    if (tool == NezumiTool.LIST_ALARMS && !setAlarmEnabled) {
                        Text(
                            text = "「アラームセット」が有効な場合のみ使用できます",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AlarmSettingsCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "アラーム管理", fontWeight = FontWeight.Bold)
                if (managedAlarms.isEmpty()) {
                    Text("登録されたアラームはありません", color = colorResource(id = R.color.text_secondary))
                } else {
                    managedAlarms.forEach { alarm ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%02d:%02d %s",
                                    alarm.hour,
                                    alarm.minute,
                                    alarm.label
                                ),
                                color = colorResource(id = R.color.text_primary)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Switch(
                                    checked = alarm.enabled,
                                    onCheckedChange = { checked ->
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            alarmDao.setEnabled(alarm.id, checked)
                                        }
                                    }
                                )
                                TextButton(onClick = { dismissAndDeleteAlarm(alarm) }) {
                                    Text(stringResource(id = R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModelCard(model: ModelFileManager.LocalModel) {
        val state = modelStates[model] ?: return
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = state.title, fontWeight = FontWeight.Bold)
                Text(text = state.status, color = colorResource(id = R.color.text_secondary))
                if (state.isDownloading) {
                    if (state.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = colorResource(id = R.color.primary),
                            trackColor = colorResource(id = R.color.context_meter_track)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = colorResource(id = R.color.primary),
                            trackColor = colorResource(id = R.color.context_meter_track)
                        )
                    }
                    Text(text = state.progressText, color = colorResource(id = R.color.text_secondary))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.isDownloaded || state.isDownloading) {
                        Button(onClick = {
                            if (state.isDownloading) {
                                ModelDownloadWorker.cancel(requireContext(), model)
                            } else {
                                requestNotificationPermissionForDownload(model)
                            }
                        }) {
                            Text(if (state.isDownloading) "キャンセル" else "ダウンロード")
                        }
                    }
                    TextButton(onClick = {
                        val ok = ModelFileManager.deleteModel(requireContext(), model)
                        toast(if (ok) "削除しました" else "削除に失敗しました")
                        refreshModelStatus(model)
                    }) { Text(stringResource(id = R.string.delete)) }
                    if (state.showAccessButton) {
                        TextButton(onClick = { openHfModelAccessPage(model) }) {
                            Text(stringResource(id = R.string.open_hf_approval_page))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ImportedModelCard(model: ModelFileManager.ImportedTaskModel) {
        val caps = ImportedModelCapabilityStore.get(requireContext(), model.path)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = model.name, fontWeight = FontWeight.Bold)
                Text(text = "追加済みモデル", color = colorResource(id = R.color.text_secondary))
                Text(
                    text = "画像: ${if (caps.imageEnabled) "ON" else "OFF"} / 音声: ${if (caps.audioEnabled) "ON" else "OFF"}",
                    color = colorResource(id = R.color.text_secondary)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        capabilityDialogModel = model
                        capabilityDialogImageEnabled = caps.imageEnabled
                        capabilityDialogAudioEnabled = caps.audioEnabled
                    }) {
                        Text("機能設定")
                    }
                    TextButton(onClick = {
                        val result = ModelFileManager.deleteImportedTask(requireContext(), model.path)
                        result.onSuccess {
                            ImportedModelCapabilityStore.clear(requireContext(), model.path)
                            toast("削除しました")
                            refreshImportedTasks()
                        }.onFailure {
                            toast("削除に失敗しました: ${it.message}")
                        }
                    }) {
                        Text(stringResource(id = R.string.delete))
                    }
                }
            }
        }
    }

    private fun renderHfTokenState() {
        val token = HfAuthManager.getToken(requireContext())
        hfLinked = token.isNotBlank()
        themeMode = PreferencesHelper.getThemeMode(requireContext())
    }

    private fun maskToken(token: String): String {
        if (token.length <= 6) return "******"
        return token.take(3) + "*".repeat((token.length - 7).coerceAtLeast(6)) + token.takeLast(4)
    }

    private fun logoutHf() {
        HfAuthManager.clearToken(requireContext())
        renderHfTokenState()
        toast("ログアウトしました")
    }

    private fun loadInferenceSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = settingsRepository.getInferenceConfig()
            val systemPrompt = settingsRepository.getSystemPrompt()
            val userName = settingsRepository.getUserName()
            val selectedModel = settingsRepository.getSelectedModel()
            val contextWindow = settingsRepository.getContextWindowForModel(selectedModel)
            val thinkingEnabled = settingsRepository.isGemmaThinkingEnabled()
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
        }
    }

    private fun loadToolSettings() {
        val loaded = NezumiTool.entries.associateWith { toolPreferences.isEnabled(it) }
        toolEnabled = loaded
    }

    private fun updateToolEnabled(tool: NezumiTool, enabled: Boolean) {
        Log.d("SettingsCompose", "updateToolEnabled: tool=$tool, enabled=$enabled")
        if (tool == NezumiTool.LIST_ALARMS && !(toolEnabled[NezumiTool.SET_ALARM] ?: true) && enabled) {
            toast("「アラームセット」を有効化してください")
            return
        }
        
        // SET_ALARMは権限チェックなしで直接有効化
        // (SET_ALARM は system-level 権限で checkSelfPermission でチェック不可)
        toolPreferences.setEnabled(tool, enabled)
        if (tool == NezumiTool.SET_ALARM && !enabled) {
            toolPreferences.setEnabled(NezumiTool.LIST_ALARMS, false)
        }
        loadToolSettings()
        
        if (enabled) {
            Log.d("SettingsCompose", "Tool enabled: $tool")
            if (tool == NezumiTool.SET_ALARM) {
                toast("アラームセットが有効になりました")
            }
        }
    }

    private fun observeManagedAlarms() {
        viewLifecycleOwner.lifecycleScope.launch {
            alarmDao.observeAll().collect { rows ->
                managedAlarms = rows
            }
        }
    }

    private fun dismissAndDeleteAlarm(alarm: AlarmEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            ToolSystemController.dismissAlarm(requireContext(), alarm.hour, alarm.minute)
            alarmDao.deleteById(alarm.id)
        }
    }

    private fun saveInferenceSettings() {
        val temperature = temperatureInput.toFloatOrNull()
        val topK = topkInput.toIntOrNull()
        val maxTokens = maxTokensInput.toIntOrNull()
        val contextWindow = contextWindowInput.toIntOrNull()
        if (temperature == null || topK == null || maxTokens == null || contextWindow == null) {
            toast("推論設定の入力値が不正です")
            return
        }
        if (temperature !in InferenceConfig.MIN_TEMPERATURE..InferenceConfig.MAX_TEMPERATURE) {
            toast("温度は ${InferenceConfig.MIN_TEMPERATURE} - ${InferenceConfig.MAX_TEMPERATURE} の範囲で入力してください")
            return
        }
        if (topK !in InferenceConfig.MIN_TOP_K..InferenceConfig.MAX_TOP_K) {
            toast("Top-K は ${InferenceConfig.MIN_TOP_K} - ${InferenceConfig.MAX_TOP_K} の範囲で入力してください")
            return
        }
        if (maxTokens !in InferenceConfig.MIN_MAX_TOKENS..InferenceConfig.MAX_MAX_TOKENS) {
            toast("Max Tokens は ${InferenceConfig.MIN_MAX_TOKENS} - ${InferenceConfig.MAX_MAX_TOKENS} の範囲で入力してください")
            return
        }
        if (contextWindow !in 512..8192) {
            toast("コンテキストは 512 - 8192 の範囲で入力してください")
            return
        }
        if (contextCompressionThresholdPercent !in
            InferenceConfig.MIN_COMPRESSION_THRESHOLD..InferenceConfig.MAX_COMPRESSION_THRESHOLD
        ) {
            toast(
                "圧縮しきい値は ${InferenceConfig.MIN_COMPRESSION_THRESHOLD} - " +
                    "${InferenceConfig.MAX_COMPRESSION_THRESHOLD} の範囲で入力してください"
            )
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
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
            loadInferenceSettings()
            toast("推論設定を保存しました")
        }
    }

    private fun refreshImportedTasks() {
        importedTasks = ModelFileManager.listImportedTaskModels(requireContext())
    }

    private fun requestNotificationPermissionForDownload(model: ModelFileManager.LocalModel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownloadPermissionModel = model
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        runModelDownload(model)
    }

    private fun runModelDownload(model: ModelFileManager.LocalModel) {
        val enqueued = ModelDownloadWorker.enqueue(requireContext(), model)
        if (!enqueued) toast("すでにダウンロード中です")
    }

    private fun refreshModelStatus(model: ModelFileManager.LocalModel? = null) {
        val targets = model?.let { listOf(it) } ?: ModelFileManager.LocalModel.entries
        targets.forEach {
            val downloaded = ModelFileManager.isDownloaded(requireContext(), it)
            val state = modelStates[it] ?: return@forEach
            state.isDownloaded = downloaded
            state.status = if (downloaded) "ダウンロード済み" else "未ダウンロード"
            if (!state.isDownloading) {
                state.progressText = ""
                state.progress = 0f
                state.showAccessButton = false
            }
        }
    }

    private fun observeDownloadWork() {
        ModelFileManager.LocalModel.entries.forEach { model ->
            WorkManager.getInstance(requireContext())
                .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.modelWorkName(model))
                .observe(viewLifecycleOwner) { infos ->
                    val info = infos.maxByOrNull { it.runAttemptCount }
                    renderDownloadState(model, info)
                }
        }
    }

    private fun renderDownloadState(model: ModelFileManager.LocalModel, workInfo: WorkInfo?) {
        val state = modelStates[model] ?: return
        if (workInfo == null) {
            state.isDownloading = false
            refreshModelStatus(model)
            return
        }
        when (workInfo.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                state.isDownloading = true
                val downloaded = workInfo.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                val total = workInfo.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                if (total > 0L) {
                    val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    state.progress = percent / 100f
                    state.progressText = "$percent% (${formatGb(downloaded)} / ${formatGb(total)})"
                    state.status = "ダウンロード中"
                } else {
                    state.progressText = "準備中..."
                    state.status = "ダウンロード待機中"
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                state.isDownloading = false
                state.progress = 1f
                state.progressText = ""
                state.showAccessButton = false
                state.isDownloaded = true
                refreshModelStatus(model)
            }
            WorkInfo.State.FAILED -> {
                state.isDownloading = false
                state.progressText = ""
                val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE) ?: "ダウンロード失敗"
                state.status = "失敗: $error"
                state.showAccessButton = error.contains("HTTP 403", ignoreCase = true)
            }
            WorkInfo.State.CANCELLED -> {
                state.isDownloading = false
                state.progressText = ""
                refreshModelStatus(model)
            }
        }
    }

    private fun startOAuthLogin() {
        if (hfLinked) {
            toast("すでに連携済みです。切り替える場合は先にログアウトしてください")
            return
        }
        if (ProjectConfig.HF_CLIENT_ID == "REPLACE_WITH_HF_CLIENT_ID") {
            toast("ProjectConfig.HF_CLIENT_ID を設定してください")
            return
        }
        val request = HfOAuthManager.buildAuthorizationRequest()
        val intent = authService?.getAuthorizationRequestIntent(request) ?: return
        authLauncher.launch(intent)
    }

    private fun exchangeToken(response: AuthorizationResponse) {
        val tokenRequest = HfOAuthManager.buildTokenRequest(response)
        val service = authService ?: return
        HfOAuthManager.performTokenRequest(service, tokenRequest) { accessToken, error ->
            requireActivity().runOnUiThread {
                if (accessToken.isNullOrBlank()) {
                    toast("トークン取得失敗: $error")
                    return@runOnUiThread
                }
                HfAuthManager.setToken(requireContext(), accessToken)
                renderHfTokenState()
                toast("OAuthログイン成功")
            }
        }
    }

    private fun openHfModelAccessPage(model: ModelFileManager.LocalModel) {
        val url = ModelFileManager.previewTreeUrl(model)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            toast("ブラウザを起動できませんでした")
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.2fGB", gb)
    }

    private fun titleFor(model: ModelFileManager.LocalModel): String {
        return when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "Gemma 3n E2B"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "Gemma 3n E4B"
            ModelFileManager.LocalModel.GEMMA4_2B -> "Gemma 4 2B"
            ModelFileManager.LocalModel.GEMMA4_4B -> "Gemma 4 4B"
        }
    }

    override fun onDestroyView() {
        authService?.dispose()
        authService = null
        super.onDestroyView()
    }

    private class ModelUiState(val title: String) {
        var status by mutableStateOf("未ダウンロード")
        var progress by mutableFloatStateOf(0f)
        var progressText by mutableStateOf("")
        var isDownloading by mutableStateOf(false)
        var showAccessButton by mutableStateOf(false)
        var isDownloaded by mutableStateOf(false)
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
