package com.nezumi_ai.presentation.ui.theme

import android.content.res.AssetManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.nezumi_ai.R

/**
 * Noto Sans JP loaded from app assets (`assets/fonts/static/`).
 * Used for the whole Compose UI and kept in sync with PDF export fonts.
 */
fun createNotoSansJpFontFamily(assets: AssetManager): FontFamily = FontFamily(
    Font("fonts/static/NotoSansJP-Thin.ttf", assets, FontWeight.Thin),
    Font("fonts/static/NotoSansJP-ExtraLight.ttf", assets, FontWeight.ExtraLight),
    Font("fonts/static/NotoSansJP-Light.ttf", assets, FontWeight.Light),
    Font("fonts/static/NotoSansJP-Regular.ttf", assets, FontWeight.Normal),
    Font("fonts/static/NotoSansJP-Medium.ttf", assets, FontWeight.Medium),
    Font("fonts/static/NotoSansJP-SemiBold.ttf", assets, FontWeight.SemiBold),
    Font("fonts/static/NotoSansJP-Bold.ttf", assets, FontWeight.Bold),
    Font("fonts/static/NotoSansJP-ExtraBold.ttf", assets, FontWeight.ExtraBold),
    Font("fonts/static/NotoSansJP-Black.ttf", assets, FontWeight.Black),
)

/**
 * Builds a Typography that uses Noto Sans JP for every role while keeping
 * Material3 default sizes / line heights / letter spacings.
 */
fun createNotoSansJpTypography(fontFamily: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily),
    )
}

/**
 * App-wide Compose theme. Loads Noto Sans JP from assets/fonts and applies
 * the project's color scheme (same colors as the previous per-fragment themes).
 */
@Composable
fun NezumiComposeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val fontFamily = remember(context.assets) {
        createNotoSansJpFontFamily(context.assets)
    }
    val typography = remember(fontFamily) {
        createNotoSansJpTypography(fontFamily)
    }

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
        typography = typography,
        content = content
    )
}
