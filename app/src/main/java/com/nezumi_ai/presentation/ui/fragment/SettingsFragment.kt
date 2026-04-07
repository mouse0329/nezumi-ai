package com.nezumi_ai.presentation.ui.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.HfAuthManager
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.HfOAuthManager
import com.nezumi_ai.data.inference.ProjectConfig
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.UUID

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var authService: AuthorizationService? = null
    private val notifiedDownloadResults = mutableSetOf<String>()
    private val lastProgressByModel = mutableMapOf<ModelFileManager.LocalModel, DownloadProgressSnapshot>()
    private val activeWorkIdByModel = mutableMapOf<ModelFileManager.LocalModel, UUID>()
    private val activeRunAttemptByModel = mutableMapOf<ModelFileManager.LocalModel, Int>()
    private val cancelRequestedByModel = mutableMapOf<ModelFileManager.LocalModel, Boolean>()
    private val observedDownloadModels = mutableSetOf<ModelFileManager.LocalModel>()
    private var pendingDownloadPermissionModel: ModelFileManager.LocalModel? = null
    private val verifyPollingJobByModel = mutableMapOf<ModelFileManager.LocalModel, Job>()
    private lateinit var settingsRepository: SettingsRepository

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (data == null) {
                Toast.makeText(requireContext(), "OAuthがキャンセルされました", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val authResponse = AuthorizationResponse.fromIntent(data)
            val authError = AuthorizationException.fromIntent(data)
            if (authError != null) {
                Toast.makeText(requireContext(), "OAuth失敗: ${authError.errorDescription}", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            if (authResponse == null) {
                Toast.makeText(requireContext(), "OAuthレスポンスが取得できませんでした", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            exchangeToken(authResponse)
        }

    private val importTaskLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            binding.importTaskButton.isEnabled = false
            binding.importTaskProgressContainer.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ModelFileManager.importTaskFromUri(requireContext(), uri)
                }
                binding.importTaskButton.isEnabled = true
                binding.importTaskProgressContainer.visibility = View.GONE
                result.onSuccess {
                    val fileType = if (it.name.lowercase().endsWith(".litertlm")) ".litertlm" else ".task"
                    Toast.makeText(requireContext(), "$fileType を追加しました: ${it.name}", Toast.LENGTH_SHORT).show()
                    renderImportedTasks()
                }.onFailure {
                    Toast.makeText(requireContext(), "追加失敗: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val model = pendingDownloadPermissionModel
            pendingDownloadPermissionModel = null
            if (model == null) return@registerForActivityResult

            if (!isAdded) return@registerForActivityResult
            if (!granted) {
                Toast.makeText(
                    requireContext(),
                    "通知が許可されていないため、完了通知は表示されません",
                    Toast.LENGTH_SHORT
                ).show()
            }
            runModelAction(model, true)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyStatusBarInset()
        authService = AuthorizationService(requireContext())
        val database = NezumiAiDatabase.getInstance(requireContext())
        settingsRepository = SettingsRepository(database.settingsDao(), database.chatSessionDao())

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        renderHfTokenState()
        binding.saveHfTokenButton.setOnClickListener {
            if (HfAuthManager.getToken(requireContext()).isNotBlank()) {
                Toast.makeText(requireContext(), "ログアウトするまで書き換えできません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            HfAuthManager.setToken(requireContext(), binding.hfTokenInput.text.toString())
            Toast.makeText(requireContext(), "HFトークンを保存しました", Toast.LENGTH_SHORT).show()
            renderHfTokenState()
        }
        binding.hfOauthLoginButton.setOnClickListener {
            if (HfAuthManager.getToken(requireContext()).isNotBlank()) {
                Toast.makeText(requireContext(), "ログアウトするまで書き換えできません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startOAuthLogin()
        }
        binding.hfOauthLogoutButton.setOnClickListener {
            HfAuthManager.clearToken(requireContext())
            renderHfTokenState()
            Toast.makeText(requireContext(), "ログアウトしました", Toast.LENGTH_SHORT).show()
        }

        // TODO: Add Gemma3n UI elements to fragment_settings.xml with IDs:
        // - download_gemma3n_2b_button, delete_gemma3n_2b_button, gemma3n_2b_access_button, gemma3n_2b_status, gemma3n_2b_download_progress, gemma3n_2b_download_text
        // - download_gemma3n_4b_button, delete_gemma3n_4b_button, gemma3n_4b_access_button, gemma3n_4b_status, gemma3n_4b_download_progress, gemma3n_4b_download_text
        // For now, Gemma3n models fall back to Gemma4_2B UI if download is initiated programmatically

        // Gemma 4 2B
        binding.downloadGemma42bButton.setOnClickListener {
            val model = ModelFileManager.LocalModel.GEMMA4_2B
            if (binding.downloadGemma42bButton.tag?.toString()?.contains("cancel_mode") == true) {
                showCancelInProgress(model)
                ModelDownloadWorker.cancel(requireContext(), model)
            } else {
                requestNotificationPermissionForDownload(model)
            }
        }
        binding.gemma42bAccessButton.setOnClickListener {
            openHfModelAccessPage(ModelFileManager.LocalModel.GEMMA4_2B)
        }
        binding.deleteGemma42bButton.setOnClickListener {
            runModelAction(ModelFileManager.LocalModel.GEMMA4_2B, false)
        }

        // Gemma 4 4B
        binding.downloadGemma44bButton.setOnClickListener {
            val model = ModelFileManager.LocalModel.GEMMA4_4B
            if (binding.downloadGemma44bButton.tag?.toString()?.contains("cancel_mode") == true) {
                showCancelInProgress(model)
                ModelDownloadWorker.cancel(requireContext(), model)
            } else {
                requestNotificationPermissionForDownload(model)
            }
        }
        binding.gemma44bAccessButton.setOnClickListener {
            openHfModelAccessPage(ModelFileManager.LocalModel.GEMMA4_4B)
        }
        binding.deleteGemma44bButton.setOnClickListener {
            runModelAction(ModelFileManager.LocalModel.GEMMA4_4B, false)
        }

        binding.importTaskButton.setOnClickListener {
            importTaskLauncher.launch(arrayOf("*/*"))
        }
        binding.licensePageButton.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_licenseFragment)
        }
        binding.saveInferenceButton.setOnClickListener {
            saveInferenceSettings()
        }
        binding.contextCompressionSwitch.setOnCheckedChangeListener { _, enabled ->
            setCompressionThresholdEnabled(enabled)
        }
        binding.contextCompressionThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = thresholdFromSeekProgress(progress)
                binding.contextCompressionThresholdValue.text =
                    getString(R.string.context_compression_threshold_format, threshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        observeDownloadWork(ModelFileManager.LocalModel.GEMMA3N_2B)
        observeDownloadWork(ModelFileManager.LocalModel.GEMMA3N_4B)
        observeDownloadWork(ModelFileManager.LocalModel.GEMMA4_2B)
        observeDownloadWork(ModelFileManager.LocalModel.GEMMA4_4B)
        refreshStatus()
        renderImportedTasks()
        loadInferenceSettings()
    }

    private fun loadInferenceSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = settingsRepository.getInferenceConfig()
            val systemPrompt = settingsRepository.getSystemPrompt()
            val userName = settingsRepository.getUserName()
            val selectedModel = settingsRepository.getSelectedModel()
            val contextWindow = settingsRepository.getContextWindowForModel(selectedModel)
            
            // コンテキストウィンドウ表示（モデル別最大値を表示）
            val maxWindow = when {
                selectedModel.equals("Gemma4-2B", ignoreCase = true) || selectedModel.equals("Gemma4-4B", ignoreCase = true) -> 8192
                else -> 4096
            }
            binding.contextWindowInput.setText(contextWindow.toString())
            binding.contextWindowInfo.text = getString(R.string.context_window_hint) + " (最大: $maxWindow)"
            
            binding.contextCompressionSwitch.isChecked = config.contextCompressionEnabled
            binding.contextCompressionThresholdSeek.progress =
                seekProgressFromThreshold(config.contextCompressionThresholdPercent)
            binding.contextCompressionThresholdValue.text = getString(
                R.string.context_compression_threshold_format,
                config.contextCompressionThresholdPercent
            )
            setCompressionThresholdEnabled(config.contextCompressionEnabled)
            binding.temperatureInput.setText(String.format(Locale.US, "%.2f", config.temperature))
            binding.topkInput.setText(config.maxTopK.toString())
            binding.maxTokensInput.setText(config.maxTokens.toString())
            binding.userNameInput.setText(userName)
            binding.systemPromptInput.setText(systemPrompt)
            when (config.backendType.uppercase()) {
                "GPU" -> binding.backendToggleGroup.check(binding.backendGpuButton.id)
                "NPU" -> binding.backendToggleGroup.check(binding.backendNpuButton.id)
                else -> {
                    binding.backendToggleGroup.check(binding.backendCpuButton.id)
                }
            }
        }
    }

    private fun selectedBackendType(): String {
        return when (binding.backendToggleGroup.checkedButtonId) {
            binding.backendGpuButton.id -> "GPU"
            binding.backendNpuButton.id -> "NPU"
            else -> "CPU"
        }
    }

    private fun saveInferenceSettings() {
        val contextCompressionEnabled = binding.contextCompressionSwitch.isChecked
        val contextCompressionThresholdPercent =
            thresholdFromSeekProgress(binding.contextCompressionThresholdSeek.progress)
        val temperature = binding.temperatureInput.text.toString().toFloatOrNull()
        val topK = binding.topkInput.text.toString().toIntOrNull()
        val maxTokens = binding.maxTokensInput.text.toString().toIntOrNull()
        val contextWindow = binding.contextWindowInput.text.toString().toIntOrNull()
        val backendType = selectedBackendType()
        if (temperature == null || topK == null || maxTokens == null || contextWindow == null) {
            Toast.makeText(requireContext(), "推論設定の入力値が不正です", Toast.LENGTH_SHORT).show()
            return
        }
        val systemPrompt = binding.systemPromptInput.text.toString().trim()
        val userName = binding.userNameInput.text.toString().trim()
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
            settingsRepository.updateSystemPrompt(systemPrompt)
            settingsRepository.updateUserName(userName)
            loadInferenceSettings()
            Toast.makeText(requireContext(), "推論設定を保存しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderHfTokenState()
        // LiveData 更新を取りこぼした場合の保険（特に「検証中…」→SUCCEEDED 遷移）
        refreshWorkInfoOnce(ModelFileManager.LocalModel.GEMMA4_2B)
        refreshWorkInfoOnce(ModelFileManager.LocalModel.GEMMA4_4B)
        refreshStatus()
        renderImportedTasks()
    }

    private fun renderHfTokenState() {
        val token = HfAuthManager.getToken(requireContext())
        val linked = token.isNotBlank()
        binding.hfAuthStatus.text =
            if (linked) getString(R.string.hf_auth_linked) else getString(R.string.hf_auth_not_linked)
        binding.hfTokenInput.setText(if (linked) maskToken(token) else "")
        binding.hfTokenInput.isEnabled = !linked
        binding.saveHfTokenButton.isEnabled = !linked
        binding.hfOauthLoginButton.isEnabled = !linked
        binding.hfOauthLogoutButton.isEnabled = linked
    }

    private fun maskToken(token: String): String {
        if (token.length <= 6) return "******"
        val head = token.take(3)
        val tail = token.takeLast(4)
        return "$head${"*".repeat((token.length - 7).coerceAtLeast(6))}$tail"
    }

    private fun thresholdFromSeekProgress(progress: Int): Int = 50 + progress

    private fun seekProgressFromThreshold(threshold: Int): Int =
        (threshold.coerceIn(50, 95) - 50)

    private fun setCompressionThresholdEnabled(enabled: Boolean) {
        binding.contextCompressionThresholdSeek.isEnabled = enabled
        binding.contextCompressionThresholdValue.alpha = if (enabled) 1f else 0.5f
    }

    private fun renderImportedTasks() {
        val imported = ModelFileManager.listImportedTaskModels(requireContext())
        binding.importedTaskListContainer.removeAllViews()
        binding.importedTaskEmptyText.visibility = if (imported.isEmpty()) View.VISIBLE else View.GONE

        imported.forEachIndexed { index, model ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val nameView = TextView(requireContext()).apply {
                text = model.name
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val deleteButton = Button(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.delete)
                textSize = 11f
                setOnClickListener {
                    val result = ModelFileManager.deleteImportedTask(requireContext(), model.path)
                    result.onSuccess {
                        Toast.makeText(requireContext(), ".task を削除しました", Toast.LENGTH_SHORT).show()
                        renderImportedTasks()
                    }.onFailure {
                        Toast.makeText(requireContext(), "削除失敗: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            row.addView(nameView)
            row.addView(deleteButton)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                val marginPx = (6 * resources.displayMetrics.density).toInt()
                params.topMargin = marginPx
            }
            binding.importedTaskListContainer.addView(row, params)
        }
    }

    private fun runModelAction(model: ModelFileManager.LocalModel, download: Boolean) {
        if (download) {
            if (ModelFileManager.isDownloaded(requireContext(), model)) {
                refreshStatus()
                Toast.makeText(requireContext(), "すでにダウンロード済みです", Toast.LENGTH_SHORT).show()
                return
            }
            setAccessButtonVisible(model, false)
            setModelButtonsEnabled(model, false)
            showProgress(model, true)
            setStatus(model, "ダウンロードキュー投入中...")
            viewLifecycleOwner.lifecycleScope.launch {
                val enqueued = withContext(Dispatchers.IO) {
                    ModelDownloadWorker.enqueue(requireContext(), model)
                }
                if (!isAdded) return@launch
                if (enqueued) {
                    setStatus(model, "ダウンロード待機中")
                    // 進捗監視を開始
                    observeDownloadWork(model)
                    Toast.makeText(requireContext(), "バックグラウンドでダウンロードを開始しました", Toast.LENGTH_SHORT).show()
                } else {
                    setStatus(model, "既にダウンロード処理が実行中です")
                    // 既存キューの状態を再描画して、UIを実態に合わせる
                    refreshWorkInfoOnce(model)
                }
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ModelDownloadWorker.cancel(requireContext(), model)
            val ok = withContext(Dispatchers.IO) {
                ModelFileManager.deleteModel(requireContext(), model)
            }
            Toast.makeText(
                requireContext(),
                if (ok) "削除しました" else "削除に失敗しました",
                Toast.LENGTH_SHORT
            ).show()
            showProgress(model, false)
            setModelButtonsEnabled(model, true)
            refreshStatus()
        }
    }

    private fun requestNotificationPermissionForDownload(model: ModelFileManager.LocalModel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runModelAction(model, true)
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runModelAction(model, true)
            return
        }

        pendingDownloadPermissionModel = model
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshStatus() {
        listOf(
            ModelFileManager.LocalModel.GEMMA4_2B,
            ModelFileManager.LocalModel.GEMMA4_4B
        ).forEach { model ->
            val file = ModelFileManager.modelFile(requireContext(), model)
            val downloaded = ModelFileManager.isDownloaded(requireContext(), model)
            setStatus(model, statusText(downloaded, file.length()))
        }
    }

    private fun statusText(downloaded: Boolean, sizeBytes: Long): String {
        return if (downloaded) {
            val sizeMb = sizeBytes / (1024.0 * 1024.0)
            "ダウンロード済み (${String.format("%.1f", sizeMb)} MB)"
        } else {
            "未ダウンロード"
        }
    }

    private fun setModelButtonsEnabled(model: ModelFileManager.LocalModel, enabled: Boolean) {
        when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> {
                binding.downloadGemma42bButton.isEnabled = enabled
                binding.deleteGemma42bButton.isEnabled = enabled
            }
            ModelFileManager.LocalModel.GEMMA4_2B -> {
                binding.downloadGemma42bButton.isEnabled = enabled
                binding.deleteGemma42bButton.isEnabled = enabled
            }
            ModelFileManager.LocalModel.GEMMA4_4B -> {
                binding.downloadGemma44bButton.isEnabled = enabled
                binding.deleteGemma44bButton.isEnabled = enabled
            }
        }
    }

    private fun setCancelMode(model: ModelFileManager.LocalModel, isCancelMode: Boolean) {
        val button = when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.downloadGemma42bButton
            ModelFileManager.LocalModel.GEMMA4_2B -> binding.downloadGemma42bButton
            ModelFileManager.LocalModel.GEMMA4_4B -> binding.downloadGemma44bButton
        }
        button.isEnabled = true
        if (isCancelMode) {
            button.text = "キャンセル"
            button.tag = "cancel_mode_${model.name}"
        } else {
            button.text = "ダウンロード"
            button.tag = "download_mode_${model.name}"
        }
    }

    private fun showProgress(model: ModelFileManager.LocalModel, visible: Boolean) {
        val (progressBar, text) = when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText
            ModelFileManager.LocalModel.GEMMA4_2B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText
            ModelFileManager.LocalModel.GEMMA4_4B -> binding.gemma44bDownloadProgress to binding.gemma44bDownloadText
        }
        progressBar.visibility = if (visible) View.VISIBLE else View.GONE
        text.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            text.text = ""
        }
    }

    private fun updateProgress(model: ModelFileManager.LocalModel, downloaded: Long, total: Long, speedMbps: Double = 0.0, remainingSec: Double = 0.0) {
        val (progressBar, text) = when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText  // Fallback to Gemma4_2B UI for now
            ModelFileManager.LocalModel.GEMMA4_2B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText
            ModelFileManager.LocalModel.GEMMA4_4B -> binding.gemma44bDownloadProgress to binding.gemma44bDownloadText
        }

        if (total > 0) {
            progressBar.isIndeterminate = false
            progressBar.max = 1000
            progressBar.progress = ((downloaded * 1000L) / total).toInt()
            
            val speedStr = String.format(Locale.US, "%.1f MB/s", speedMbps)
            val remainingStr = if (remainingSec > 0) {
                String.format(Locale.US, "残り %d 秒", remainingSec.toLong())
            } else {
                ""
            }
            val details = listOfNotNull(
                "${formatGb(downloaded)} / ${formatGb(total)}",
                if (speedMbps > 0) speedStr else null,
                if (remainingSec > 0) remainingStr else null
            ).joinToString(" | ")
            
            text.text = details
        } else {
            progressBar.isIndeterminate = true
            text.text = "${formatGb(downloaded)} / ? GB"
        }
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private fun observeDownloadWork(model: ModelFileManager.LocalModel) {
        if (!observedDownloadModels.add(model)) return
        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.modelWorkName(model))
            .observe(viewLifecycleOwner) { infos ->
                renderDownloadState(model, pickLatestRelevantWorkInfo(infos))
            }
    }

    private fun pickLatestRelevantWorkInfo(infos: List<WorkInfo>): WorkInfo? {
        if (infos.isEmpty()) return null

        fun isActive(state: WorkInfo.State): Boolean {
            return state == WorkInfo.State.RUNNING ||
                state == WorkInfo.State.ENQUEUED ||
                state == WorkInfo.State.BLOCKED
        }

        fun statePriority(state: WorkInfo.State): Int {
            return when (state) {
                WorkInfo.State.RUNNING -> 5
                WorkInfo.State.ENQUEUED -> 4
                WorkInfo.State.BLOCKED -> 3
                WorkInfo.State.SUCCEEDED -> 2
                WorkInfo.State.FAILED -> 1
                WorkInfo.State.CANCELLED -> 0
            }
        }

        fun progressedBytes(info: WorkInfo): Long {
            val progressBytes = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, -1L)
            if (progressBytes >= 0L) return progressBytes
            return info.outputData.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, -1L)
        }

        val active = infos
            .asSequence()
            .filter { isActive(it.state) }
            .maxWithOrNull(
                compareBy<WorkInfo>(
                    { statePriority(it.state) },
                    { progressedBytes(it) },
                    { it.runAttemptCount }
                ).thenBy { it.id.toString() }
            )
        if (active != null) return active

        return infos.maxWithOrNull(
            compareBy<WorkInfo>(
                { statePriority(it.state) },
                { progressedBytes(it) },
                { it.runAttemptCount }
            ).thenBy { it.id.toString() }
        )
    }

    private fun renderDownloadState(model: ModelFileManager.LocalModel, workInfo: WorkInfo?) {
        if (workInfo == null) {
            stopVerifyPolling(model)
            clearCancelInProgress(model)
            clearProgressTracking(model)
            setAccessButtonVisible(model, false)
            setModelButtonsEnabled(model, true)
            showProgress(model, false)
            refreshStatus()
            return
        }

        when (workInfo.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED -> {
                val cancelInProgress = cancelRequestedByModel[model] == true
                setAccessButtonVisible(model, false)
                if (cancelInProgress) {
                    stopVerifyPolling(model)
                    setCancelMode(model, false)
                    setModelButtonsEnabled(model, false)
                    setStatus(model, "キャンセル処理中...")
                    showProgress(model, true)
                    val (progressBar, text) = when (model) {
                        ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText  // Fallback to Gemma4_2B UI for now
                        ModelFileManager.LocalModel.GEMMA4_2B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText
                        ModelFileManager.LocalModel.GEMMA4_4B -> binding.gemma44bDownloadProgress to binding.gemma44bDownloadText
                    }
                    progressBar.isIndeterminate = true
                    text.text = "中断中..."
                    return
                } else {
                    setModelButtonsEnabled(model, false)
                    setCancelMode(model, true)
                }
                showProgress(model, true)
                val downloaded = workInfo.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, -1L)
                val total = workInfo.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, -1L)
                val speedMbps = workInfo.progress.getDouble(ModelDownloadWorker.KEY_SPEED_MBPS, 0.0)
                val remainingSec = workInfo.progress.getDouble(ModelDownloadWorker.KEY_ESTIMATED_REMAINING_SEC, 0.0)
                
                if (downloaded >= 0L) {
                    val monotonic = monotonicProgress(
                        model = model,
                        workId = workInfo.id,
                        runAttemptCount = workInfo.runAttemptCount,
                        downloaded = downloaded,
                        total = total
                    )
                    updateProgress(model, monotonic.downloadedBytes, monotonic.totalBytes, speedMbps, remainingSec)
                    if (monotonic.totalBytes > 0L) {
                        val percent = ((monotonic.downloadedBytes * 100L) / monotonic.totalBytes).toInt()
                        if (monotonic.downloadedBytes >= monotonic.totalBytes) {
                            setStatus(model, "ダウンロード完了後の検証中...")
                            // 検証フェーズ中は進捗更新が止まりやすいので、短時間だけ状態をポーリングしてUIを追従させる
                            startVerifyPolling(model)
                            val text = when (model) {
                                ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.gemma42bDownloadText  // Fallback to Gemma4_2B UI
                                ModelFileManager.LocalModel.GEMMA4_2B -> binding.gemma42bDownloadText
                                ModelFileManager.LocalModel.GEMMA4_4B -> binding.gemma44bDownloadText
                            }
                            text.text = "${formatGb(monotonic.totalBytes)} / ${formatGb(monotonic.totalBytes)} | 検証中..."
                        } else {
                            stopVerifyPolling(model)
                            setStatus(model, "ダウンロード中 ${percent}%")
                        }
                    } else {
                        stopVerifyPolling(model)
                        setStatus(model, "ダウンロード中")
                    }
                } else {
                    stopVerifyPolling(model)
                    setStatus(model, "ダウンロード待機中")
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                stopVerifyPolling(model)
                clearCancelInProgress(model)
                clearProgressTracking(model)
                setAccessButtonVisible(model, false)
                setModelButtonsEnabled(model, true)
                setCancelMode(model, false)
                showProgress(model, false)
                refreshStatus()
                notifyOnce(model, workInfo, "ダウンロード完了")
            }
            WorkInfo.State.FAILED -> {
                stopVerifyPolling(model)
                clearCancelInProgress(model)
                clearProgressTracking(model)
                setModelButtonsEnabled(model, true)
                setCancelMode(model, false)
                showProgress(model, false)
                refreshStatus()
                val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                    ?: "ダウンロードに失敗しました"
                setStatus(model, "失敗: $error")
                setAccessButtonVisible(model, shouldOpenHfAccessPage(error))
                notifyOnce(model, workInfo, error)
            }
            WorkInfo.State.CANCELLED -> {
                stopVerifyPolling(model)
                clearCancelInProgress(model)
                clearProgressTracking(model)
                setAccessButtonVisible(model, false)
                setModelButtonsEnabled(model, true)
                setCancelMode(model, false)
                showProgress(model, false)
                refreshStatus()
                if (!ModelFileManager.isDownloaded(requireContext(), model)) {
                    setStatus(model, "未ダウンロード")
                }
            }
        }
    }

    private fun refreshWorkInfoOnce(model: ModelFileManager.LocalModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val infos = withContext(Dispatchers.IO) {
                try {
                    WorkManager.getInstance(requireContext())
                        .getWorkInfosForUniqueWork(ModelDownloadWorker.modelWorkName(model))
                        .get(2, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    null
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            renderDownloadState(model, pickLatestRelevantWorkInfo(infos))
        }
    }

    private fun startVerifyPolling(model: ModelFileManager.LocalModel) {
        if (verifyPollingJobByModel[model]?.isActive == true) return
        verifyPollingJobByModel[model] = viewLifecycleOwner.lifecycleScope.launch {
            repeat(12) { // 最大 ~12秒（長引く場合はLiveDataに任せる）
                refreshWorkInfoOnce(model)
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun stopVerifyPolling(model: ModelFileManager.LocalModel) {
        verifyPollingJobByModel.remove(model)?.cancel()
    }

    private fun showCancelInProgress(model: ModelFileManager.LocalModel) {
        cancelRequestedByModel[model] = true
        setCancelMode(model, false)
        setModelButtonsEnabled(model, false)
        showProgress(model, true)
        setStatus(model, "キャンセル処理中...")
        val (progressBar, text) = when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText
            ModelFileManager.LocalModel.GEMMA4_2B -> binding.gemma42bDownloadProgress to binding.gemma42bDownloadText
            ModelFileManager.LocalModel.GEMMA4_4B -> binding.gemma44bDownloadProgress to binding.gemma44bDownloadText
        }
        progressBar.isIndeterminate = true
        text.text = "中断中..."
    }

    private fun clearCancelInProgress(model: ModelFileManager.LocalModel) {
        cancelRequestedByModel.remove(model)
    }

    private fun monotonicProgress(
        model: ModelFileManager.LocalModel,
        workId: UUID,
        runAttemptCount: Int,
        downloaded: Long,
        total: Long
    ): DownloadProgressSnapshot {
        val previousWorkId = activeWorkIdByModel[model]
        val previousRunAttempt = activeRunAttemptByModel[model]
        // WorkManager の retry は同一 workId のまま runAttemptCount が増える。
        // この場合は再試行が始まっているので、前回の100%を引き継がず進捗をリセットする。
        if (previousWorkId != workId || previousRunAttempt != runAttemptCount) {
            activeWorkIdByModel[model] = workId
            activeRunAttemptByModel[model] = runAttemptCount
            lastProgressByModel.remove(model)
        }

        val previous = lastProgressByModel[model]
        val normalizedTotal = if (total > 0L) total else (previous?.totalBytes ?: total)
        val candidateDownloaded = when {
            previous == null -> downloaded
            normalizedTotal > 0L && previous.totalBytes == normalizedTotal ->
                maxOf(previous.downloadedBytes, downloaded)
            normalizedTotal <= 0L && previous.totalBytes <= 0L ->
                maxOf(previous.downloadedBytes, downloaded)
            else -> downloaded
        }
        val normalizedDownloaded = if (normalizedTotal > 0L) {
            candidateDownloaded.coerceIn(0L, normalizedTotal)
        } else {
            candidateDownloaded.coerceAtLeast(0L)
        }

        return DownloadProgressSnapshot(normalizedDownloaded, normalizedTotal).also {
            lastProgressByModel[model] = it
        }
    }

    private fun clearProgressTracking(model: ModelFileManager.LocalModel) {
        lastProgressByModel.remove(model)
        activeWorkIdByModel.remove(model)
        activeRunAttemptByModel.remove(model)
    }

    private fun setStatus(model: ModelFileManager.LocalModel, text: String) {
        when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.gemma42bStatus.text = text  // Fallback to Gemma4_2B UI
            ModelFileManager.LocalModel.GEMMA4_2B -> binding.gemma42bStatus.text = text
            ModelFileManager.LocalModel.GEMMA4_4B -> binding.gemma44bStatus.text = text
        }
    }

    private fun setAccessButtonVisible(model: ModelFileManager.LocalModel, visible: Boolean) {
        when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B, ModelFileManager.LocalModel.GEMMA3N_4B -> binding.gemma42bAccessButton.visibility = if (visible) View.VISIBLE else View.GONE  // Fallback to Gemma4_2B UI
            ModelFileManager.LocalModel.GEMMA4_2B -> binding.gemma42bAccessButton.visibility = if (visible) View.VISIBLE else View.GONE
            ModelFileManager.LocalModel.GEMMA4_4B -> binding.gemma44bAccessButton.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun notifyOnce(model: ModelFileManager.LocalModel, workInfo: WorkInfo, message: String): Boolean {
        val key = "${model.name}:${workInfo.id}:${workInfo.state}"
        if (notifiedDownloadResults.add(key)) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun shouldOpenHfAccessPage(errorMessage: String): Boolean {
        return errorMessage.contains("HTTP 403", ignoreCase = true)
    }

    private fun openHfModelAccessPage(model: ModelFileManager.LocalModel) {
        val url = ModelFileManager.previewTreeUrl(model)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), "ブラウザを起動できませんでした: $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyStatusBarInset() {
        val initialTop = binding.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = initialTop + topInset)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun startOAuthLogin() {
        if (ProjectConfig.HF_CLIENT_ID == "REPLACE_WITH_HF_CLIENT_ID") {
            Toast.makeText(requireContext(), "ProjectConfig.HF_CLIENT_ID を設定してください", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(requireContext(), "トークン取得失敗: $error", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                HfAuthManager.setToken(requireContext(), accessToken)
                renderHfTokenState()
                Toast.makeText(requireContext(), "OAuthログイン成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        authService?.dispose()
        authService = null
        observedDownloadModels.clear()
        verifyPollingJobByModel.values.forEach { it.cancel() }
        verifyPollingJobByModel.clear()
        super.onDestroyView()
        _binding = null
    }

    private data class DownloadProgressSnapshot(
        val downloadedBytes: Long,
        val totalBytes: Long
    )
}
