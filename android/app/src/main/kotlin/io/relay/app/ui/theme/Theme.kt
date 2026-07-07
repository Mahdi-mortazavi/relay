package io.relay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Liquid Glass tokens, mirrored from /shared/design-tokens.json (single
 * source of truth — change that file first). Phase 1 ships the dark set;
 * full dark/light theming lands in Phase 2.
 */
object Glass {
    val backgroundBase = Color(0xFF0A0C10)
    val backgroundGradientTop = Color(0xFF12151C)
    val backgroundGradientBottom = Color(0xFF08090C)
    val fill = Color.White.copy(alpha = 0.06f)
    val fillRaised = Color.White.copy(alpha = 0.09f)
    val stroke = Color.White.copy(alpha = 0.12f)
    val strokeHighlight = Color.White.copy(alpha = 0.22f)
    val textPrimary = Color.White.copy(alpha = 0.96f)
    val textSecondary = Color.White.copy(alpha = 0.62f)
    val textTertiary = Color.White.copy(alpha = 0.38f)
    val accent = Color(0xFF45D6B8)
    val accentPressed = Color(0xFF33B99C)
    val accentSubtle = Color(0x2945D6B8)
    val error = Color(0xFFE5645F)
    val errorSubtle = Color(0x29E5645F)
    val warning = Color(0xFFE0A458)

    val radiusSm = 12.dp
    val radiusMd = 16.dp
    val radiusLg = 24.dp
}

val LocalGlass = staticCompositionLocalOf { Glass }

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

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    // Dark-first by design; system light theme follows in Phase 2.
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Glass.accent,
            background = Glass.backgroundBase,
            onBackground = Glass.textPrimary,
            surface = Glass.backgroundBase,
            onSurface = Glass.textPrimary,
            error = Glass.error,
        ),
        typography = glassTypography,
        content = content,
    )
}
