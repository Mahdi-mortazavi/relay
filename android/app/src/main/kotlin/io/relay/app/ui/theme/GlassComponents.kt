package io.relay.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The glass material: a translucent layered surface with a light-from-above
 * gradient fill and a specular top edge. Elevation is communicated by
 * translucency, not drop shadows (see docs/design). The layered-translucency
 * fallback used here must never appear broken; backdrop blur is layered on where
 * the platform supports it.
 */
fun Modifier.glassPanel(radius: Dp = 24.dp, raised: Boolean = false): Modifier = composed {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(radius)
    val fill = if (raised) glass.fillRaised else glass.fill
    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(fill.copy(alpha = (fill.alpha + 0.03f).coerceAtMost(1f)), fill),
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(glass.strokeHighlight, glass.stroke, glass.stroke),
            ),
            shape = shape,
        )
}

/** The quiet backdrop the glass refracts: a deep neutral vertical gradient with a faint accent bloom. */
@Composable
fun RelayBackground(content: @Composable () -> Unit) {
    val glass = LocalGlass.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        glass.backgroundGradientTop,
                        glass.backgroundBase,
                        glass.backgroundGradientBottom,
                    )
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(glass.accent.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(0.5f, 0f),
                    radius = 1200f,
                )
            ),
    ) {
        content()
    }
}
