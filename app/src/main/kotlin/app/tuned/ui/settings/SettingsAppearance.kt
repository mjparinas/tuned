package app.tuned.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.container
import app.tuned.data.Accent
import app.tuned.data.AppSettings
import app.tuned.data.LighterMode
import app.tuned.data.ThemeMode
import app.tuned.diagnostics.Lighter
import app.tuned.ui.theme.swatch

@Composable
internal fun AppearancePage(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    ChoiceRow(
        stringResource(R.string.settings_theme),
        ThemeMode.entries,
        settings.theme,
        label = { stringResource(themeLabel(it)) },
    ) { t ->
        vm.update { it.copy(theme = t) }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SwitchRow(
            stringResource(R.string.settings_dynamic_colour),
            settings.dynamicColour,
            stringResource(R.string.settings_dynamic_colour_summary),
        ) { on -> vm.update { it.copy(dynamicColour = on) } }
    }
    if (!settings.dynamicColour || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        // The default accent is free; the others are Premium (docs/decisions/premium.md).
        val context = LocalContext.current
        AccentRow(settings.accent) { a ->
            if (a == Accent.VIOLET || context.container.premium.allow(context)) vm.update { it.copy(accent = a) }
        }
    }
    SwitchRow(
        stringResource(R.string.settings_pure_black),
        settings.pureBlack,
        stringResource(R.string.settings_pure_black_summary),
    ) { on ->
        vm.update { it.copy(pureBlack = on) }
    }
    // For weak TV boxes; Automatic turns it on for a low-memory device or one that keeps freezing (docs/decisions/speed.md).
    val lighterNow by Lighter.on.collectAsState()
    ChoiceRow(
        stringResource(R.string.settings_lighter),
        LighterMode.entries,
        settings.lighterMode,
        label = { stringResource(lighterLabel(it, lighterNow)) },
    ) { m ->
        vm.update { it.copy(lighterMode = m) }
    }
}

private fun lighterLabel(
    mode: LighterMode,
    onNow: Boolean,
): Int = when (mode) {
    LighterMode.AUTO -> if (onNow) R.string.lighter_auto_on else R.string.lighter_auto
    LighterMode.ON -> R.string.lighter_on
    LighterMode.OFF -> R.string.lighter_off
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/** Accent colour: a row of round swatches, the chosen one ticked. */
@Composable
private fun AccentRow(
    selected: Accent,
    onPick: (Accent) -> Unit,
) {
    if (searchHides(stringResource(R.string.settings_accent))) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Accent.entries.forEach { accent ->
                val name = stringResource(accentLabel(accent))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(accent.swatch())
                            .selectable(selected = accent == selected, role = Role.RadioButton, onClick = { onPick(accent) })
                            .semantics { contentDescription = name },
                ) {
                    if (accent == selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

internal fun accentLabel(accent: Accent): Int = when (accent) {
    Accent.VIOLET -> R.string.accent_violet
    Accent.BLUE -> R.string.accent_blue
    Accent.TEAL -> R.string.accent_teal
    Accent.GREEN -> R.string.accent_green
    Accent.ORANGE -> R.string.accent_orange
    Accent.PINK -> R.string.accent_pink
    Accent.RED -> R.string.accent_red
}
