package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.data.db.FavouriteRow
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings with a TV remote's six buttons (Melvin, 2026-11-21: "you basically have up, down, left, right, OK, and
 * back"): up and down go from row to row, sliders included, left and right move a slider, a page and a choice open on
 * the likely row, and reordering favourites keeps the remote on the channel being moved.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SettingsTvRemoteTest {
    @get:Rule val compose = createComposeRule()

    private val saved = mutableListOf<Float>()

    private fun captionRows() {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                Column {
                    SwitchRow("Show captions", checked = true) {}
                    SliderRow("Text size", 1f, 0.5f..2.5f, steps = 7, label = { "$it" }) { saved += it }
                    SwitchRow("Bold", checked = false) {}
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The bug Melvin found: down on the text size changed the size instead of going to the next row. */
    @Test
    fun downAndUpLeaveASlider() {
        captionRows()
        compose.onNodeWithContentDescription("Text size").requestFocus()
        compose.waitForIdle()
        press(Key.DirectionDown)
        compose.onNodeWithText("Bold").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithContentDescription("Text size").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithText("Show captions").assertIsFocused()
        assertTrue(saved.isEmpty(), "Up and down changed the size: $saved")
    }

    @Test
    fun leftAndRightMoveASliderAStepAndSave() {
        captionRows()
        compose.onNodeWithContentDescription("Text size").requestFocus()
        compose.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertEquals(listOf(1.25f, 1.5f, 1.25f), saved)
        compose.onNodeWithContentDescription("Text size").assertIsFocused()
    }

    @Test
    fun aPageStartsOnItsFirstRowThatTakesFocus() {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalRowStartFocus provides rememberStartFocus()) {
                    Column {
                        SettingRow("Version", "1.0")
                        SwitchRow("Resume", checked = true) {}
                        SwitchRow("Autoplay", checked = true) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Resume").assertIsFocused()
        compose.onNodeWithText("Autoplay").assertIsNotFocused()
    }

    /** The choices as a choice's dialog shows them; Robolectric's dialog windows never take focus, so they're drawn here. */
    @Test
    fun aChoiceOpensOnTheOneInUse() {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                ChoiceOptions(listOf("System", "Light", "Dark"), "Dark", label = { it }) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Dark").assertIsFocused()
        compose.onNodeWithText("Light").assertIsNotFocused()
    }

    @Test
    fun reorderingKeepsTheRemoteOnTheChannelMoved() {
        val list = mutableStateListOf(fav(1, "Alpha"), fav(2, "Bravo"), fav(3, "Charlie"))
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                FavouriteOrderList(list, onMove = { from, by -> list.add(from + by, list.removeAt(from)) })
            }
        }
        compose.waitForIdle()
        // Alpha is first, so its up arrow is off and the remote starts on its down arrow.
        compose.onNodeWithContentDescription("Move Alpha down").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("Bravo", "Alpha", "Charlie"), list.map { it.name })
        compose.onNodeWithContentDescription("Move Alpha down").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), list.map { it.name })
        // At the bottom the down arrow turns off, so the remote goes to the up arrow beside it.
        compose.onNodeWithContentDescription("Move Alpha up").assertIsFocused()
    }

    private fun fav(
        id: Long,
        name: String,
    ) = FavouriteRow(id = id, name = name, logo = null, tvgId = null)
}
