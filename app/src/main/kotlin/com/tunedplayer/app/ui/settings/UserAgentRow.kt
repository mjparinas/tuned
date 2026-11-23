package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.R
import com.tunedplayer.app.core.UserAgents
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.isTelevision

/**
 * Settings › About › User agent: a picklist of what common players and set-top boxes send, Tuned first and the
 * default, and Custom… for anything else ([UserAgents]). [stored] is the setting as saved (blank is Tuned's own).
 */
@Composable
internal fun UserAgentRow(
    stored: String,
    onChange: (String) -> Unit,
) {
    val preset = UserAgents.presetFor(stored)
    val tv = isTelevision()
    var picking by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.settings_user_agent),
        when {
            preset == UserAgents.TUNED -> stringResource(R.string.settings_user_agent_default)
            preset != null -> preset.label
            else -> stringResource(R.string.settings_user_agent_custom_summary, stored.trim())
        },
        onClick = { picking = true },
    )
    if (picking) {
        UserAgentPicker(
            selected = preset,
            // Custom… needs typing; on a TV the presets alone stay, and a custom value already set can only switch away.
            allowCustom = !tv,
            onPick = { picked ->
                picking = false
                if (picked == null) typing = true else onChange(picked.value)
            },
            onDismiss = { picking = false },
        )
    }
    if (typing && !tv) {
        CustomUserAgentDialog(initial = if (preset == null) stored.trim() else "", onDismiss = { typing = false }) { ua ->
            onChange(ua.trim())
        }
    }
}

/** The list: each player's name with what it sends underneath, then Custom…, which [onPick] gets as null. */
@Composable
private fun UserAgentPicker(
    selected: UserAgents.Preset?,
    allowCustom: Boolean,
    onPick: (UserAgents.Preset?) -> Unit,
    onDismiss: () -> Unit,
) {
    val tunedOwn = "Tuned/${BuildConfig.VERSION_NAME}"
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_user_agent)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                UserAgents.presets.forEach { p ->
                    val name = if (p == UserAgents.TUNED) stringResource(R.string.settings_user_agent_default) else p.label
                    Choice(name, p.value.ifBlank { tunedOwn }, on = p == selected) { onPick(p) }
                }
                if (allowCustom) {
                    Choice(stringResource(R.string.settings_user_agent_custom), null, on = selected == null) { onPick(null) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Choice(
    name: String,
    sends: String?,
    on: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .focusRing(RowFocusShape, MaterialTheme.colorScheme.primary)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = on, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (sends != null) {
                Text(
                    sends,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Custom…: the text the provider asked for. Saving it empty goes back to Tuned's own. */
@Composable
private fun CustomUserAgentDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_user_agent_custom)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_user_agent_custom_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
