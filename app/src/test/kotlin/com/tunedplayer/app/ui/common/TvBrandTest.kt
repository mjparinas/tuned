package com.tunedplayer.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On a TV, Tuned's mark leads a title bar in the upper left, but only once a screen (Melvin, 2026-11-21), and the
 * system theme there is dark, while an explicit Light still gives light.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w853dp-h480dp-land-television-xhdpi")
class TvBrandTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTitleBarOnATvShowsTheMark() {
        compose.setContent { MaterialTheme { BackTopBar("Settings", onBack = {}) } }
        assertEquals(1, compose.onAllNodesWithText("Tuned").fetchSemanticsNodes().size)
    }

    @Test
    fun aTitleBarUnderTheRailLeavesItOut() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTvBrandShown provides true) { BackTopBar("Downloads", onBack = {}) }
            }
        }
        assertEquals(0, compose.onAllNodesWithText("Tuned").fetchSemanticsNodes().size)
    }

    @Test
    fun systemThemeIsDarkOnATvAndLightStaysLight() {
        var mode by mutableStateOf(ThemeMode.SYSTEM)
        var background = 0f
        compose.setContent { TunedTheme(mode = mode) { Probe { background = it } } }
        compose.waitForIdle()
        assertTrue(background < 0.2f, "the system default on a TV is dark")
        mode = ThemeMode.LIGHT
        compose.waitForIdle()
        assertFalse(background < 0.2f, "Light chosen in Appearance stays light")
    }

    @Composable
    private fun Probe(onBackground: (Float) -> Unit) {
        onBackground(MaterialTheme.colorScheme.background.luminance())
    }
}

/** Off a TV nothing changes: no mark in the bar, and the system theme follows the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-notnight-xxhdpi")
class TvBrandPhoneTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aPhoneTitleBarHasNoMark() {
        compose.setContent { MaterialTheme { BackTopBar("Settings", onBack = {}) } }
        assertEquals(0, compose.onAllNodesWithText("Tuned").fetchSemanticsNodes().size)
    }

    @Test
    fun aPhoneFollowsTheSystemTheme() {
        var background = 0f
        compose.setContent { TunedTheme(mode = ThemeMode.SYSTEM) { background = MaterialTheme.colorScheme.background.luminance() } }
        compose.waitForIdle()
        assertFalse(background < 0.2f)
    }
}
