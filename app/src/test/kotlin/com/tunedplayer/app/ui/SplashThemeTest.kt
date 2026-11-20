package com.tunedplayer.app.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertTrue

/**
 * The splash drew the mark on the light theme's near-white background, so its white front button vanished
 * (Melvin, 2026-11-20). The splash background must stand out from white in light and dark mode, on phones and TVs,
 * whether or not the system draws the icon's background circle.
 */
@RunWith(RobolectricTestRunner::class)
class SplashThemeTest {
    @Test
    @Config(sdk = [35], qualifiers = "notnight")
    fun theMarkShowsOnTheLightSplash() = assertWhiteStandsOut()

    @Test
    @Config(sdk = [35], qualifiers = "night")
    fun theMarkShowsOnTheDarkSplash() = assertWhiteStandsOut()

    @Test
    @Config(sdk = [35], qualifiers = "television-notnight")
    fun theMarkShowsOnTheTvSplash() = assertWhiteStandsOut()

    @Test
    @Config(sdk = [30], qualifiers = "notnight")
    fun theMarkShowsOnTheSplashBeforeAndroid12() = assertWhiteStandsOut()

    private fun assertWhiteStandsOut() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Tuned_Starting)

        // The splash library's attribute, looked up by name like the player's theme test does.
        @Suppress("DiscouragedApi")
        val attr = context.resources.getIdentifier("windowSplashScreenBackground", "attr", context.packageName)
        assertTrue(attr != 0)
        val value = TypedValue()
        assertTrue(context.theme.resolveAttribute(attr, value, true), "the splash theme has no background")
        val ratio = contrast(Color.WHITE, value.data)
        assertTrue(ratio >= MIN_CONTRAST, "white on the splash background is only $ratio:1")
    }

    private fun contrast(
        a: Int,
        b: Int,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }

    private companion object {
        // WCAG's minimum for graphics; Tuned ink against white is far above it.
        const val MIN_CONTRAST = 3.0
    }
}
