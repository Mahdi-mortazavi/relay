package io.relay.app

import io.relay.app.ui.theme.DarkGlass
import io.relay.app.ui.theme.GlassColors
import io.relay.app.ui.theme.LightGlass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The theme is the token file, and the token file is legible.
 *
 * DESIGN.md recorded the gap this closes: nothing asserted `/shared` against
 * either client, and the accent drifted to three values at once before anyone
 * noticed by reading. Two checks, both against `shared/design-tokens.json`:
 *
 * 1. every colour the Compose theme defines equals its token, to within one
 *    8-bit step;
 * 2. every colour used for text clears WCAG AA (4.5:1) on the worst ground it
 *    can sit on, which is written into the token file's `$contrast` note.
 */
class DesignTokensTest {

    private val tokens = SharedContracts.json("design-tokens.json").jsonObject
        .getValue("color").jsonObject

    @Test
    fun `the dark theme is the dark tokens`() = assertMatches("dark", DarkGlass)

    @Test
    fun `the light theme is the light tokens`() = assertMatches("light", LightGlass)

    @Test
    fun `every text colour clears AA on its worst ground`() {
        val failures = mutableListOf<String>()
        for (theme in listOf("dark", "light")) {
            val t = tokens.getValue(theme).jsonObject
            // Dark: the lightest thing text sits on is a raised panel with its
            // highlight (9% + 3% white) over the top of the gradient. Light: the
            // darkest is the bottom of the gradient itself, under no panel.
            val ground = if (theme == "dark") {
                over(parse("rgba(255,255,255,0.12)"), parse(t.str("backgroundGradientTop")))
            } else {
                parse(t.str("backgroundGradientBottom"))
            }
            for (name in TEXT_TOKENS) {
                val ratio = contrast(over(parse(t.str(name)), ground), ground)
                if (ratio < 4.5) failures += "$theme.$name = ${"%.2f".format(ratio)}:1"
            }
        }
        assertTrue("Below 4.5:1 — " + failures.joinToString(), failures.isEmpty())
    }

    private fun assertMatches(theme: String, glass: GlassColors) {
        val t = tokens.getValue(theme).jsonObject
        val pairs = mapOf(
            "backgroundBase" to glass.backgroundBase,
            "backgroundGradientTop" to glass.backgroundGradientTop,
            "backgroundGradientBottom" to glass.backgroundGradientBottom,
            "glassFill" to glass.fill,
            "glassFillRaised" to glass.fillRaised,
            "glassStroke" to glass.stroke,
            "glassStrokeHighlight" to glass.strokeHighlight,
            "textPrimary" to glass.textPrimary,
            "textSecondary" to glass.textSecondary,
            "textTertiary" to glass.textTertiary,
            "accent" to glass.accent,
            "accentPressed" to glass.accentPressed,
            "accentSubtle" to glass.accentSubtle,
            "error" to glass.error,
            "errorSubtle" to glass.errorSubtle,
            "warning" to glass.warning,
        )
        val drift = pairs.mapNotNull { (name, actual) ->
            val want = parse(t.str(name))
            val got = Rgba(actual.red * 255, actual.green * 255, actual.blue * 255, actual.alpha)
            val off = abs(want.r - got.r) > 1.01 || abs(want.g - got.g) > 1.01 ||
                abs(want.b - got.b) > 1.01 || abs(want.a - got.a) > 0.01
            if (off) "$theme.$name: token $want, theme $got" else null
        }
        assertTrue("Theme drifted from /shared:\n" + drift.joinToString("\n"), drift.isEmpty())
    }

    private data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
        constructor(r: Float, g: Float, b: Float, a: Float) :
            this(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble())
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun parse(text: String): Rgba {
        val s = text.trim()
        if (s.startsWith("#")) {
            val v = s.drop(1).toLong(16)
            return Rgba(((v shr 16) and 0xFF).toDouble(), ((v shr 8) and 0xFF).toDouble(), (v and 0xFF).toDouble())
        }
        val parts = s.substringAfter("(").substringBefore(")").split(",").map { it.trim().toDouble() }
        return Rgba(parts[0], parts[1], parts[2], parts.getOrElse(3) { 1.0 })
    }

    /** [fg] composited over an opaque [bg]. */
    private fun over(fg: Rgba, bg: Rgba) = Rgba(
        fg.r * fg.a + bg.r * (1 - fg.a),
        fg.g * fg.a + bg.g * (1 - fg.a),
        fg.b * fg.a + bg.b * (1 - fg.a),
    )

    private fun luminance(c: Rgba): Double {
        fun channel(v: Double): Double {
            val s = v / 255
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
    }

    private fun contrast(a: Rgba, b: Rgba): Double {
        val (lo, hi) = listOf(luminance(a), luminance(b)).sorted()
        return (hi + 0.05) / (lo + 0.05)
    }

    private companion object {
        val TEXT_TOKENS = listOf(
            "textPrimary", "textSecondary", "textTertiary", "accent", "error", "warning",
        )
    }
}
