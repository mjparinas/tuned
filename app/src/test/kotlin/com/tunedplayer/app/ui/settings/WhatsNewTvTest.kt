package com.tunedplayer.app.ui.settings

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.tunedplayer.app.core.ReleaseNotes
import com.tunedplayer.app.core.ReleaseNotes.Kind
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What's new on a TV is the whole screen, not a half-open bottom sheet with a drag handle a remote can't drag (Melvin,
 * 2026-11-21: "Dragging handles shouldn't really be on the TV app"), and opens with the remote on the first change so
 * Up and Down scroll through them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class WhatsNewTvTest {
    @get:Rule val compose = createComposeRule()

    private val changes =
        listOf(
            ReleaseNotes.Change(Kind.NEW, "Filters stay chosen"),
            ReleaseNotes.Change(Kind.FIXED, "The guide keeps its place"),
        )

    private fun open() {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) { WhatsNew(WhatsNewNotes.Loaded(changes), onRetry = {}, onDismiss = {}) }
        }
        compose.waitForIdle()
    }

    @Test
    fun noDragHandleOnATv() {
        open()
        assertEquals(0, compose.onAllNodesWithContentDescription("Drag handle", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun firstChangeHasFocusAndEveryChangeShows() {
        open()
        compose.onNodeWithText("Filters stay chosen").assertIsFocused()
        compose.onNodeWithText("The guide keeps its place").assertIsNotFocused()
    }

    /** A phone keeps the bottom sheet, with its handle. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneKeepsTheSheet() {
        open()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Drag handle", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
