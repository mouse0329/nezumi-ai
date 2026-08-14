package com.nezumi_ai.presentation.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpFontFamily
import com.nezumi_ai.presentation.ui.theme.createNotoSansJpTypography

class LicenseFragment : Fragment() {

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                LicenseScreen()
            }
        }
    }

    @Composable
    private fun LicenseScreen() {
        val licenses = listOf(
            LicenseItem(
                R.string.license_project_title,
                R.string.license_project_desc,
                R.string.license_project_url
            ),
            LicenseItem(
                R.string.license_androidx_title,
                R.string.license_androidx_desc,
                R.string.license_androidx_url
            ),
            LicenseItem(
                R.string.license_navigation_title,
                R.string.license_navigation_desc,
                R.string.license_navigation_url
            ),
            LicenseItem(
                R.string.license_room_title,
                R.string.license_room_desc,
                R.string.license_room_url
            ),
            LicenseItem(
                R.string.license_workmanager_title,
                R.string.license_workmanager_desc,
                R.string.license_workmanager_url
            ),
            LicenseItem(
                R.string.license_lifecycle_title,
                R.string.license_lifecycle_desc,
                R.string.license_lifecycle_url
            ),
            LicenseItem(
                R.string.license_biometric_title,
                R.string.license_biometric_desc,
                R.string.license_biometric_url
            ),
            LicenseItem(
                R.string.license_kotlin_title,
                R.string.license_kotlin_desc,
                R.string.license_kotlin_url
            ),
            LicenseItem(
                R.string.license_kotlin_coroutines_title,
                R.string.license_kotlin_coroutines_desc,
                R.string.license_kotlin_coroutines_url
            ),
            LicenseItem(
                R.string.license_kotlin_serialization_title,
                R.string.license_kotlin_serialization_desc,
                R.string.license_kotlin_serialization_url
            ),
            LicenseItem(
                R.string.license_compose_title,
                R.string.license_compose_desc,
                R.string.license_compose_url
            ),
            LicenseItem(
                R.string.license_richtext_title,
                R.string.license_richtext_desc,
                R.string.license_richtext_url
            ),
            LicenseItem(
                R.string.license_jlatexmath_title,
                R.string.license_jlatexmath_desc,
                R.string.license_jlatexmath_url
            ),
            LicenseItem(
                R.string.license_litertlm_title,
                R.string.license_litertlm_desc,
                R.string.license_litertlm_url
            ),
            LicenseItem(
                R.string.license_tflite_title,
                R.string.license_tflite_desc,
                R.string.license_tflite_url
            ),
            LicenseItem(
                R.string.license_onnxruntime_title,
                R.string.license_onnxruntime_desc,
                R.string.license_onnxruntime_url
            ),
            LicenseItem(
                R.string.license_appauth_title,
                R.string.license_appauth_desc,
                R.string.license_appauth_url
            ),
            LicenseItem(
                R.string.license_coil_title,
                R.string.license_coil_desc,
                R.string.license_coil_url
            ),
            LicenseItem(
                R.string.license_okhttp_title,
                R.string.license_okhttp_desc,
                R.string.license_okhttp_url
            ),
            LicenseItem(
                R.string.license_llamacpp_title,
                R.string.license_llamacpp_desc,
                R.string.license_llamacpp_url
            ),
            LicenseItem(
                R.string.license_llamarn_title,
                R.string.license_llamarn_desc,
                R.string.license_llamarn_url
            ),
            LicenseItem(
                R.string.license_ggml_title,
                R.string.license_ggml_desc,
                R.string.license_ggml_url
            ),
            LicenseItem(
                R.string.license_nlohmann_title,
                R.string.license_nlohmann_desc,
                R.string.license_nlohmann_url
            ),
            LicenseItem(
                R.string.license_anyascii_title,
                R.string.license_anyascii_desc,
                R.string.license_anyascii_url
            ),
            LicenseItem(
                R.string.license_mnn_title,
                R.string.license_mnn_desc,
                R.string.license_mnn_url
            ),
            LicenseItem(
                R.string.license_stable_diffusion_mnn_title,
                R.string.license_stable_diffusion_mnn_desc,
                R.string.license_stable_diffusion_mnn_url
            ),
            LicenseItem(
                R.string.license_huggingface_title,
                R.string.license_huggingface_desc,
                R.string.license_huggingface_url
            ),
            LicenseItem(
                R.string.license_gemma_title,
                R.string.license_gemma_desc,
                R.string.license_gemma_url
            ),
            LicenseItem(
                R.string.license_downloaded_models_title,
                R.string.license_downloaded_models_desc,
                R.string.license_downloaded_models_url
            ),
            // VOICEVOX 本体の許諾内容と、話者ごとのクレジット表記。
            // 生成音声を公開する際にクレジットが必須なため、アプリ内から常に参照できるようにする。
            LicenseItem(
                R.string.license_voicevox_title,
                R.string.license_voicevox_desc,
                R.string.license_voicevox_url
            ),
            LicenseItem(
                R.string.license_voicevox_libraries_title,
                R.string.license_voicevox_libraries_desc,
                R.string.license_voicevox_libraries_url
            ),
            // ドキュメント変換 (Markdown → Word/PDF/Excel 生成、
            // Word/PDF/Excel → Markdown 読み取り) で追加した依存群。
            LicenseItem(
                R.string.license_apache_poi_title,
                R.string.license_apache_poi_desc,
                R.string.license_apache_poi_url
            ),
            LicenseItem(
                R.string.license_xmlbeans_title,
                R.string.license_xmlbeans_desc,
                R.string.license_xmlbeans_url
            ),
            LicenseItem(
                R.string.license_woodstox_title,
                R.string.license_woodstox_desc,
                R.string.license_woodstox_url
            ),
            LicenseItem(
                R.string.license_pdfbox_android_title,
                R.string.license_pdfbox_android_desc,
                R.string.license_pdfbox_android_url
            ),
            LicenseItem(
                R.string.license_chaquopy_title,
                R.string.license_chaquopy_desc,
                R.string.license_chaquopy_url
            ),
            LicenseItem(
                R.string.license_markitdown_title,
                R.string.license_markitdown_desc,
                R.string.license_markitdown_url
            ),
            LicenseItem(
                R.string.license_markitdown_deps_title,
                R.string.license_markitdown_deps_desc,
                R.string.license_markitdown_deps_url
            ),
            // ページ取得ツール (URL → HTML 取得 + Markdown 変換) で追加した依存群。
            LicenseItem(
                R.string.license_jsoup_title,
                R.string.license_jsoup_desc,
                R.string.license_jsoup_url
            ),
            LicenseItem(
                R.string.license_flexmark_title,
                R.string.license_flexmark_desc,
                R.string.license_flexmark_url
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
        ) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.statusBarsPadding())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { findNavController().navigateUp() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = stringResource(id = R.string.back),
                        tint = colorResource(id = R.color.text_primary)
                    )
                }
                Text(
                    text = stringResource(id = R.string.license_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorResource(id = R.color.text_primary),
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = stringResource(id = R.string.license_body),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(licenses) { item ->
                    LicenseCard(item)
                }
            }
        }
    }

    @Composable
    private fun LicenseCard(item: LicenseItem) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = colorResource(id = R.color.primary_light)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(id = item.titleRes),
                    color = colorResource(id = R.color.text_primary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(id = item.descriptionRes),
                    color = colorResource(id = R.color.text_secondary),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = {
                    val url = getString(item.urlRes)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    if (intent.resolveActivity(requireContext().packageManager) != null) {
                        startActivity(intent)
                    }
                }) {
                    Text(text = stringResource(id = R.string.license_open_url))
                }
            }
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

        val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
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

        val assetContext = LocalContext.current

        val notoFamily = remember(assetContext.assets) {

            createNotoSansJpFontFamily(assetContext.assets)

        }

        val notoTypography = remember(notoFamily) {

            createNotoSansJpTypography(notoFamily)

        }

        MaterialTheme(

            colorScheme = colorScheme,

            typography = notoTypography,

            content = content

        )
    }
}
