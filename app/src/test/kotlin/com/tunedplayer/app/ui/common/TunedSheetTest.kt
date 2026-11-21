package com.tunedplayer.app.ui.common

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A panel that slides up on a phone (Live TV's Filters) fills the screen on a TV, with no drag handle, and the remote
 * starts on its first option (Melvin, 2026-11-21: "I can't click to any of these selections in the filters").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TunedSheetTest {
    @get:Rule val compose = createComposeRule()

    private fun open() {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                TunedSheet(onDismissRequest = {}) {
                    val start = rememberStartFocus()
                    FilterChip(selected = false, onClick = {}, label = { Text("Sports") }, modifier = Modifier.startFocus(start))
                    FilterChip(selected = false, onClick = {}, label = { Text("News") })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun handles() = compose.onAllNodesWithContentDescription("Drag handle", substring = true).fetchSemanticsNodes().size

    @Test
    fun fullScreenOnATvWithTheFirstOptionFocused() {
        open()
        assertEquals(0, handles())
        compose.onNodeWithText("Sports").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("News").assertIsDisplayed().assertIsNotFocused()
    }

    /** A phone keeps Material's bottom sheet, handle and all. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneKeepsTheSheet() {
        open()
        compose.waitUntil(5_000) { handles() > 0 }
    }
}
