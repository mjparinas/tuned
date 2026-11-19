package app.tuned.ui.settings

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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tuned.R
import app.tuned.ui.common.TunedDialog

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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
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

/** A slider that writes only when the finger lifts, so dragging doesn't hammer DataStore. */
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
        )
    }
}

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
                        ).selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(c) })
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
