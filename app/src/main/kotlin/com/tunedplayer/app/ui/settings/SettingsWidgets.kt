package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunedplayer.app.R
import com.tunedplayer.app.ui.common.StartFocus
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.focusWithinRing
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.common.startFocus
import kotlin.math.roundToInt

/**
 * The outline round a Settings row while a TV remote is on it (Melvin, 2026-11-21: Settings "can be appropriately
 * adjusted using a remote control"). Material's own focus shade is too faint to see from the sofa; touch never focuses a
 * row, so phones look the same.
 */
internal val RowFocusShape = RoundedCornerShape(12.dp)

/**
 * On a TV, where a page of Settings puts the remote when it opens: the first row that can take it, so OK alone works
 * and the first arrow moves from somewhere visible (the screens that open full screen provide it; null elsewhere).
 */
internal val LocalRowStartFocus = staticCompositionLocalOf<StartFocus?> { null }

/** Gives a row the page's start focus ([LocalRowStartFocus]), if the page has one and no row above took it. */
private fun Modifier.rowStartFocus(start: StartFocus?): Modifier = if (start == null) this else startFocus(start)

@Composable
fun SectionHeader(text: String) {
    if (searching()) return
    // Small capitals over each card, as in Horizon Maps' settings (Melvin, 2026-11-19). The screen reader still gets the
    // words as written, and each header is a heading, so it can jump from section to section.
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .outsideCards()
                .padding(horizontal = if (LocalInCards.current) 32.dp else 16.dp)
                .padding(top = 20.dp, bottom = 8.dp)
                .semantics {
                    heading()
                    contentDescription = text
                },
    )
}

@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    if (searchHides(title, summary)) return
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .rowStartFocus(LocalRowStartFocus.current)
                        .focusRing(RowFocusShape, MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/**
 * A small spinner at the end of a row while what it started runs (a backup, a refresh), beside a summary that says the
 * same in words, so a tap is never met with nothing (Melvin, 2026-11-21).
 */
@Composable
fun RowSpinner() {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    summary: String? = null,
    onChange: (Boolean) -> Unit,
) {
    if (searchHides(title, summary)) return
    Row(
        Modifier
            .fillMaxWidth()
            .rowStartFocus(LocalRowStartFocus.current)
            .focusRing(RowFocusShape, MaterialTheme.colorScheme.primary)
            // Toggleable, not selectable: a screen reader then says "on" or "off" rather than "selected".
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting with a fixed set of choices: shows the current one, opens a dialog to pick. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title = title, summary = label(selected), onClick = { open = true })
    if (open) {
        TunedDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                // On a TV the remote starts on the choice in use, so OK alone keeps it and an arrow moves from there.
                val start = rememberStartFocus()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (option == selected) Modifier.startFocus(start) else Modifier)
                                .focusRing(RowFocusShape, MaterialTheme.colorScheme.primary)
                                .selectable(selected = option == selected, role = Role.RadioButton, onClick = {
                                    onSelect(option)
                                    open = false
                                })
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * A slider that writes only when the finger lifts, so dragging doesn't hammer DataStore.
 *
 * With a TV remote (or a keyboard), up and down leave it for the row above or below, as on every other row, and left
 * and right move it a step and save (Melvin, 2026-11-21: "up and down would take me to the different options within
 * captions, and left and right would adjust the size"). Material's slider takes all four arrows, so up and down
 * changed the size and the rows below it couldn't be reached.
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    if (searchHides(title)) return
    var current by remember(value) { mutableFloatStateOf(value) }
    val focus = LocalFocusManager.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        Modifier
            .fillMaxWidth()
            .focusWithinRing(RowFocusShape, MaterialTheme.colorScheme.primary)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current) },
            valueRange = range,
            steps = steps,
            modifier =
                Modifier
                    .rowStartFocus(LocalRowStartFocus.current)
                    .semantics { contentDescription = title }
                    .remoteSlider(
                        onLeave = { focus.moveFocus(it) },
                        onStep = { more ->
                            val next = sliderStep(current, range, steps, more = more != rtl)
                            if (next != current) {
                                current = next
                                onChange(next)
                            }
                        },
                    ),
        )
    }
}

/**
 * A remote's arrows on a slider: up and down [onLeave] it, left and right [onStep] it (true for right). Both the press
 * and the release are kept from Material's slider, which would otherwise move on up and down too.
 */
private fun Modifier.remoteSlider(
    onLeave: (FocusDirection) -> Unit,
    onStep: (more: Boolean) -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val press = event.type == KeyEventType.KeyDown
    when (event.key) {
        Key.DirectionUp -> if (press) onLeave(FocusDirection.Up)
        Key.DirectionDown -> if (press) onLeave(FocusDirection.Down)
        Key.DirectionLeft -> if (press) onStep(false)
        Key.DirectionRight -> if (press) onStep(true)
        else -> return@onPreviewKeyEvent false
    }
    true
}

/**
 * Where one press of left or right moves a slider: the next of its [steps] stops (or a twentieth of the range when it
 * has none), never past either end. A value between stops goes to the nearest one first.
 */
internal fun sliderStep(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    more: Boolean,
): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val stops = if (steps > 0) steps + 1 else FREE_SLIDER_STOPS
    val step = span / stops
    val index = ((value - range.start) / step).roundToInt() + if (more) 1 else -1
    return (range.start + index.coerceIn(0, stops) * step).coerceIn(range.start, range.endInclusive)
}

/** How many presses take a slider without steps from one end to the other. */
private const val FREE_SLIDER_STOPS = 20

/** Caption colours: the FCC-style palette TVs use, so every choice reads well. */
val captionPalette =
    listOf(
        0xFFFFFFFF.toInt(),
        0xFFFFFF00.toInt(),
        0xFF00FFFF.toInt(),
        0xFF00FF00.toInt(),
        0xFFFF00FF.toInt(),
        0xFFFF0000.toInt(),
        0xFF0000FF.toInt(),
        0xFF000000.toInt(),
    )

/** A caption colour's name, for screen readers; one outside the palette is read as its hex code. */
@Composable
private fun colourName(colour: Int): String = when (colour or 0xFF000000.toInt()) {
    0xFFFFFFFF.toInt() -> stringResource(R.string.colour_white)
    0xFFFFFF00.toInt() -> stringResource(R.string.colour_yellow)
    0xFF00FFFF.toInt() -> stringResource(R.string.colour_cyan)
    0xFF00FF00.toInt() -> stringResource(R.string.colour_green)
    0xFFFF00FF.toInt() -> stringResource(R.string.colour_magenta)
    0xFFFF0000.toInt() -> stringResource(R.string.colour_red)
    0xFF0000FF.toInt() -> stringResource(R.string.colour_blue)
    0xFF000000.toInt() -> stringResource(R.string.colour_black)
    else -> stringResource(R.string.colour_custom, "#%06X".format(colour and 0xFFFFFF))
}

@Composable
fun ColourRow(
    title: String,
    selected: Int,
    colours: List<Int> = captionPalette,
    onSelect: (Int) -> Unit,
) {
    if (searchHides(title)) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            colours.forEach { c ->
                val on = (c or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
                val name = colourName(c)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (on) 3.dp else 1.dp,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        // White (or black in light mode) rather than the accent, which already marks the colour chosen.
                        .focusRing(CircleShape, MaterialTheme.colorScheme.onSurface)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(c) })
                        .semantics { contentDescription = name },
                )
            }
        }
    }
}

@Composable
fun TextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
