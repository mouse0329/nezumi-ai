package com.nezumi_ai.presentation.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.miniapp.MiniAppException
import com.nezumi_ai.data.miniapp.MiniAppInstaller
import com.nezumi_ai.data.miniapp.MiniAppStore
import com.nezumi_ai.presentation.ui.theme.NezumiComposeTheme
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 仕様 v1.1 §35.5 Mini App Manager。
 *
 * ハンバーガーメニューから開く Mini App 専用の管理画面で、
 * インストール・起動・削除・ZIP/URLインポートの**唯一の入口**（§35.5.1）。
 * 画面本体は Compose（本プロジェクトの SettingsComposeFragment 方式に倣う）。
 */
class MiniAppManagerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                MiniAppManagerScreen(
                    onOpenApp = { appId ->
                        findNavController().navigate(
                            R.id.action_miniAppManagerFragment_to_miniAppRunnerFragment,
                            Bundle().apply { putString("appId", appId) }
                        )
                    },
                    onBack = { findNavController().popBackStack() }
                )
            }
        }
    }
}

private sealed interface ManagerDialog {
    /** [c] 未署名アプリの Dev Mode 同意（§35.5.3）。 */
    data class DevModeConsent(val pending: MiniAppInstaller.PendingInstall.UnsignedNeedsConsent) : ManagerDialog

    /** [c] 信頼できない署名鍵の Dev Mode 同意 + 鍵の信頼登録。 */
    data class UnknownKeyConsent(val pending: MiniAppInstaller.PendingInstall.UnknownKeyNeedsConsent) : ManagerDialog

    /** [d] Permission 提示（インストール前に要求権限の全体像を必ず提示、§35.5.3）。 */
    data class PermissionReview(
        val verification: com.nezumi_ai.data.miniapp.MiniAppSignatureVerifier.VerificationResult,
        val devModeConsent: Boolean
    ) : ManagerDialog

    /** 削除確認（§35.5.2: 削除前に確認ダイアログ）。 */
    data class DeleteConfirm(val appId: String, val name: String) : ManagerDialog

    /** URL インポート入力。 */
    object UrlImport : ManagerDialog

    /** アプリ情報・ストレージ使用量（App Data の初期化/確認）。 */
    data class AppInfo(val appId: String, val name: String) : ManagerDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniAppManagerScreen(onOpenApp: (String) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MiniAppStore.get(context) }

    var apps by remember { mutableStateOf(store.list()) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<ManagerDialog?>(null) }
    var urlInput by remember { mutableStateOf("") }

    fun refresh() {
        apps = store.list()
    }

    fun startPrepare(source: MiniAppInstaller.InstallSource) {
        scope.launch {
            busy = true
            errorMessage = null
            try {
                when (val pending = MiniAppInstaller.prepare(context, source)) {
                    is MiniAppInstaller.PendingInstall.TrustedReady ->
                        dialog = ManagerDialog.PermissionReview(pending.verification, devModeConsent = false)
                    is MiniAppInstaller.PendingInstall.UnsignedNeedsConsent ->
                        dialog = ManagerDialog.DevModeConsent(pending)
                    is MiniAppInstaller.PendingInstall.UnknownKeyNeedsConsent ->
                        dialog = ManagerDialog.UnknownKeyConsent(pending)
                }
            } catch (e: MiniAppException) {
                errorMessage = "${e.code}: ${e.message}"
            } catch (e: Exception) {
                errorMessage = e.message ?: "unknown error"
            } finally {
                busy = false
            }
        }
    }

