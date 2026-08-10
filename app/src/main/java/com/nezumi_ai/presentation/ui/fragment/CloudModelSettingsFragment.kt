package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudModelId
import com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry

/**
 * クラウド推論プロバイダの API キー / Base URL 設定と、ユーザーが利用したい
 * モデル名の登録を行う画面。
 *
 * ## 責務
 * - 6 プロバイダ (Claude / Gemini / OpenAI / Ollama Local / Ollama Remote / LM Studio) の
 *   認証情報を [CloudApiKeyStore] 経由で暗号化保存する。
 * - 「プリセットから利用したいモデル名」を [CloudUserModelRegistry] へ追加/削除する。
 *   追加されたモデルは [com.nezumi_ai.data.preset.PresetModelCatalog.downloadedModels] を
 *   通じてプリセット選択肢に自然に流し込まれる (設定済みプロバイダのぶんだけ)。
 *
 * ## 意図的にやらないこと
 * - モデル一覧の動的取得。ユーザーはモデル名を直接入力する方針 (Q&A 決定事項)。
 * - 疎通確認/pingリクエスト。「保存 → プリセット選択 → 実推論」で判定される。
 * - ネットワーク越しの API 呼び出し。設定と登録のみを担当する。
 *
 * 遷移元は [ModelSettingsFragment] のエントリーボタン。
 * Navigation graph: `action_modelSettingsFragment_to_cloudModelSettingsFragment`。
 */
class CloudModelSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NezumiComposeTheme {
                    CloudModelSettingsScreen(
                        onBack = { findNavController().navigateUp() },
                        toast = ::toast
                    )
                }
            }
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // ─── 画面本体 ─────────────────────────────────────────────────

    @Composable
    private fun CloudModelSettingsScreen(
        onBack: () -> Unit,
        toast: (String) -> Unit
    ) {
        val context = requireContext()
        val bg = colorResource(id = R.color.bg_session_list)

        // ユーザー追加モデルは remember で保持。add/remove 後に再読込するために revision を管理。
        var registryRevision by remember { mutableStateOf(0) }
        val userModels = remember(registryRevision) { CloudUserModelRegistry.list(context) }

        // 認証情報の保存で isConfigured の返り値が変わり、userModels のフィルタも変えたいので
        // 同じ revision で両方無効化する。
        // credentialsRevision は各プロバイダの Save/Clear で increment する。
        var credentialsRevision by remember { mutableStateOf(0) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // ── ヘッダ ────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(id = R.string.cloud_models_screen_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.cloud_models_screen_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── プロバイダごとの設定カード ───────────────
                CloudApiKeyStore.Provider.values().forEach { provider ->
                    ProviderCard(
                        provider = provider,
                        credentialsRevision = credentialsRevision,
                        onCredentialsChanged = {
                            credentialsRevision++
                            registryRevision++  // フィルタ結果を更新
                        },
                        toast = toast
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ── 追加済みモデル一覧 ────────────────────────
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(id = R.string.cloud_models_added_models_section),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                if (userModels.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.cloud_models_added_models_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            userModels.forEachIndexed { index, modelId ->
                                if (index > 0) {
                                    Divider(color = colorResource(id = R.color.border))
                                }
                                RegisteredModelRow(
                                    modelId = modelId,
                                    onRemove = {
                                        CloudUserModelRegistry.remove(context, modelId)
                                        registryRevision++
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ─── 1 プロバイダ分の設定カード ─────────────────────────────

    @Composable
    private fun ProviderCard(
        provider: CloudApiKeyStore.Provider,
        credentialsRevision: Int,
        onCredentialsChanged: () -> Unit,
        toast: (String) -> Unit
    ) {
        val context = requireContext()

        // 初期表示: 保存済み値を読み取り、以降はローカル state で編集させる。
        // credentialsRevision が更新されたら key(...) で再初期化する。
        var apiKey by remember(credentialsRevision, provider) {
            mutableStateOf(CloudApiKeyStore.getApiKey(context, provider))
        }
        var baseUrl by remember(credentialsRevision, provider) {
            mutableStateOf(
                // ユーザーが明示的に保存した値のみを編集フィールドに出したい。
                // getBaseUrl はデフォルト値を返してくるので、実 store の raw 値を優先する。
                readRawBaseUrl(provider) ?: ""
            )
        }
        var newModelName by remember(provider) { mutableStateOf("") }
        val isConfigured = remember(credentialsRevision, provider) {
            CloudApiKeyStore.isConfigured(context, provider)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // タイトル行 (プロバイダ名 + 状態バッジ)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = providerLabel(provider),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    StatusBadge(isConfigured = isConfigured)
                }
                Spacer(modifier = Modifier.height(8.dp))

                // ── API キー欄 (必要なプロバイダのみ) ─────────────
                if (provider.requiresApiKey) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(id = R.string.cloud_models_api_key_label)) },
                        placeholder = { Text(stringResource(id = R.string.cloud_models_api_key_placeholder)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── Base URL 欄 (常に表示、既定値がある場合はプレースホルダ表示) ──
                val baseUrlHint = when {
                    provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE ->
                        stringResource(id = R.string.cloud_models_base_url_required_hint)
                    provider.defaultBaseUrl != null ->
                        stringResource(id = R.string.cloud_models_base_url_default_hint, provider.defaultBaseUrl)
                    else -> ""
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(id = R.string.cloud_models_base_url_label)) },
                    placeholder = { Text(baseUrlHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (baseUrl.isBlank() && baseUrlHint.isNotBlank()) {
                    Text(
                        text = baseUrlHint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val savedKey = if (provider.requiresApiKey) {
                                CloudApiKeyStore.setApiKey(context, provider, apiKey)
                            } else if (apiKey.isNotBlank()) {
                                // 任意 API キー (Ollama/LMStudio でリバースプロキシ用) は
                                // 入力があれば保存、空なら明示的に削除する。
                                CloudApiKeyStore.setApiKey(context, provider, apiKey)
                            } else {
                                CloudApiKeyStore.setApiKey(context, provider, "")
                            }
                            val savedUrl = CloudApiKeyStore.setBaseUrl(context, provider, baseUrl)
                            if (savedKey && savedUrl) {
                                toast(getString(R.string.cloud_models_credentials_saved))
                                onCredentialsChanged()
                            } else {
                                toast(getString(R.string.cloud_models_credentials_save_failed))
                            }
                        }
                    ) {
                        Text(stringResource(id = R.string.cloud_models_save_credentials))
                    }
                    OutlinedButton(
                        onClick = {
                            CloudApiKeyStore.clear(context, provider)
                            apiKey = ""
                            baseUrl = ""
                            toast(getString(R.string.cloud_models_credentials_cleared))
                            onCredentialsChanged()
                        }
                    ) {
                        Text(stringResource(id = R.string.cloud_models_clear_credentials))
                    }
                }

                // ── モデル名追加欄 ────────────────────────────────
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = colorResource(id = R.color.border))
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = newModelName,
                    onValueChange = { newModelName = it },
                    label = { Text(stringResource(id = R.string.cloud_models_add_model_label)) },
                    placeholder = { Text(addModelHint(provider)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        val cleaned = newModelName.trim()
                        when {
                            cleaned.isEmpty() -> toast(getString(R.string.cloud_models_add_failed_blank))
                            !CloudApiKeyStore.isConfigured(context, provider) ->
                                toast(getString(R.string.cloud_models_add_failed_not_configured))
                            else -> {
                                val modelId = CloudModelId.build(provider, cleaned)
                                CloudUserModelRegistry.add(context, modelId)
                                newModelName = ""
                                onCredentialsChanged()
                            }
                        }
                    },
                    enabled = isConfigured
                ) {
                    Text(stringResource(id = R.string.cloud_models_add_button))
                }
            }
        }
    }

    @Composable
    private fun RegisteredModelRow(
        modelId: String,
        onRemove: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = CloudModelId.displayLabel(modelId),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.cloud_models_remove_button),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun StatusBadge(isConfigured: Boolean) {
        val (text, color) = if (isConfigured) {
            stringResource(id = R.string.cloud_models_status_configured) to
                colorResource(id = R.color.primary)
        } else {
            stringResource(id = R.string.cloud_models_status_missing) to
                MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }

    // ─── ヘルパ ───────────────────────────────────────────────────

    @Composable
    private fun providerLabel(provider: CloudApiKeyStore.Provider): String = when (provider) {
        CloudApiKeyStore.Provider.CLAUDE -> stringResource(id = R.string.cloud_models_provider_claude)
        CloudApiKeyStore.Provider.GEMINI -> stringResource(id = R.string.cloud_models_provider_gemini)
        CloudApiKeyStore.Provider.OPENAI -> stringResource(id = R.string.cloud_models_provider_openai)
        CloudApiKeyStore.Provider.OLLAMA_LOCAL -> stringResource(id = R.string.cloud_models_provider_ollama_local)
        CloudApiKeyStore.Provider.OLLAMA_REMOTE -> stringResource(id = R.string.cloud_models_provider_ollama_remote)
        CloudApiKeyStore.Provider.LM_STUDIO -> stringResource(id = R.string.cloud_models_provider_lmstudio)
    }

    @Composable
    private fun addModelHint(provider: CloudApiKeyStore.Provider): String = when (provider) {
        CloudApiKeyStore.Provider.CLAUDE -> stringResource(id = R.string.cloud_models_add_model_hint_claude)
        CloudApiKeyStore.Provider.GEMINI -> stringResource(id = R.string.cloud_models_add_model_hint_gemini)
        CloudApiKeyStore.Provider.OPENAI -> stringResource(id = R.string.cloud_models_add_model_hint_openai)
        CloudApiKeyStore.Provider.OLLAMA_LOCAL,
        CloudApiKeyStore.Provider.OLLAMA_REMOTE ->
            stringResource(id = R.string.cloud_models_add_model_hint_ollama)
        CloudApiKeyStore.Provider.LM_STUDIO -> stringResource(id = R.string.cloud_models_add_model_hint_lmstudio)
    }

    /**
     * Base URL の生値 (デフォルトフォールバック抜きの、ユーザーが明示保存した値) を読む。
     * [CloudApiKeyStore.getBaseUrl] はデフォルトを返してしまうため、
     * 編集フィールドの初期値としては生値を使いたい。
     *
     * EncryptedSharedPreferences 経由なので API は公開されている `getBaseUrl` を使う。
     * ただし、返ってきた値が defaultBaseUrl と完全一致した場合のみ「未保存」とみなす近似で扱う。
     */
    private fun readRawBaseUrl(provider: CloudApiKeyStore.Provider): String? {
        val resolved = CloudApiKeyStore.getBaseUrl(requireContext(), provider)
        return if (resolved.isBlank() || resolved == provider.defaultBaseUrl) null else resolved
    }

    // ─── テーマ (PresetSettingsFragment.NezumiComposeTheme と同一実装) ───

    @Composable
    private fun NezumiComposeTheme(content: @Composable () -> Unit) {
        val primary = colorResource(id = R.color.primary)
        val onPrimary = colorResource(id = R.color.nezumi_on_primary)
        val primaryContainer = colorResource(id = R.color.nezumi_primary_container)
        val onPrimaryContainer = colorResource(id = R.color.nezumi_on_primary_container)
        val surface = colorResource(id = R.color.surface_card)
        val onSurface = colorResource(id = R.color.text_primary)
        val onSurfaceVariant = colorResource(id = R.color.text_secondary)

        val scheme = lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            surface = surface,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            background = colorResource(id = R.color.bg_session_list),
            onBackground = onSurface
        )
        androidx.compose.material3.MaterialTheme(colorScheme = scheme, content = content)
    }
}
