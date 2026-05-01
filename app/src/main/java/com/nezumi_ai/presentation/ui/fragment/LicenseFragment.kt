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
                R.string.license_constraintlayout_title,
                R.string.license_constraintlayout_desc,
                R.string.license_constraintlayout_url
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
                R.string.license_kotlin_title,
                R.string.license_kotlin_desc,
                R.string.license_kotlin_url
            ),
            LicenseItem(
                R.string.license_mediapipe_title,
                R.string.license_mediapipe_desc,
                R.string.license_mediapipe_url
            ),
            LicenseItem(
                R.string.license_mediapipe_genai_title,
                R.string.license_mediapipe_genai_desc,
                R.string.license_mediapipe_genai_url
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
                R.string.license_gemma4_title,
                R.string.license_gemma4_desc,
                R.string.license_gemma4_url
            ),
            LicenseItem(
                R.string.license_appauth_title,
                R.string.license_appauth_desc,
                R.string.license_appauth_url
            ),
            LicenseItem(
                R.string.license_richtext_title,
                R.string.license_richtext_desc,
                R.string.license_richtext_url
            ),
            LicenseItem(
                R.string.license_junit_title,
                R.string.license_junit_desc,
                R.string.license_junit_url
            ),
            LicenseItem(
                R.string.license_androidtest_title,
                R.string.license_androidtest_desc,
                R.string.license_androidtest_url
            ),
            LicenseItem(
                R.string.license_compose_title,
                R.string.license_compose_desc,
                R.string.license_compose_url
            ),
            LicenseItem(
                R.string.license_coil_title,
                R.string.license_coil_desc,
                R.string.license_coil_url
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
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
                .statusBarsPadding()
        ) {
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

        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            content = content
        )
    }
}