    fun finalizeInstall(verification: com.nezumi_ai.data.miniapp.MiniAppSignatureVerifier.VerificationResult, devModeConsent: Boolean) {
        scope.launch {
            busy = true
            errorMessage = null
            try {
                MiniAppInstaller.install(context, verification, devModeConsent)
                refresh()
            } catch (e: MiniAppException) {
                errorMessage = "${e.code}: ${e.message}"
            } catch (e: Exception) {
                errorMessage = e.message ?: "unknown error"
            } finally {
                busy = false
            }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        startPrepare(MiniAppInstaller.InstallSource.LocalZip(uri))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.miniapp_manager_title),
                        color = colorResource(R.color.text_primary)
                    )
                },
                navigationIcon = {
                    // 他画面（SettingsComposeFragment 等）と同じ ic_back アイコンに統一
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.back),
                            tint = colorResource(R.color.text_primary)
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // インストール（§35.5.2: ZIPをローカルから選択 / URLを入力してインポート）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // 端末のファイルマネージャによって ZIP の MIME 型がまちまちなため広めに指定
                        zipPicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    enabled = !busy
                ) { Text(stringResource(R.string.miniapp_install_from_zip)) }
                OutlinedButton(
                    onClick = { urlInput = ""; dialog = ManagerDialog.UrlImport },
                    enabled = !busy
                ) { Text(stringResource(R.string.miniapp_install_from_url)) }
            }

            if (busy) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.miniapp_installing))
                }
            }
            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.miniapp_installed_list),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            if (apps.isEmpty()) {
                Text(
                    stringResource(R.string.miniapp_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(apps, key = { it.manifest.id }) { app ->
                        MiniAppCard(
                            app = app,
                            onOpen = { onOpenApp(app.manifest.id) },
                            onInfo = { dialog = ManagerDialog.AppInfo(app.manifest.id, app.manifest.name) },
                            onDelete = { dialog = ManagerDialog.DeleteConfirm(app.manifest.id, app.manifest.name) }
                        )
                    }
                }
            }
        }
    }

    // ---- ダイアログ群（§35.5.3 [c]/[d]） ----
    when (val d = dialog) {
        is ManagerDialog.DevModeConsent -> {
            // Dev Mode が無効ならインストール拒否（§35.5.3 [c]）
            val devModeEnabled = remember { PreferencesHelper.isMiniAppDevModeEnabled(context) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.miniapp_dev_mode_title)) },
                text = {
                    Text(
                        if (devModeEnabled)
                            stringResource(R.string.miniapp_dev_mode_unsigned_message, d.pending.verification.manifest.name)
                        else
                            stringResource(R.string.miniapp_dev_mode_disabled_message)
                    )
                },
                confirmButton = {
                    if (devModeEnabled) {
                        TextButton(onClick = {
                            dialog = ManagerDialog.PermissionReview(d.pending.verification, devModeConsent = true)
                        }) { Text(stringResource(R.string.miniapp_dev_mode_agree)) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.miniapp_cancel)) }
                }
            )
        }

        is ManagerDialog.UnknownKeyConsent -> {
            val devModeEnabled = remember { PreferencesHelper.isMiniAppDevModeEnabled(context) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.miniapp_dev_mode_title)) },
                text = {
                    Text(
                        if (devModeEnabled)
                            stringResource(
                                R.string.miniapp_dev_mode_unknown_key_message,
                                d.pending.manifest.name,
                                d.pending.keyId
                            )
                        else
                            stringResource(R.string.miniapp_dev_mode_disabled_message)
                    )
                },
                confirmButton = {
                    if (devModeEnabled) {
                        TextButton(onClick = {
                            val pending = d.pending
                            dialog = null
                            scope.launch {
                                busy = true
                                errorMessage = null
                                try {
                                    MiniAppInstaller.installTrustingKey(context, pending)
                                    refresh()
                                } catch (e: MiniAppException) {
                                    errorMessage = "${e.code}: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text(stringResource(R.string.miniapp_dev_mode_trust_key)) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.miniapp_cancel)) }
                }
            )
        }

        is ManagerDialog.PermissionReview -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.miniapp_permissions_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.miniapp_permissions_message,
                                d.verification.manifest.name,
                                d.verification.manifest.version
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        if (d.verification.manifest.permissions.isEmpty()) {
                            Text(stringResource(R.string.miniapp_permissions_none))
                        } else {
                            d.verification.manifest.permissions.forEach { p ->
                                Text("• $p", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        finalizeInstall(d.verification, d.devModeConsent)
                    }) { Text(stringResource(R.string.miniapp_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.miniapp_cancel)) }
                }
            )
        }

        is ManagerDialog.DeleteConfirm -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.miniapp_delete_title)) },
                text = { Text(stringResource(R.string.miniapp_delete_message, d.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        scope.launch {
                            MiniAppInstaller.uninstall(context, d.appId)
                            refresh()
                        }
                    }) { Text(stringResource(R.string.miniapp_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.miniapp_cancel)) }
                }
            )
        }

        is ManagerDialog.AppInfo -> {
            MiniAppInfoDialog(
                appId = d.appId,
                name = d.name,
                onDismiss = { dialog = null },
                onChanged = { refresh() }
            )
        }

        is ManagerDialog.UrlImport -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.miniapp_install_from_url)) },
                text = {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("https://example.com/miniapp.zip") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val url = urlInput.trim()
                        if (url.isNotBlank()) {
                            dialog = null
                            startPrepare(MiniAppInstaller.InstallSource.Url(url))
                        }
                    }) { Text(stringResource(R.string.miniapp_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.miniapp_cancel)) }
                }
            )
        }

        null -> Unit
    }
}

