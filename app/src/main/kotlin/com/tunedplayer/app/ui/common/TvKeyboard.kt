package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.diagnostics.attempt

/** Tuned's D-pad keyboard, used on a TV instead of an input method that may only offer typing from a phone. */
private fun interface TvKeyboardController {
    fun show(
        text: String,
        onChange: (String) -> Unit,
    )
}

private val LocalTvKeyboard = compositionLocalOf<TvKeyboardController?> { null }

/** Provides the one keyboard above navigation, so opening it never adds another text box to the current screen. */
@Composable
fun TvKeyboardHost(content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<TvKeyboardRequest?>(null) }
    val controller = remember { TvKeyboardController { text, onChange -> request = TvKeyboardRequest(text, onChange) } }
    CompositionLocalProvider(LocalTvKeyboard provides controller) { content() }
    request?.let { current ->
        TvKeyboard(
            initial = current.text,
            onChange = current.onChange,
            onDismiss = { request = null },
        )
    }
}

@Composable
private fun TvKeyboard(
    initial: String,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val firstKey = remember { FocusRequester() }

    fun update(value: String) {
        text = value
        onChange(value)
    }
    LaunchedEffect(Unit) {
        attempt("TvKeyboard", "Couldn't focus the first key") { firstKey.requestFocus() }
    }
    TunedDialog(
        onDismissRequest = onDismiss,
        maxWidth = DialogWideMaxWidth,
        // The field's current text, so every search and follow box shares one keyboard without a second input.
        title = { Text(text.ifEmpty { "\u00A0" }, maxLines = 1, modifier = Modifier.testTag(TV_KEYBOARD_TITLE_TAG)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KEY_ROWS.forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { index, character ->
                            OutlinedButton(
                                onClick = { update(text + character) },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .then(if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstKey) else Modifier)
                                        .testTag("$TV_KEYBOARD_KEY_TAG$character"),
                            ) { Text(character.uppercase()) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { update(text.dropLast(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                    OutlinedButton(onClick = { update("") }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.search_clear))
                    }
                    OutlinedButton(onClick = { update("$text ") }, modifier = Modifier.weight(1f).widthIn(min = 120.dp)) {
                        Text("\u2423")
                    }
                    Button(onClick = onDismiss, modifier = Modifier.testTag(TV_KEYBOARD_DONE_TAG)) {
                        Text(stringResource(R.string.profiles_done))
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** Opens the host keyboard. Kept here so the modifier does not expose the host's implementation. */
@Composable
internal fun rememberTvKeyboardOpener(
    text: String,
    onChange: (String) -> Unit,
): () -> Unit {
    val keyboard = LocalTvKeyboard.current
    return remember(keyboard, text, onChange) { { keyboard?.show(text, onChange) } }
}

private data class TvKeyboardRequest(
    val text: String,
    val onChange: (String) -> Unit,
)

internal const val TV_KEYBOARD_DONE_TAG = "tv_keyboard_done"
internal const val TV_KEYBOARD_TITLE_TAG = "tv_keyboard_title"
internal const val TV_KEYBOARD_KEY_TAG = "tv_keyboard_key_"

private val KEY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890").map { it.map(Char::toString) }
