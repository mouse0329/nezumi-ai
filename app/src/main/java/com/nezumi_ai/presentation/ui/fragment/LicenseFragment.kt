package com.nezumi_ai.presentation.ui.fragment

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.nezumi_ai.R
import com.nezumi_ai.presentation.ui.model.LicenseEntry
import com.nezumi_ai.presentation.ui.model.LicenseCatalog
import com.nezumi_ai.presentation.ui.screen.SharedLicenseScreen
import com.nezumi_ai.presentation.ui.theme.NezumiComposeTheme
import com.nezumi_ai.utils.ExternalLinkOpener

class LicenseFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                SharedLicenseScreen(
                    title = getString(R.string.license_title),
                    body = getString(R.string.license_body),
                    openUrlLabel = getString(R.string.license_open_url),
                    backLabel = getString(R.string.back),
                    licenses = LicenseCatalog.entries.map { item ->
                        LicenseEntry(
                            title = getString(resourceId(item.key, "title")),
                            description = getString(resourceId(item.key, "desc")),
                            url = getString(resourceId(item.key, "url")),
                        )
                    },
                    onBack = { findNavController().navigateUp() },
                    onOpenUrl = { ExternalLinkOpener.openUrl(requireContext(), it) },
                )
            }
        }
    }

    private fun resourceId(key: String, suffix: String): Int =
        resources.getIdentifier("license_${key}_$suffix", "string", requireContext().packageName)
            .also {
                check(it != 0) { "Missing license resource: license_${key}_$suffix" }
            }
}
