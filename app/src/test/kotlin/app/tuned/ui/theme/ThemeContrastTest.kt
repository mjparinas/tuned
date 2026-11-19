package app.tuned.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.tuned.data.Accent
import java.util.Locale
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Words and icons drawn in a colour of the theme stay readable on every background the app puts them on, in light, dark
 * and pure black, with every accent (Melvin, 2026-11-19: deeper status colours on light backgrounds, from the Horizon
 * Maps review). WCAG AA: 4.5:1 for text. A new colour or accent that falls short fails here before anyone squints at it.
 */
class ThemeContrastTest {
    private val schemes =
        Accent.entries.flatMap { accent ->
            listOf(
                "light $accent" to tunedScheme(dark = false, accent = accent, pureBlack = false),
                "dark $accent" to tunedScheme(dark = true, accent = accent, pureBlack = false),
                "pure black $accent" to tunedScheme(dark = true, accent = accent, pureBlack = true),
            )
        }

    private fun ColorScheme.backgrounds() = mapOf(
        "background" to background,
        "surface" to surface,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
    )

    private fun ColorScheme.textColours() = mapOf(
        "primary" to primary,
        "secondary" to secondary,
        "tertiary" to tertiary,
        "error" to error,
        "onSurface" to onSurface,
        "onSurfaceVariant" to onSurfaceVariant,
    )

    @Test
    fun themeColoursReadOnEveryBackground() {
        val failures =
            schemes.flatMap { (name, scheme) ->
                scheme.textColours().flatMap { (text, fg) ->
                    scheme.backgrounds().mapNotNull { (bg, colour) ->
                        val ratio = contrast(fg, colour)
                        "$name: $text on $bg is ${ratio.twoPlaces()}:1".takeIf { ratio < AA }
                    }
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun wordsOnFilledColoursRead() {
        val failures =
            schemes.flatMap { (name, s) ->
                listOf(
                    "onPrimary on primary" to contrast(s.onPrimary, s.primary),
                    "onPrimaryContainer on primaryContainer" to contrast(s.onPrimaryContainer, s.primaryContainer),
                    "onSecondaryContainer on secondaryContainer" to contrast(s.onSecondaryContainer, s.secondaryContainer),
                ).mapNotNull { (what, ratio) -> "$name: $what is ${ratio.twoPlaces()}:1".takeIf { ratio < AA } }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun Double.twoPlaces(): String = String.format(Locale.ROOT, "%.2f", this)

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG relative luminance, worked out here so the test doesn't lean on the code it checks. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private companion object {
        const val AA = 4.5
    }
}