@Composable
private fun MiniAppCard(
    app: MiniAppStore.InstalledApp,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                app.manifest.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorResource(R.color.text_primary)
            )
            Text(
                "${app.manifest.id}  v${app.manifest.version}  by ${app.manifest.publisher}",
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.text_secondary)
            )
            if (app.devMode) {
                Text(
                    stringResource(R.string.miniapp_badge_dev_mode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (app.manifest.permissions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    app.manifest.permissions.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.text_secondary)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text(stringResource(R.string.miniapp_open)) }
                OutlinedButton(onClick = onInfo) { Text(stringResource(R.string.miniapp_info)) }
                OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.miniapp_delete)) }
            }
        }
    }
}

/**
 * アプリ情報 + ストレージ使用量の確認と App Data 初期化ダイアログ。
 * 初期化は settings/cache/user-data をクリアして領域を作り直す（Package には触れない、§4）。
 */
@Composable
private fun MiniAppInfoDialog(appId: String, name: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MiniAppStore.get(context) }
    val app = remember(appId) { store.get(appId) }

    var usage by remember { mutableStateOf<Map<String, Long>?>(null) }
    var initializing by remember { mutableStateOf(false) }
    var confirmInit by remember { mutableStateOf(false) }

    fun refreshUsage() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dataDir = store.dataDir(appId)
            fun dirSize(f: File): Long =
                if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val settings = File(dataDir, "settings.json").let { if (it.exists()) it.length() else 0L }
            val cache = dirSize(File(dataDir, "cache"))
            val userData = dirSize(File(dataDir, "user-data"))
            val total = dirSize(dataDir)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                usage = mapOf(
                    "total" to total,
                    "settings" to settings,
                    "cache" to cache,
                    "userData" to userData
                )
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(appId) { refreshUsage() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.miniapp_info_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                app?.let {
                    Text("ID: ${it.manifest.id}", style = MaterialTheme.typography.bodySmall)
                    Text("Version: ${it.manifest.version}", style = MaterialTheme.typography.bodySmall)
                    Text("Publisher: ${it.manifest.publisher}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.miniapp_storage_usage_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (usage == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    val u = usage!!
                    Text(stringResource(R.string.miniapp_storage_total, formatBytes(u["total"] ?: 0)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.miniapp_storage_settings, formatBytes(u["settings"] ?: 0)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.miniapp_storage_cache, formatBytes(u["cache"] ?: 0)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.miniapp_storage_user_data, formatBytes(u["userData"] ?: 0)), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirmInit = true },
                enabled = !initializing
            ) { Text(stringResource(R.string.miniapp_storage_initialize), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.miniapp_close)) }
        }
    )

    if (confirmInit) {
        AlertDialog(
            onDismissRequest = { confirmInit = false },
            title = { Text(stringResource(R.string.miniapp_storage_initialize)) },
            text = { Text(stringResource(R.string.miniapp_storage_initialize_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmInit = false
                    initializing = true
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val dataDir = store.dataDir(appId)
                        dataDir.deleteRecursively()
                        File(dataDir, "cache").mkdirs()
                        File(dataDir, "user-data").mkdirs()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            initializing = false
                            onChanged()
                            refreshUsage()
                        }
                    }
                }) { Text(stringResource(R.string.miniapp_storage_initialize)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmInit = false }) { Text(stringResource(R.string.miniapp_cancel)) }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
