package com.nezumi_ai.presentation.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
import kotlinx.coroutines.launch

class HelpFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NezumiComposeTheme {
                HelpScreen()
            }
        }
    }

    @Composable
    private fun HelpScreen() {
        val helpText = remember { loadHelpText() }
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current
        val density = LocalDensity.current
        val textColor = colorResource(id = R.color.text_primary)
        val headingOffsets = remember(helpText) { buildHeadingScrollOffsets(helpText, density.density) }

        val uriHandler = remember(helpText, headingOffsets) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    if (uri.startsWith("#")) {
                        val anchor = uri.removePrefix("#")
                        val offset = headingOffsets[anchor] ?: 0
                        coroutineScope.launch {
                            scrollState.animateScrollTo(offset.coerceAtLeast(0))
                        }
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }
                    }
                }
            }
        }

        val linkSpan = SpanStyle(color = textColor, textDecoration = TextDecoration.Underline)
        val linkStyle = TextLinkStyles(
            style = linkSpan,
            hoveredStyle = linkSpan,
            pressedStyle = linkSpan,
            focusedStyle = linkSpan
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg_session_list))
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = { findNavController().navigateUp() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = stringResource(id = R.string.back),
                        tint = textColor
                    )
                }
                Text(
                    text = stringResource(id = R.string.help_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }

            CompositionLocalProvider(
                LocalContentColor provides textColor,
                LocalUriHandler provides uriHandler
            ) {
                ProvideTextStyle(
                    value = TextStyle(
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                ) {
                    RichText(
                        modifier = Modifier.fillMaxWidth(),
                        style = RichTextStyle(
                            stringStyle = RichTextStringStyle(linkStyle = linkStyle)
                        )
                    ) {
                        Markdown(content = helpText)
                    }
                }
            }
        }
    }

    private fun loadHelpText(): String {
        val raw = requireContext().assets.open("nezumi-ai-help.md").bufferedReader().use { it.readText() }
        return raw.replace("\${appversion}", BuildConfig.VERSION_NAME)
    }

    private fun buildHeadingScrollOffsets(helpText: String, density: Float): Map<String, Int> {
        val lineHeightPx = (22f * density).toInt()
        val offsets = linkedMapOf<String, Int>()
        var lineIndex = 0
        for (line in helpText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                val headingText = trimmed.trimStart('#').trim()
                offsets[slugifyHeading(headingText)] = lineIndex * lineHeightPx
            }
            lineIndex++
        }
        return offsets
    }

    private fun slugifyHeading(heading: String): String {
        return heading
            .lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^a-z0-9\\u3040-\\u309f\\u30a0-\\u30ff\\u4e00-\\u9fff-]"), "")
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
