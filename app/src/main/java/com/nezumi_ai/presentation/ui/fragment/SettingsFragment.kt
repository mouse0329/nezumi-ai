package com.nezumi_ai.presentation.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import java.util.Locale

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var authService: AuthorizationService? = null
    private val notifiedDownloadResults = mutableSetOf<String>()
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
            val result = ModelFileManager.importTaskFromUri(requireContext(), uri)
            result.onSuccess {
                Toast.makeText(requireContext(), ".task を追加しました: ${it.name}", Toast.LENGTH_SHORT).show()
                renderImportedTasks()
            }.onFailure {
                Toast.makeText(requireContext(), "追加失敗: ${it.message}", Toast.LENGTH_LONG).show()
            }
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
        settingsRepository = SettingsRepository(database.settingsDao())

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

        binding.downloadE2bButton.setOnClickListener {
            runModelAction(ModelFileManager.LocalModel.E2B, true)
        }
        binding.e2bAccessButton.setOnClickListener {
            openHfModelAccessPage(ModelFileManager.LocalModel.E2B)
        }
        binding.deleteE2bButton.setOnClickListener {
            runModelAction(ModelFileManager.LocalModel.E2B, false)
        }
        binding.downloadE4bButton.setOnClickListener {
            runModelAction(ModelFileManager.LocalModel.E4B, true)
        }
        binding.e4bAccessButton.setOnClickListener {
            openHfModelAccessPage(ModelFileManager.LocalModel.E4B)
        }
        binding.deleteE4bButton.setOnClickListener {
            runModelAction(ModelFileManager.LocalModel.E4B, false)
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

        observeDownloadWork(ModelFileManager.LocalModel.E2B)
        observeDownloadWork(ModelFileManager.LocalModel.E4B)
        refreshStatus()
        renderImportedTasks()
        loadInferenceSettings()
    }

    private fun loadInferenceSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = settingsRepository.getInferenceConfig()
            binding.contextWindowText.text = getString(R.string.context_window_fixed)
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
            if (config.backendType.uppercase() == "GPU") {
                binding.backendToggleGroup.check(binding.backendGpuButton.id)
            } else {
                binding.backendToggleGroup.check(binding.backendCpuButton.id)
            }
        }
    }

    private fun saveInferenceSettings() {
        val contextCompressionEnabled = binding.contextCompressionSwitch.isChecked
        val contextCompressionThresholdPercent =
            thresholdFromSeekProgress(binding.contextCompressionThresholdSeek.progress)
        val temperature = binding.temperatureInput.text.toString().toFloatOrNull()
        val topK = binding.topkInput.text.toString().toIntOrNull()
        val maxTokens = binding.maxTokensInput.text.toString().toIntOrNull()
        val backendType = when (binding.backendToggleGroup.checkedButtonId) {
            binding.backendGpuButton.id -> "GPU"
            else -> "CPU"
        }
        if (temperature == null || topK == null || maxTokens == null) {
            Toast.makeText(requireContext(), "推論設定の入力値が不正です", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.updateInferenceConfig(
                contextCompressionEnabled = contextCompressionEnabled,
                contextCompressionThresholdPercent = contextCompressionThresholdPercent,
                temperature = temperature,
                maxTopK = topK,
                maxTokens = maxTokens,
                backendType = backendType
            )
            loadInferenceSettings()
            Toast.makeText(requireContext(), "推論設定を保存しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderHfTokenState()
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
            val item = TextView(requireContext()).apply {
                text = "• ${model.name}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                val marginPx = (6 * resources.displayMetrics.density).toInt()
                params.topMargin = marginPx
            }
            binding.importedTaskListContainer.addView(item, params)
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
            setStatus(model, "ダウンロード待機中")
            ModelDownloadWorker.enqueue(requireContext(), model)
            Toast.makeText(requireContext(), "バックグラウンドでダウンロードを開始しました", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ModelDownloadWorker.cancel(requireContext(), model)
            val ok = ModelFileManager.deleteModel(requireContext(), model)
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

    private fun refreshStatus() {
        val e2bFile = ModelFileManager.modelFile(requireContext(), ModelFileManager.LocalModel.E2B)
        val e4bFile = ModelFileManager.modelFile(requireContext(), ModelFileManager.LocalModel.E4B)

        setStatus(ModelFileManager.LocalModel.E2B, statusText(e2bFile.exists(), e2bFile.length()))
        setStatus(ModelFileManager.LocalModel.E4B, statusText(e4bFile.exists(), e4bFile.length()))
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
            ModelFileManager.LocalModel.E2B -> {
                binding.downloadE2bButton.isEnabled = enabled
                binding.deleteE2bButton.isEnabled = enabled
            }
            ModelFileManager.LocalModel.E4B -> {
                binding.downloadE4bButton.isEnabled = enabled
                binding.deleteE4bButton.isEnabled = enabled
            }
        }
    }

    private fun showProgress(model: ModelFileManager.LocalModel, visible: Boolean) {
        val progressBar = when (model) {
            ModelFileManager.LocalModel.E2B -> binding.e2bDownloadProgress
            ModelFileManager.LocalModel.E4B -> binding.e4bDownloadProgress
        }
        val text = when (model) {
            ModelFileManager.LocalModel.E2B -> binding.e2bDownloadText
            ModelFileManager.LocalModel.E4B -> binding.e4bDownloadText
        }
        progressBar.visibility = if (visible) View.VISIBLE else View.GONE
        text.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            text.text = ""
        }
    }

    private fun updateProgress(model: ModelFileManager.LocalModel, downloaded: Long, total: Long) {
        val progressBar = when (model) {
            ModelFileManager.LocalModel.E2B -> binding.e2bDownloadProgress
            ModelFileManager.LocalModel.E4B -> binding.e4bDownloadProgress
        }
        val text = when (model) {
            ModelFileManager.LocalModel.E2B -> binding.e2bDownloadText
            ModelFileManager.LocalModel.E4B -> binding.e4bDownloadText
        }

        if (total > 0) {
            progressBar.isIndeterminate = false
            progressBar.max = 1000
            progressBar.progress = ((downloaded * 1000L) / total).toInt()
            text.text = "${formatGb(downloaded)} / ${formatGb(total)}"
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
        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.modelWorkName(model))
            .observe(viewLifecycleOwner) { infos ->
                renderDownloadState(model, pickLatestRelevantWorkInfo(infos))
            }
    }

    private fun pickLatestRelevantWorkInfo(infos: List<WorkInfo>): WorkInfo? {
        if (infos.isEmpty()) return null
        val active = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        }
        if (active != null) return active
        return infos.lastOrNull()
    }

    private fun renderDownloadState(model: ModelFileManager.LocalModel, workInfo: WorkInfo?) {
        if (workInfo == null) {
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
                setAccessButtonVisible(model, false)
                setModelButtonsEnabled(model, false)
                showProgress(model, true)
                val downloaded = workInfo.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, -1L)
                val total = workInfo.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, -1L)
                if (downloaded >= 0L) {
                    updateProgress(model, downloaded, total)
                    if (total > 0L) {
                        val percent = ((downloaded * 100L) / total).toInt()
                        setStatus(model, "ダウンロード中 ${percent}%")
                    } else {
                        setStatus(model, "ダウンロード中")
                    }
                } else {
                    setStatus(model, "ダウンロード待機中")
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                setAccessButtonVisible(model, false)
                setModelButtonsEnabled(model, true)
                showProgress(model, false)
                refreshStatus()
                notifyOnce(model, workInfo, "ダウンロード完了")
            }
            WorkInfo.State.FAILED -> {
                setModelButtonsEnabled(model, true)
                showProgress(model, false)
                refreshStatus()
                val error = workInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                    ?: "ダウンロードに失敗しました"
                setStatus(model, "失敗: $error")
                setAccessButtonVisible(model, shouldOpenHfAccessPage(error))
                notifyOnce(model, workInfo, error)
            }
            WorkInfo.State.CANCELLED -> {
                setAccessButtonVisible(model, false)
                setModelButtonsEnabled(model, true)
                showProgress(model, false)
                refreshStatus()
                if (!ModelFileManager.isDownloaded(requireContext(), model)) {
                    setStatus(model, "未ダウンロード")
                }
            }
        }
    }

    private fun setStatus(model: ModelFileManager.LocalModel, text: String) {
        when (model) {
            ModelFileManager.LocalModel.E2B -> binding.e2bStatus.text = text
            ModelFileManager.LocalModel.E4B -> binding.e4bStatus.text = text
        }
    }

    private fun setAccessButtonVisible(model: ModelFileManager.LocalModel, visible: Boolean) {
        when (model) {
            ModelFileManager.LocalModel.E2B -> binding.e2bAccessButton.visibility =
                if (visible) View.VISIBLE else View.GONE
            ModelFileManager.LocalModel.E4B -> binding.e4bAccessButton.visibility =
                if (visible) View.VISIBLE else View.GONE
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
        super.onDestroyView()
        _binding = null
    }
}
