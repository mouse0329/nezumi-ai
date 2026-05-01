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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.HfAuthManager
import com.nezumi_ai.data.inference.HfOAuthManager
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ProjectConfig
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.presentation.ui.helper.SettingsHelper
import com.nezumi_ai.utils.ImportedModelCapabilities
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import java.util.Locale

class ModelSettingsFragment : Fragment() {
    private lateinit var settingsRepository: SettingsRepository
    private var authService: AuthorizationService? = null
    private var pendingDownloadPermissionModel: ModelFileManager.LocalModel? = null

    private var hfLinked by mutableStateOf(false)
    private var hfSearchQuery by mutableStateOf("")
    private var hfSearchLoading by mutableStateOf(false)
    private var hfSearchError by mutableStateOf<String?>(null)
    private var hfSearchResults by mutableStateOf<List<ModelFileManager.HfModelSearchResult>>(emptyList())
    private var hfSearchResultsDialogVisible by mutableStateOf(false)
    private var hfFilePickerModel by mutableStateOf<ModelFileManager.HfModelSearchResult?>(null)
    private var hfFilePickerLoading by mutableStateOf(false)
    private var hfFilePickerFiles by mutableStateOf<List<ModelFileManager.HfModelFile>>(emptyList())
    private var hfDownloadingFilePath by mutableStateOf<String?>(null)
    private var hfQueuedDownloads by mutableStateOf<List<HfQueuedDownloadUiState>>(emptyList())
    private var importedTasks by mutableStateOf<List<ModelFileManager.ImportedTaskModel>>(emptyList())
    private var isImportingModel by mutableStateOf(false)
    private var capabilityDialogModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var capabilityDialogImageEnabled by mutableStateOf(false)
    private var capabilityDialogAudioEnabled by mutableStateOf(false)
    private var stopTokensDialogModel by mutableStateOf<ModelFileManager.ImportedTaskModel?>(null)
    private var stopTokensDialogText by mutableStateOf("")
    private var expandedModelKey by mutableStateOf<String?>(null)

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
                ModelScreen()
            }
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderHfTokenState()
        refreshImportedTasks()
        refreshModelStatus()
        observeDownloadWork()
        observeCustomHfDownloadWork()
    }

    override fun onResume() {
        super.onResume()
        renderHfTokenState()
        refreshImportedTasks()
    }

    @Composable
    private fun ModelScreen() {
        if (isImportingModel) {
            ImportingDialog()
        }
        hfFilePickerModel?.let { model ->
            HfFilePickerDialog(model)
        }
        capabilityDialogModel?.let { model ->
            ImportedCapabilityDialog(model)
        }
        stopTokensDialogModel?.let { model ->
            StopTokensDialog(model)
        }
        if (hfSearchResultsDialogVisible) {
            HfSearchResultsContent()
            return
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
                        text = "モデル",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colorResource(id = R.color.text_primary),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "モデル管理",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "組み込みモデルのDL、Hugging Face検索、カスタムモデル追加をここで管理できます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                }
            }
            item { HfCard() }
            item { HfModelSearchCard() }
            item { ModelListCard() }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorResource(id = R.color.primary_light))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ローカルモデル追加",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = ".task / .litertlm / .gguf を追加できます",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { importTaskLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Text(text = stringResource(id = R.string.import_task_model))
                        }
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
                            refreshImportedTasks()
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
    private fun StopTokensDialog(model: ModelFileManager.ImportedTaskModel) {
        Dialog(onDismissRequest = { stopTokensDialogModel = null }) {
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
                        text = "ストップトークン",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = model.name,
                        color = colorResource(id = R.color.text_secondary)
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = stopTokensDialogText,
                        onValueChange = { stopTokensDialogText = it },
                        label = { Text("トークン（カンマ区切り）") },
                        placeholder = { Text("<|im_end|>,<|im_start|>") },
                        minLines = 2
                    )
                    Text(
                        text = "カンマ区切りで複数指定できます。デフォルトのストップトークンに追加されます。",
                        color = colorResource(id = R.color.text_secondary),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = { stopTokensDialogModel = null }) { Text("キャンセル") }
                        Button(onClick = {
                            val tokens = stopTokensDialogText
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    settingsRepository.updateStopTokensForModel(model.path, tokens)
                                }
                                toast("ストップトークンを保存しました")
                                stopTokensDialogModel = null
                            }
                        }) { Text("保存") }
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
    private fun HfModelSearchCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Hugging Face モデル検索", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = hfSearchQuery,
                    onValueChange = { hfSearchQuery = it },
                    label = { Text("キーワード / repo id") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !hfSearchLoading,
                        onClick = { searchHfModels() }
                    ) {
                        Text(if (hfSearchLoading) "検索中..." else "検索")
                    }
                    if (hfSearchResults.isNotEmpty()) {
                        TextButton(
                            enabled = !hfSearchLoading,
                            onClick = { hfSearchResultsDialogVisible = true }
                        ) {
                            Text("結果を見る (${hfSearchResults.size})")
                        }
                        TextButton(
                            enabled = !hfSearchLoading,
                            onClick = {
                                hfSearchResults = emptyList()
                                hfSearchError = null
                                hfSearchResultsDialogVisible = false
                            }
                        ) {
                            Text("クリア")
                        }
                    }
                }
                hfSearchError?.let {
                    Text(text = it, color = colorResource(id = R.color.text_primary))
                }
                if (hfSearchResults.isNotEmpty() && !hfSearchLoading) {
                    Text(
                        text = "検索結果 ${hfSearchResults.size}件（ページで表示）",
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            }
        }
    }

    @Composable
    private fun HfSearchResultsContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { hfSearchResultsDialogVisible = false }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "戻る",
                        tint = colorResource(id = R.color.text_primary)
                    )
                }
                Text(
                    text = "検索結果",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorResource(id = R.color.text_primary),
                    fontWeight = FontWeight.Bold
                )
            }
            if (hfSearchResults.isEmpty()) {
                Text(
                    text = "検索結果がありません",
                    color = colorResource(id = R.color.text_secondary)
                )
            } else {
                Text(
                    text = "${hfSearchResults.size}件の結果",
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hfSearchResults, key = { it.id }) { result ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = colorResource(id = R.color.primary_light)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(text = result.id, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "DL: ${result.downloads} / Likes: ${result.likes}",
                                    color = colorResource(id = R.color.text_secondary)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !hfFilePickerLoading,
                                        onClick = {
                                            openHfFilePicker(result)
                                        }
                                    ) {
                                        Text("ファイル選択")
                                    }
                                    TextButton(onClick = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://huggingface.co/${result.id}")
                                        )
                                        if (intent.resolveActivity(requireContext().packageManager) != null) {
                                            startActivity(intent)
                                        } else {
                                            toast("ブラウザを起動できませんでした")
                                        }
                                    }) {
                                        Text("ページを開く")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HfFilePickerDialog(model: ModelFileManager.HfModelSearchResult) {
        Dialog(onDismissRequest = {
            if (hfDownloadingFilePath == null) {
                hfFilePickerModel = null
            }
        }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = colorResource(id = R.color.primary_light)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "ダウンロードするファイルを選択", fontWeight = FontWeight.Bold)
                    Text(text = model.id, color = colorResource(id = R.color.text_secondary))
                    if (hfFilePickerLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("ファイル一覧を取得中...")
                        }
                    } else if (hfFilePickerFiles.isEmpty()) {
                        Text("対応ファイル（.gguf / .task / .litertlm）が見つかりません")
                    } else {
                        hfFilePickerFiles.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(text = file.path)
                                    Text(
                                        text = file.sizeBytes?.let { formatBytes(it) } ?: "size: unknown",
                                        color = colorResource(id = R.color.text_secondary)
                                    )
                                }
                                Button(
                                    enabled = hfDownloadingFilePath == null,
                                    onClick = { downloadHfModelFile(model.id, file.path) }
                                ) {
                                    val isDownloading = hfDownloadingFilePath == file.path
                                    Text(if (isDownloading) "DL中..." else "DL")
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            enabled = hfDownloadingFilePath == null,
                            onClick = { hfFilePickerModel = null }
                        ) { Text("閉じる") }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModelListCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "モデル", fontWeight = FontWeight.Bold)

                // 追加モデル ダウンロード中
                if (hfQueuedDownloads.isNotEmpty()) {
                    Text(
                        text = "追加モデル ダウンロード中",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                        items(hfQueuedDownloads, key = { "${it.modelId}/${it.filePath}" }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = colorResource(id = R.color.surface_card)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(text = item.modelId, fontWeight = FontWeight.SemiBold)
                                    Text(text = item.filePath, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                                    if (item.totalBytes > 0L) {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth(),
                                            progress = { item.progress },
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
                                    Text(text = item.statusText, color = colorResource(id = R.color.text_secondary), style = MaterialTheme.typography.bodySmall)
                                    if (item.isActive) {
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            TextButton(onClick = {
                                                ModelDownloadWorker.cancelCustomHf(
                                                    requireContext(),
                                                    item.modelId,
                                                    item.filePath
                                                )
                                            }) { Text("キャンセル") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 組み込みモデル
                Text(
                    text = "組み込みモデル",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (model in ModelFileManager.LocalModel.entries) {
                        val state = modelStates[model] ?: continue
                        val modelKey = "builtin_${model.name}"
                        val isExpanded = expandedModelKey == modelKey
                        ModelAccordionItem(
                            title = state.title,
                            status = state.status,
                            isExpanded = isExpanded,
                            onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                            onDownload = { requestNotificationPermissionForDownload(model) },
                            onDelete = {
                                val ok = ModelFileManager.deleteModel(requireContext(), model)
                                toast(if (ok) "削除しました" else "削除に失敗しました")
                                refreshModelStatus(model)
                                expandedModelKey = null
                            },
                            isDownloading = state.isDownloading,
                            isDownloaded = state.isDownloaded,
                            progress = state.progress,
                            progressText = state.progressText
                        )
                    }
                }

                // インポートされたモデル
                if (importedTasks.isNotEmpty()) {
                    Text(
                        text = "カスタムモデル",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (model in importedTasks) {
                            val modelKey = "imported_${model.path}"
                            val isExpanded = expandedModelKey == modelKey
                            ImportedModelAccordionItem(
                                name = model.name,
                                path = model.path,
                                isExpanded = isExpanded,
                                onToggle = { expandedModelKey = if (isExpanded) null else modelKey },
                                onDelete = {
                                    val result = ModelFileManager.deleteImportedTask(requireContext(), model.path)
                                    result.onSuccess {
                                        ImportedModelCapabilityStore.clear(requireContext(), model.path)
                                        toast("削除しました")
                                        refreshImportedTasks()
                                        expandedModelKey = null
                                    }.onFailure {
                                        toast("削除に失敗しました: ${it.message}")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModelAccordionItem(
        title: String,
        status: String,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        onDownload: () -> Unit,
        onDelete: () -> Unit,
        isDownloading: Boolean,
        isDownloaded: Boolean,
        progress: Float = 0f,
        progressText: String = ""
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!isExpanded && isDownloaded && !isDownloading) {
                        Text(
                            text = "✓ ダウンロード済み",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else if (!isExpanded && isDownloading) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = status, style = MaterialTheme.typography.bodySmall, color = colorResource(id = R.color.text_secondary))
                    if (isDownloading && progressText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { progress },
                            color = colorResource(id = R.color.primary),
                            trackColor = colorResource(id = R.color.context_meter_track)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorResource(id = R.color.text_secondary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!isDownloaded || isDownloading) {
                            Button(
                                onClick = onDownload,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isDownloading) "キャンセル" else "ダウンロード")
                            }
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(id = R.string.delete), fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ImportedModelAccordionItem(
        name: String,
        path: String,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            colors = CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.surface_card)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!isExpanded) {
                        Text(
                            text = "✓ インポート済み",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorResource(id = R.color.text_secondary),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "追加済みモデル", style = MaterialTheme.typography.bodySmall, color = colorResource(id = R.color.text_secondary))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = {
                                val caps = ImportedModelCapabilityStore.get(requireContext(), path)
                                capabilityDialogImageEnabled = caps.imageEnabled
                                capabilityDialogAudioEnabled = caps.audioEnabled
                                capabilityDialogModel = ModelFileManager.ImportedTaskModel(name, path)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("機能設定", fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
                        }
                        TextButton(
                            onClick = {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val tokens = withContext(Dispatchers.IO) {
                                        settingsRepository.getStopTokensForModel(path)
                                    }
                                    stopTokensDialogText = tokens.joinToString(", ")
                                    stopTokensDialogModel = ModelFileManager.ImportedTaskModel(name, path)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ストップトークン", fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(id = R.string.delete), fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f)
                        }
                    }
                }
            }
        }
    }

    private fun renderHfTokenState() {
        val token = HfAuthManager.getToken(requireContext())
        hfLinked = token.isNotBlank()
        // トークン状態変更時は検索結果をクリア
        if (hfLinked && hfSearchResults.isNotEmpty()) {
            hfSearchResults = emptyList()
            hfSearchResultsDialogVisible = false
        }
    }

    private fun logoutHf() {
        HfAuthManager.clearToken(requireContext())
        renderHfTokenState()
        toast("ログアウトしました")
    }

    private fun refreshImportedTasks() {
        importedTasks = ModelFileManager.listImportedTaskModels(requireContext())
    }

    private fun searchHfModels() {
        val query = hfSearchQuery.trim()
        if (query.isBlank()) {
            hfSearchError = "検索ワードを入力してください"
            hfSearchResults = emptyList()
            return
        }
        // 検索前に最新のトークン状態を確認
        renderHfTokenState()
        
        hfSearchLoading = true
        hfSearchError = null
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelFileManager.searchHuggingFaceModels(requireContext(), query)
            }
            result.onSuccess { list ->
                hfSearchResults = list
                hfSearchError = if (list.isEmpty()) "検索結果がありませんでした" else null
                hfSearchResultsDialogVisible = list.isNotEmpty()
            }.onFailure {
                hfSearchResults = emptyList()
                hfSearchError = "検索失敗: ${it.message}"
                hfSearchResultsDialogVisible = false
            }
            hfSearchLoading = false
        }
    }

    private fun openHfFilePicker(result: ModelFileManager.HfModelSearchResult) {
        hfFilePickerModel = result
        hfFilePickerLoading = true
        hfFilePickerFiles = emptyList()
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                ModelFileManager.listHuggingFaceDownloadableFiles(requireContext(), result.id)
            }
            files.onSuccess {
                hfFilePickerFiles = it
            }.onFailure {
                hfFilePickerFiles = emptyList()
                toast("ファイル一覧取得に失敗: ${it.message}")
            }
            hfFilePickerLoading = false
        }
    }

    private fun downloadHfModelFile(modelId: String, filePath: String) {
        hfDownloadingFilePath = filePath
        val enqueued = ModelDownloadWorker.enqueueCustomHf(requireContext(), modelId, filePath)
        if (enqueued) {
            if (hfQueuedDownloads.none { it.modelId == modelId && it.filePath == filePath }) {
                hfQueuedDownloads = hfQueuedDownloads + HfQueuedDownloadUiState(
                    modelId = modelId,
                    filePath = filePath,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "待機中",
                    isActive = true
                )
            }
            toast("ダウンロードキューに追加しました")
            hfFilePickerModel = null
        } else {
            toast("すでにダウンロード中です")
        }
        hfDownloadingFilePath = null
    }

    private fun observeCustomHfDownloadWork() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData(ModelDownloadWorker.TAG_HF_CUSTOM_DOWNLOAD)
            .observe(viewLifecycleOwner) { infos ->
                val mapped = infos.mapNotNull { info ->
                    val kind = info.progress.getString(ModelDownloadWorker.KEY_DOWNLOAD_KIND)
                        ?: info.outputData.getString(ModelDownloadWorker.KEY_DOWNLOAD_KIND)
                        ?: ModelDownloadWorker.DOWNLOAD_KIND_HF_CUSTOM
                    if (kind != ModelDownloadWorker.DOWNLOAD_KIND_HF_CUSTOM) return@mapNotNull null

                    val modelId =
                        info.progress.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                            ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_MODEL_ID)
                            ?: return@mapNotNull null
                    val filePath =
                        info.progress.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                            ?: info.outputData.getString(ModelDownloadWorker.KEY_HF_FILE_PATH)
                            ?: return@mapNotNull null
                    val downloaded =
                        info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                            .takeIf { it > 0L }
                            ?: info.outputData.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
                    val total =
                        info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                            .takeIf { it > 0L }
                            ?: info.outputData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                    val status = when (info.state) {
                        WorkInfo.State.ENQUEUED -> "待機中"
                        WorkInfo.State.RUNNING -> if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            "ダウンロード中 $percent% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                        } else {
                            "ダウンロード中"
                        }
                        WorkInfo.State.BLOCKED -> "待機中"
                        WorkInfo.State.SUCCEEDED -> "完了"
                        WorkInfo.State.CANCELLED -> "キャンセル"
                        WorkInfo.State.FAILED -> {
                            val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_MESSAGE)
                            if (error.isNullOrBlank()) "失敗" else "失敗: $error"
                        }
                    }
                    // 完了またはキャンセルされたダウンロードは追加しない
                    if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED) {
                        return@mapNotNull null
                    }
                    HfQueuedDownloadUiState(
                        modelId = modelId,
                        filePath = filePath,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        statusText = status,
                        isActive = info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.BLOCKED
                    )
                }

                hfQueuedDownloads = mapped
                    .sortedWith(compareBy<HfQueuedDownloadUiState> { it.modelId }.thenBy { it.filePath })

                if (infos.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    refreshImportedTasks()
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

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
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

    private data class HfQueuedDownloadUiState(
        val modelId: String,
        val filePath: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val statusText: String,
        val isActive: Boolean
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
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
