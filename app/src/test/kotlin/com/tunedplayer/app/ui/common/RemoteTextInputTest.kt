package com.tunedplayer.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * A search box driven by a TV remote (Melvin, 2026-11-21: "there is a speaker icon. When I click right ... I can't
 * access that speaker icon"): the mic sits before the box and has the remote first, Right from the box reaches Clear, and OK opens the on-screen keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@OptIn(ExperimentalTestApi::class)
class RemoteTextInputTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun addRecogniser() {
        // A device with a speech recogniser, as on a Shield or any Google TV.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recogniser =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "com.google.android.katniss"
                        name = "Recogniser"
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), recogniser)
    }

    /** A search box laid out as the search screens do, opening with the remote where [SearchStartFocus] puts it. */
    private fun box(initial: String) {
        compose.setContent {
            MaterialTheme {
                var value by remember { mutableStateOf(initial) }
                val boxFocus = remember { FocusRequester() }
                val micFocus = remember { FocusRequester() }
                SearchStartFocus(box = boxFocus, mic = micFocus)
                Row {
                    SearchBoxVoiceBefore({ value = it }, Modifier.focusRequester(micFocus))
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = { Text("Search") },
                        trailingIcon = searchBoxTrailing(value, { value = it }),
                        singleLine = true,
                        modifier =
                            Modifier
                                .remoteTextInput(SearchBoxShape)
                                .weight(1f)
                                .testTag("box")
                                .focusRequester(boxFocus),
                    )
                    SearchBoxButtonsBeside(value, { value = it })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun mic() = compose.onNodeWithContentDescription(text(R.string.voice_search))

    @Test
    fun theMicIsLeftOfTheBoxAndHasFocusFirst() {
        addRecogniser()
        box("")
        mic().assertIsFocused()
        val micRight = mic().fetchSemanticsNode().boundsInRoot.right
        val boxLeft =
            compose
                .onNodeWithTag("box")
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue(micRight <= boxLeft, "mic ends at $micRight, box starts at $boxLeft")
        // Right goes into the box, and Left comes back to the mic.
        mic().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithTag("box").assertIsFocused()
        compose.onNodeWithTag("box").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        mic().assertIsFocused()
    }

    @Test
    fun withoutVoiceSearchTheBoxHasFocusFirst() {
        box("")
        mic().assertDoesNotExist()
        compose.onNodeWithTag("box").assertIsFocused()
    }

    @Test
    fun rightFromTheBoxReachesClear() {
        box("hockey")
        compose.onNodeWithTag("box").assertIsFocused()
        compose.onNodeWithTag("box").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.search_clear)).assertIsFocused()
    }

    private class FakeKeyboard : SoftwareKeyboardController {
        var shown = 0

        override fun show() {
            shown++
        }

        override fun hide() = Unit
    }

    private class FakeFocus : FocusManager {
        val moves = mutableListOf<FocusDirection>()

        override fun clearFocus(force: Boolean) = Unit

        override fun moveFocus(focusDirection: FocusDirection): Boolean {
            moves += focusDirection
            return true
        }
    }

    private fun key(
        action: Int,
        code: Int,
    ) = ComposeKeyEvent(AndroidKeyEvent(action, code))

    @Test
    fun everyOkKeyOpensTheKeyboard() {
        val codes =
            listOf(
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                AndroidKeyEvent.KEYCODE_BUTTON_A,
            )
        for (code in codes) {
            val keyboard = FakeKeyboard()
            val focus = FakeFocus()
            assertTrue(RemoteKeys.handle(key(AndroidKeyEvent.ACTION_DOWN, code), keyboard, focus))
            assertEquals(0, keyboard.shown, "opens on release, not press ($code)")
            assertTrue(RemoteKeys.handle(key(AndroidKeyEvent.ACTION_UP, code), keyboard, focus))
            assertEquals(1, keyboard.shown, "keycode $code")
        }
    }

    @Test
    fun leftAndRightLeaveTheBox() {
        val focus = FakeFocus()
        RemoteKeys.handle(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT), FakeKeyboard(), focus)
        RemoteKeys.handle(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_LEFT), FakeKeyboard(), focus)
        assertEquals(listOf(FocusDirection.Right, FocusDirection.Left), focus.moves)
    }
}
