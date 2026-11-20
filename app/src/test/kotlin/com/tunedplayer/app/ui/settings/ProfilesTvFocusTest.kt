package com.tunedplayer.app.ui.settings

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a TV, "Who's watching?" opens with the remote on the first profile, so OK alone picks it (Melvin, 2026-11-20:
 * "it should auto highlight the first entry so we only need to click ok on the remote"). The profile in use is the
 * second here, so the test shows it is the first entry, not the current one, that gets focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ProfilesTvFocusTest {
    @get:Rule val compose = createComposeRule()

    private val alex = ProfileEntity(id = 1, name = "Alex", colour = 0, kids = false, pinHash = null, createdAt = 0)
    private val sam = ProfileEntity(id = 2, name = "Sam", colour = 1, kids = false, pinHash = null, createdAt = 0)

    private fun picker() {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                WelcomeScaffold { _ -> ProfileRow(listOf(alex, sam), currentId = sam.id, managing = false, onPick = {}) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun firstProfileHasFocusOnATv() {
        picker()
        compose.onNodeWithText("Alex").assertIsFocused()
        compose.onNodeWithText("Sam").assertIsNotFocused()
    }

    /** A phone is touched, not steered, so nothing is focused there. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun nothingIsFocusedOnAPhone() {
        picker()
        compose.onNodeWithText("Alex").assertIsNotFocused()
        compose.onNodeWithText("Sam").assertIsNotFocused()
    }
}
