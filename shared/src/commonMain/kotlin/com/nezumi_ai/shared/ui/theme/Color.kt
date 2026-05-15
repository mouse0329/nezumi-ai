package com.nezumi_ai.shared.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Android res/values-night/colors.xml に合わせたダークテーマ
private val DarkPrimary = Color(0xFF0284C7)
private val DarkOnPrimary = Color(0xFFFFFFFF)
private val DarkPrimaryContainer = Color(0xFF525252)
private val DarkOnPrimaryContainer = Color(0xFFE0F2FE)
private val DarkTextPrimary = Color(0xFFF3F4F6)
private val DarkTextSecondary = Color(0xFF9CA3AF)
private val DarkBgChat = Color(0xFF111827)
private val DarkSurfaceCard = Color(0xFF1F2937)
private val DarkBgSessionList = Color(0xFF0F172A)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkTextSecondary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkSurfaceCard,
    onSecondaryContainer = DarkTextPrimary,
    tertiary = Color(0xFF38BDF8),
    onTertiary = DarkOnPrimary,
    background = DarkBgChat,
    onBackground = DarkTextPrimary,
    surface = DarkSurfaceCard,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkBgSessionList,
    onSurfaceVariant = DarkTextSecondary,
    outline = Color(0xFF374151),
)

// Android res/values/colors.xml（ライト）に合わせたテーマ
private val LightPrimary = Color(0xFF38BDF8)
private val LightOnPrimary = Color(0xFF082F49)
private val LightPrimaryContainer = Color(0xFFE6F7FF)
private val LightOnPrimaryContainer = Color(0xFF0C4A6E)
private val LightTextPrimary = Color(0xFF111827)
private val LightTextSecondary = Color(0xFF4B5563)
private val LightBgChat = Color(0xFFF5F6F8)
private val LightSurfaceCard = Color(0xFFFFFFFF)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightTextSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightPrimaryContainer,
    onSecondaryContainer = LightOnPrimaryContainer,
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    background = LightBgChat,
    onBackground = LightTextPrimary,
    surface = LightSurfaceCard,
    onSurface = LightTextPrimary,
    surfaceVariant = Color(0xFFF5F6F8),
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFB6BDC8),
)
