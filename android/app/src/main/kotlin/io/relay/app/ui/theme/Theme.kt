package io.relay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Liquid Glass tokens, mirrored from /shared/design-tokens.json (single source
 * of truth — change that file first). Dark-first, with a light glass set for
 * Phase 2 so the material works in both themes while keeping text legible over
 * translucency.
 */
data class GlassColors(
    val backgroundBase: Color,
    val backgroundGradientTop: Color,
    val backgroundGradientBottom: Color,
    val fill: Color,
    val fillRaised: Color,
    val stroke: Color,
    val strokeHighlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentPressed: Color,
    val accentSubtle: Color,
    val error: Color,
    val errorSubtle: Color,
    val warning: Color,
    val onAccent: Color,
    val isDark: Boolean,
) {
    val radiusSm = 12.dp
    val radiusMd = 16.dp
    val radiusLg = 24.dp
}

private val DarkGlass = GlassColors(
    backgroundBase = Color(0xFF0A0C10),
    backgroundGradientTop = Color(0xFF12151C),
    backgroundGradientBottom = Color(0xFF08090C),
    fill = Color.White.copy(alpha = 0.06f),
    fillRaised = Color.White.copy(alpha = 0.09f),
    stroke = Color.White.copy(alpha = 0.12f),
    strokeHighlight = Color.White.copy(alpha = 0.22f),
    textPrimary = Color.White.copy(alpha = 0.96f),
    textSecondary = Color.White.copy(alpha = 0.62f),
    textTertiary = Color.White.copy(alpha = 0.38f),
    accent = Color(0xFF45D6B8),
    accentPressed = Color(0xFF33B99C),
    accentSubtle = Color(0x2945D6B8),
    error = Color(0xFFE5645F),
    errorSubtle = Color(0x29E5645F),
    warning = Color(0xFFE0A458),
    onAccent = Color(0xFF0A0C10),
    isDark = true,
)

private val LightGlass = GlassColors(
    backgroundBase = Color(0xFFEEF1F5),
    backgroundGradientTop = Color(0xFFF7F9FC),
    backgroundGradientBottom = Color(0xFFE4E8EE),
    fill = Color.White.copy(alpha = 0.55f),
    fillRaised = Color.White.copy(alpha = 0.72f),
    stroke = Color.Black.copy(alpha = 0.08f),
    strokeHighlight = Color.White.copy(alpha = 0.85f),
    textPrimary = Color(0xFF0C0E12).copy(alpha = 0.94f),
    textSecondary = Color(0xFF0C0E12).copy(alpha = 0.58f),
    textTertiary = Color(0xFF0C0E12).copy(alpha = 0.36f),
    accent = Color(0xFF17A98C),
    accentPressed = Color(0xFF12876F),
    accentSubtle = Color(0x2917A98C),
    error = Color(0xFFC7433E),
    errorSubtle = Color(0x29C7433E),
    warning = Color(0xFFB37417),
    onAccent = Color(0xFFFFFFFF),
    isDark = false,
)

val LocalGlass = staticCompositionLocalOf { DarkGlass }

private val glassTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    // pairing codes
    headlineMedium = TextStyle(
        fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp,
    ),
)

/** [themeMode] is "system" | "dark" | "light" (persisted in Settings). */
@Composable
fun RelayTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val glass = if (dark) DarkGlass else LightGlass
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = glass.accent,
            background = glass.backgroundBase,
            onBackground = glass.textPrimary,
            surface = glass.backgroundBase,
            onSurface = glass.textPrimary,
            error = glass.error,
        )
    } else {
        lightColorScheme(
            primary = glass.accent,
            background = glass.backgroundBase,
            onBackground = glass.textPrimary,
            surface = glass.backgroundBase,
            onSurface = glass.textPrimary,
            error = glass.error,
        )
    }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = colorScheme, typography = glassTypography, content = content)
    }
}
