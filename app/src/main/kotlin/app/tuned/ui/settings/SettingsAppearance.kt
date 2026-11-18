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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.data.Accent
import app.tuned.data.AppSettings
import app.tuned.data.ThemeMode
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
        AccentRow(settings.accent) { a -> vm.update { it.copy(accent = a) } }
    }
    SwitchRow(
        stringResource(R.string.settings_pure_black),
        settings.pureBlack,
        stringResource(R.string.settings_pure_black_summary),
    ) { on ->
        vm.update { it.copy(pureBlack = on) }
    }
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

private fun accentLabel(accent: Accent): Int = when (accent) {
    Accent.VIOLET -> R.string.accent_violet
    Accent.BLUE -> R.string.accent_blue
    Accent.TEAL -> R.string.accent_teal
    Accent.GREEN -> R.string.accent_green
    Accent.ORANGE -> R.string.accent_orange
    Accent.PINK -> R.string.accent_pink
    Accent.RED -> R.string.accent_red
}
