package com.nezumi_ai.presentation.ui.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.Context
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SetupWizardFragment : Fragment() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sessionRepository: ChatSessionRepository

    private var currentStep by mutableStateOf(0)
    private var selectedBackend by mutableStateOf("CPU")
    private var selectedModel by mutableStateOf<String?>(null)
    private var pendingDownloadModel: ModelFileManager.LocalModel? = null
    private var isCompleting by mutableStateOf(false)

    private val modelStates = mutableStateMapOf<ModelFileManager.LocalModel, DownloadUiState>()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            pendingDownloadModel?.let { enqueueModelDownload(it) }
            pendingDownloadModel = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository(database.settingsDao(), database.chatSessionDao())
        sessionRepository = ChatSessionRepository(
            database.chatSessionDao(),
            settingsRepository,
            MessageRepository(database.messageDao())
        )
        ModelFileManager.LocalModel.entries.forEach { model ->
            modelStates[model] = DownloadUiState(isDownloaded = false)
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { SetupWizardScreen() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ★ MainActivity で既にセットアップ完了チェックされているため、ここでの重複チェック不要
        // セットアップ画面が一瞬表示される問題を回避

        refreshModelStatus()
        observeDownloadWork()

        viewLifecycleOwner.lifecycleScope.launch {
            selectedBackend = settingsRepository.getBackendForModel(settingsRepository.getSelectedModel())
            selectedModel = normalizeModelSelection(settingsRepository.getSelectedModel())
        }
    }

    @Composable
    private fun SetupWizardScreen() {
        val containerColor = colorResource(id = R.color.bg_session_list)
        val cardColor = colorResource(id = R.color.surface_card)
        val accent = colorResource(id = R.color.primary)
        val accentSoft = colorResource(id = R.color.nezumi_primary_container)
        val textPrimary = colorResource(id = R.color.text_primary)
        val textSecondary = colorResource(id = R.color.text_secondary)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(containerColor)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = "セットアップ",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                }
                item {
                    StepHeader(
                        accent = accent,
                        accentSoft = accentSoft,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary
                    )
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (currentStep) {
                                0 -> WelcomeStep(textPrimary, textSecondary)
                                1 -> BackendStep(accent, textPrimary, textSecondary)
                                else -> ModelStep(accent, textPrimary, textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StepHeader(
        accent: androidx.compose.ui.graphics.Color,
        accentSoft: androidx.compose.ui.graphics.Color,
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        val labels = listOf("ウェルカム", "バックエンド", "モデル")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Step ${currentStep + 1} / ${labels.size}",
                color = textSecondary,
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { index, label ->
                    val active = index == currentStep
                    val completed = index < currentStep
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                active -> accent
                                completed -> accentSoft
                                else -> accentSoft.copy(alpha = 0.45f)
                            }
                        ),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (active) colorResource(id = R.color.nezumi_on_primary) else textPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun WelcomeStep(
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_app_icon_96),
                contentDescription = getString(R.string.app_name),
                modifier = Modifier.size(84.dp)
            )
        }
        Text(
            text = "Nezumi AI へようこそ",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = "最初に 3 ステップだけ設定すると、すぐチャットを始められます。",
            color = textSecondary
        )
        Text(
            text = "1. 実行バックエンドを選ぶ\n2. 使いたいモデルを選ぶ\n3. 必要ならダウンロードして開始",
            color = textPrimary
        )
        Text(
            text = "モデルのダウンロードはあとからでも大丈夫です。",
            color = textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "チャット履歴は最大30件まで保存されます。古いものから自動的に削除されます。設定から変更できます。",
            color = textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(
            onClick = { 
                findNavController().navigate(R.id.settingsFragment)
            }
        ) {
            Text(
                text = "設定から変更できます",
                color = colorResource(id = R.color.primary),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = { currentStep = 1 }) {
                Text("はじめる")
            }
        }
    }

    @Composable
    private fun BackendStep(
        accent: androidx.compose.ui.graphics.Color,
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        Text(
            text = "バックエンドを選択",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = "あとから設定画面で変更できます。",
            color = textSecondary
        )

        backendOptions().forEach { option ->
            SelectableCard(
                title = option.label,
                subtitle = option.description,
                selected = selectedBackend == option.value,
                accent = accent,
                onClick = { selectedBackend = option.value }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { currentStep = 0 }) {
                Text("戻る")
            }
            Button(onClick = { currentStep = 2 }) {
                Text("次へ")
            }
        }
    }

    @Composable
    private fun ModelStep(
        accent: androidx.compose.ui.graphics.Color,
        textPrimary: androidx.compose.ui.graphics.Color,
        textSecondary: androidx.compose.ui.graphics.Color
    ) {
        Text(
            text = "モデルを選択",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = "ダウンロード済みモデルならそのまま開始できます。未取得ならここでダウンロードできます。",
            color = textSecondary
        )

        builtinModelOptions().forEach { option ->
            val state = modelStates[option.model] ?: DownloadUiState()
            val selected = selectedModel == option.settingValue
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) accent else textSecondary.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .clickable { selectedModel = option.settingValue },
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.surface_card)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.title,
                                color = textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = option.subtitle,
                                color = textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = when {
                                state.isDownloaded -> "準備OK"
                                state.isDownloading -> "取得中"
                                else -> "未取得"
                            },
                            color = if (state.isDownloaded) accent else textSecondary
                        )
                    }

                    if (state.isDownloading) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = state.progressText.ifBlank { "ダウンロード中..." },
                            color = textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (selected && !state.isDownloaded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { requestNotificationPermissionForDownload(option.model) },
                                enabled = !state.isDownloading
                            ) {
                                Text(if (state.isDownloading) "ダウンロード中" else "ダウンロード")
                            }
                            TextButton(onClick = { selectedModel = null }) {
                                Text("選択解除")
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "スキップすると、あとでモデル設定からダウンロードできます。",
            color = textSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { currentStep = 1 }, enabled = !isCompleting) {
                Text("戻る")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { completeSetup(skipModelSelection = true) },
                    enabled = !isCompleting
                ) {
                    Text("スキップ")
                }
                Button(
                    onClick = { completeSetup(skipModelSelection = false) },
                    enabled = canFinishWithoutSkip() && !isCompleting
                ) {
                    if (isCompleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colorResource(id = R.color.nezumi_on_primary)
                        )
                    } else {
                        Text("チャットへ")
                    }
                }
            }
        }
    }

    @Composable
    private fun SelectableCard(
        title: String,
        subtitle: String,
        selected: Boolean,
        accent: androidx.compose.ui.graphics.Color,
        onClick: () -> Unit
    ) {
        val textPrimary = colorResource(id = R.color.text_primary)
        val textSecondary = colorResource(id = R.color.text_secondary)
        val selectedTextColor = colorResource(id = R.color.nezumi_on_primary)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) accent else textSecondary.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(18.dp)
                )
                .clickable(onClick = onClick),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) accent else colorResource(id = R.color.surface_card)
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) selectedTextColor else textPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) selectedTextColor.copy(alpha = 0.92f) else textSecondary
                )
            }
        }
    }

    private fun requestNotificationPermissionForDownload(model: ModelFileManager.LocalModel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownloadModel = model
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        enqueueModelDownload(model)
    }

    private fun enqueueModelDownload(model: ModelFileManager.LocalModel) {
        val modelName = when (model) {
            ModelFileManager.LocalModel.GEMMA4_2B -> "Gemma 4 2B"
            ModelFileManager.LocalModel.GEMMA4_4B -> "Gemma 4 4B"
            else -> "モデル"
        }
        
        // システムメモリ情報を取得
        val systemMemInfo = com.nezumi_ai.data.inference.MemoryObserver.getSystemMemoryInfo(requireContext())
        
        // アプリメモリ情報を取得
        val runtime = Runtime.getRuntime()
        val appUsedMemory = runtime.totalMemory() - runtime.freeMemory()
        val appMaxMemory = runtime.maxMemory()
        val appUsedMB = appUsedMemory / (1024 * 1024)
        val appMaxMB = appMaxMemory / (1024 * 1024)
        val appUsedPercent = if (appMaxMemory > 0) {
            ((appUsedMemory * 100) / appMaxMemory).toInt()
        } else {
            0
        }
        
        val alertDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("⚠️ メモリ警告")
            .setMessage(
                "モデル「$modelName」のダウンロードは高メモリ使用率になる可能性があります。\n\n" +
                "━━━ デバイスメモリ ━━━\n" +
                "スマホ本体: ${systemMemInfo.usedMemoryMB}MB / ${systemMemInfo.totalMemoryMB}MB\n" +
                "使用率: ${systemMemInfo.usedPercent}%\n" +
                "${if (systemMemInfo.lowMemoryFlag) "⚠️ デバイスがメモリ不足状態です" else "✓ 正常"}\n\n" +
                "━━━ アプリメモリ ━━━\n" +
                "現在: ${appUsedMB}MB / ${appMaxMB}MB (${appUsedPercent}%)\n\n" +
                "ダウンロードを続行しますか？"
            )
            .setPositiveButton("ダウンロード") { _, _ ->
                val enqueued = ModelDownloadWorker.enqueue(requireContext(), model)
                if (!enqueued) {
                    toast("すでにダウンロード中です")
                }
            }
            .setNegativeButton("キャンセル", null)
            .setCancelable(false)
            .create()
        alertDialog.show()
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

    private fun refreshModelStatus() {
        builtinModelOptions().forEach { option ->
            val state = modelStates[option.model] ?: return@forEach
            state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), option.model)
            state.isDownloading = false
            state.progress = 0f
            state.progressText = ""
        }
    }

    private fun renderDownloadState(model: ModelFileManager.LocalModel, workInfo: WorkInfo?) {
        val state = modelStates[model] ?: return
        if (workInfo == null) {
            state.isDownloading = false
            state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), model)
            state.progress = 0f
            state.progressText = ""
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
                } else {
                    state.progress = 0f
                    state.progressText = "準備中..."
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                state.isDownloading = false
                state.isDownloaded = true
                state.progress = 1f
                state.progressText = "ダウンロード完了"
            }

            WorkInfo.State.FAILED -> {
                state.isDownloading = false
                state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), model)
                state.progress = 0f
                val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                state.progressText = if (error.isNullOrBlank()) "ダウンロード失敗" else error
                toast("ダウンロード失敗: ${state.progressText}")
            }

            WorkInfo.State.CANCELLED -> {
                state.isDownloading = false
                state.isDownloaded = ModelFileManager.isDownloaded(requireContext(), model)
                state.progress = 0f
                state.progressText = ""
            }
        }
    }

    private fun canFinishWithoutSkip(): Boolean {
        val selected = selectedModel ?: return false
        val option = builtinModelOptions().firstOrNull { it.settingValue == selected } ?: return false
        return modelStates[option.model]?.isDownloaded == true
    }

    private fun completeSetup(skipModelSelection: Boolean) {
        if (!skipModelSelection && !canFinishWithoutSkip()) {
            toast("ダウンロード済みモデルを選ぶか、スキップしてください")
            return
        }

        isCompleting = true
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    settingsRepository.updateBackend(selectedBackend)
                    val modelToApply = if (skipModelSelection) {
                        selectedModel?.takeIf { model ->
                            builtinModelOptions().firstOrNull { it.settingValue == model }?.let { option ->
                                modelStates[option.model]?.isDownloaded == true
                            } == true
                        }
                    } else {
                        selectedModel
                    }
                    if (!modelToApply.isNullOrBlank()) {
                        settingsRepository.updateModel(modelToApply)
                    }

                    PreferencesHelper.markInitialSetupCompleted(requireContext())
                    val sessionId = sessionRepository.createSession("新しいチャット")
                    if (!modelToApply.isNullOrBlank()) {
                        sessionRepository.updateSessionModel(sessionId, modelToApply)
                    }
                    sessionId
                }
            }.onSuccess { sessionId ->
                navigateToChat(sessionId)
            }.onFailure {
                isCompleting = false
                toast("セットアップ完了処理に失敗しました: ${it.message}")
            }
        }
    }

    private fun navigateToChat(sessionId: Long) {
        findNavController().navigate(
            R.id.chatFragment,
            Bundle().apply { putLong("sessionId", sessionId) },
            navOptions {
                popUpTo(R.id.setupWizardFragment) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        )
    }

    private fun openLatestOrCreateSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    sessionRepository.getLatestSession()?.id
                        ?: sessionRepository.createSession("新しいチャット")
                }
            }.onSuccess { sessionId ->
                navigateToChat(sessionId)
            }.onFailure {
                toast("チャットの復元に失敗しました: ${it.message}")
            }
        }
    }

    private fun normalizeModelSelection(model: String): String? {
        return builtinModelOptions().firstOrNull { it.settingValue.equals(model, ignoreCase = true) }?.settingValue
    }

    private fun backendOptions(): List<BackendOption> {
        return listOf(
            BackendOption("NPU", "NPU", "使える端末では最優先。省電力寄りですが、端末依存があります。"),
            BackendOption("GPU", "GPU", "高速寄り。安定して使いやすい選択です。"),
            BackendOption("CPU", "CPU", "互換性重視。まず確実に試したいとき向けです。")
        )
    }

    private fun builtinModelOptions(): List<ModelOption> {
        return listOf(
            ModelOption(
                model = ModelFileManager.LocalModel.GEMMA4_2B,
                settingValue = "Gemma4-2B",
                title = "Gemma 4 2B",
                subtitle = "Gemma 4 系の軽量モデル"
            ),
            ModelOption(
                model = ModelFileManager.LocalModel.GEMMA4_4B,
                settingValue = "Gemma4-4B",
                title = "Gemma 4 4B",
                subtitle = "より高品質な Gemma 4 モデル"
            )
        )
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private data class BackendOption(
        val value: String,
        val label: String,
        val description: String
    )

    private data class ModelOption(
        val model: ModelFileManager.LocalModel,
        val settingValue: String,
        val title: String,
        val subtitle: String
    )

    private class DownloadUiState(
        isDownloaded: Boolean = false
    ) {
        var isDownloaded by mutableStateOf(isDownloaded)
        var isDownloading by mutableStateOf(false)
        var progress by mutableFloatStateOf(0f)
        var progressText by mutableStateOf("")
    }
}
