package io.relay.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

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
    // ~3.4:1 at 0.38 over the dark glass; 0.60 reaches ~5.4:1.
    textTertiary = Color.White.copy(alpha = 0.60f),
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
    // 0.36 alpha over the light glass panel composited to ~2.4:1 — below the
    // 4.5:1 AA floor for the 12sp labels this token carries (every caption,
    // hint and log line). 0.62 lands at ~5.0:1 and still reads as tertiary.
    textTertiary = Color(0xFF0C0E12).copy(alpha = 0.62f),
    // White on #17A98C is ~3.0:1 — the "Start Sharing" and "Try Again" labels
    // failed AA in light mode (the dark pairing is fine at ~10.6:1). Darkening
    // the accent to #0F7A63 puts white at ~5.3:1 while keeping the same hue.
    accent = Color(0xFF0F7A63),
    accentPressed = Color(0xFF0B5F4D),
    accentSubtle = Color(0x290F7A63),
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

    // enableEdgeToEdge() resolves the system-bar icon colours once, from the
    // *system* uiMode. Relay lets the user override the theme independently, so
    // choosing Light under a dark system left white status-bar icons on a
    // near-white background: an invisible clock, battery and signal. Follow the
    // resolved theme instead.
    val view = LocalView.current
    if (!view.isInEditMode) {
        (view.context as? Activity)?.window?.let { window ->
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

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
