package com.tunedplayer.app.ui.common

import android.view.KeyEvent.KEYCODE_BUTTON_A
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.diagnostics.attempt

/**
 * Makes a text field work with a TV remote (Melvin, 2026-11-21: on TV search "it asks me to use my keyboard. I don't
 * know how to do that ... there is a speaker icon. When I click right ... I can't access that speaker icon"):
 *
 * - OK (the centre of the D-pad, Enter, or a game pad's A) opens Tuned's D-pad keyboard every time, never the system
 *   input method that asks to type on a phone. Compose only opens an IME for the centre key from a physical D-pad, so
 *   Enter, A and the phone remote app's keys did nothing.
 * - Left and Right leave the field for the button beside it, such as the mic, instead of moving a cursor no one sees.
 *   The keyboard has its own arrows for that.
 * - A ring in the theme's colour shows while the remote is on it.
 *
 * Put it first in the field's modifier. On phones and tablets it changes nothing.
 */
fun Modifier.remoteTextInput(
    shape: Shape,
    text: String,
    onChange: (String) -> Unit,
): Modifier = composed {
    if (!isTelevision()) return@composed this
    val focus = LocalFocusManager.current
    val openKeyboard = rememberTvKeyboardOpener(text, onChange)
    val ring = MaterialTheme.colorScheme.primary
    this
        .focusRing(shape, ring)
        .onPreviewKeyEvent { event -> RemoteKeys.handle(event, openKeyboard, focus) }
}

/** What [remoteTextInput] does with each key, apart from Compose, so a test can drive it without a TV. */
internal object RemoteKeys {
    private val ok = setOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER, KEYCODE_BUTTON_A)

    fun handle(
        event: KeyEvent,
        openKeyboard: () -> Unit,
        focus: FocusManager,
    ): Boolean {
        val code = event.key.nativeKeyCode
        val down = event.type == KeyEventType.KeyDown
        return when {
            code in ok -> {
                // Opened on release, as a button acts, and the press is taken too so nothing else acts on it.
                if (event.type == KeyEventType.KeyUp) openKeyboard()
                true
            }

            code == KEYCODE_DPAD_RIGHT && down -> {
                focus.moveFocus(FocusDirection.Right)
            }

            code == KEYCODE_DPAD_LEFT && down -> {
                focus.moveFocus(FocusDirection.Left)
            }

            else -> {
                false
            }
        }
    }
}

/**
 * The buttons that belong to a search box: Clear while there is text, and the mic. On a phone they sit inside the box
 * as its trailing icon (pass it as `trailingIcon`). On a TV the remote can only reach what is outside the box, so the mic
 * goes before it with [SearchBoxVoiceBefore] and Clear after it with [SearchBoxButtonsBeside], as on other TV apps
 * (Melvin, 2026-11-21: "in many apps the mic is on the left and the search box is on the right"). The mic stays while
 * there is text, so focus on it is never lost when voice fills the box.
 */
@Composable
fun searchBoxTrailing(
    text: String,
    onChange: (String) -> Unit,
    voice: Boolean = true,
): (@Composable () -> Unit)? {
    if (isTelevision()) return null
    return {
        if (text.isNotEmpty()) {
            ClearButton(onChange)
        } else if (voice) {
            VoiceSearchButton(onResult = onChange)
        }
    }
}

/**
 * On a TV, the mic before the search box, with a focus ring; nothing on a phone, or where there is no speech
 * recogniser. [modifier] can carry a focus requester, so a screen can open with the remote on the mic.
 */
@Composable
fun SearchBoxVoiceBefore(
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isTelevision()) return
    VoiceSearchButton(onResult = onChange, modifier = modifier.remoteButtonRing())
}

/**
 * Where the remote starts on a search screen: on a TV the mic, as on other TV apps, so OK alone starts talking (Melvin,
 * 2026-11-21: "on android tv, the mic should be selected by default"); on a TV without a speech recogniser, and on a
 * phone, the box. Nothing when [enabled] is false (a search that opened with words already in it).
 */
@Composable
fun SearchStartFocus(
    box: FocusRequester,
    mic: FocusRequester,
    enabled: Boolean = true,
) {
    val micFirst = isTelevision() && rememberVoiceSearchAvailable()
    LaunchedEffect(Unit) {
        if (enabled) attempt("SearchFocus", "Couldn't focus search") { (if (micFirst) mic else box).requestFocus() }
    }
}

/** On a TV, Clear after the search box while there is text, with a focus ring; nothing on a phone. */
@Composable
fun SearchBoxButtonsBeside(
    text: String,
    onChange: (String) -> Unit,
) {
    if (!isTelevision()) return
    if (text.isNotEmpty()) ClearButton(onChange, Modifier.remoteButtonRing())
}

@Composable
private fun ClearButton(
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = { onChange("") }, modifier = modifier) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
    }
}

/** The ring a round icon button shows while a TV remote is on it. */
fun Modifier.remoteButtonRing(): Modifier = composed { focusRing(CircleShape, MaterialTheme.colorScheme.primary) }

/** The rounded box of the app's search bars. */
val SearchBoxShape = RoundedCornerShape(28.dp)
