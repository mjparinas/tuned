package com.tunedplayer.app.ui.settings

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import com.tunedplayer.app.data.Accent
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * "Who's watching?" text must be readable (WCAG AA, 4.5:1) on its backdrop in every theme and accent, including
 * where the accent's glow is strongest. It once fell back to black on the dark theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfilesContrastTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickerTextIsReadableInEveryThemeAndAccent() {
        val cases = mutableListOf<Triple<String, Color, List<Color>>>()
        compose.setContent {
            listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
                listOf(false, true).forEach { pureBlack ->
                    Accent.entries.forEach { accent ->
                        TunedTheme(mode = mode, pureBlack = pureBlack, accent = accent) {
                            val scheme = MaterialTheme.colorScheme
                            WelcomeScaffold { _ ->
                                val text = LocalContentColor.current
                                val glow = scheme.primary.copy(alpha = 0.22f).compositeOver(scheme.background)
                                cases += Triple("$mode pureBlack=$pureBlack $accent", text, listOf(scheme.background, glow))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(cases.isNotEmpty())
        cases.forEach { (name, text, backgrounds) ->
            backgrounds.forEach { bg ->
                val ratio = contrast(text, bg)
                assertTrue(ratio >= 4.5, "$name: text contrast $ratio is below 4.5:1")
            }
        }
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
