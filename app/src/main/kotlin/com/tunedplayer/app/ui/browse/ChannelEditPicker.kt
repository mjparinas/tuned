package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.data.db.ChannelEditEntity
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.premium.Premium
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.isTelevision

/** Renaming a channel or changing its logo, or the Premium screen while that's locked (docs/decisions/premium.md). */
@Stable
class ChannelEditPicker internal constructor(
    private val canRename: () -> Boolean,
    private val unlocked: () -> Boolean,
    private val locked: () -> Unit,
) {
    internal var renaming by mutableStateOf<Long?>(null)
    internal var changingLogo by mutableStateOf<Long?>(null)

    fun rename(id: Long) {
        if (!canRename()) return
        if (unlocked()) renaming = id else locked()
    }

    fun changeLogo(id: Long) {
        if (unlocked()) changingLogo = id else locked()
    }
}

/** A [ChannelEditPicker], and its dialog while one is open. */
@Composable
fun rememberChannelEditPicker(): ChannelEditPicker {
    val context = LocalContext.current
    val unlocked by context.container.premium.unlocked
        .collectAsStateWithLifecycle()
    val isUnlocked by rememberUpdatedState(unlocked)
    val renameAllowed by rememberUpdatedState(!isTelevision())
    val picker = remember { ChannelEditPicker({ renameAllowed }, { isUnlocked }, { Premium.open(context) }) }
    picker.renaming?.let { id -> RenameChannelDialog(id) { picker.renaming = null } }
    picker.changingLogo?.let { id -> ChangeLogoDialog(id) { picker.changingLogo = null } }
    return picker
}

@Composable
private fun RenameChannelDialog(
    itemId: Long,
    onDismiss: () -> Unit,
) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val item by produceState<Pair<String, ChannelEditEntity?>?>(null, itemId) {
        value = container.db
            .items()
            .get(itemId)
            ?.name
            .orEmpty() to container.db.channelEdits().get(itemId)
    }
    val current = item ?: return
    var name by remember(current) { mutableStateOf(current.first) }
    val edit = current.second
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.channel_name)) },
                    singleLine = true,
                )
                if (edit?.name != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.channel_original_name, edit.originalName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onDismiss()
                scope.launchSafely(TAG) { container.channelEditor.set(itemId, name, edit?.logo) }
            }) { Text(stringResource(R.string.list_save)) }
        },
        dismissButton = {
            if (edit?.name != null) {
                TextButton(onClick = {
                    onDismiss()
                    scope.launchSafely(TAG) { container.channelEditor.set(itemId, null, edit.logo) }
                }) { Text(stringResource(R.string.channel_use_original_name)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun ChangeLogoDialog(
    itemId: Long,
    onDismiss: () -> Unit,
) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val state by produceState<Triple<String?, ChannelEditEntity?, List<String>>?>(null, itemId) {
        value =
            Triple(
                container.db
                    .items()
                    .get(itemId)
                    ?.logo,
                container.db.channelEdits().get(itemId),
                container.tune.copyLogos(itemId),
            )
    }
    val current = state ?: return
    val edit = current.second
    var link by remember(current) { mutableStateOf(edit?.logo.orEmpty()) }
    val save = { logo: String? ->
        onDismiss()
        scope.launchSafely(TAG) { container.channelEditor.set(itemId, edit?.name, logo) }
    }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_change_logo)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (current.third.isNotEmpty()) {
                    Text(stringResource(R.string.channel_logo_from_copies), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.third.forEachIndexed { i, logo ->
                            val label = stringResource(R.string.channel_logo_choice, i + 1)
                            val chosen = link == logo
                            Logo(
                                logo,
                                Modifier
                                    .size(width = 72.dp, height = 48.dp)
                                    .then(
                                        if (chosen) {
                                            Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp))
                                        } else {
                                            Modifier
                                        },
                                    ).focusRing(RoundedCornerShape(8.dp), MaterialTheme.colorScheme.onSurface)
                                    .clickable(role = Role.RadioButton) { link = logo }
                                    .semantics {
                                        contentDescription = label
                                        selected = chosen
                                    },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim() },
                    label = { Text(stringResource(R.string.channel_logo_link)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = link.isNotBlank(), onClick = { save(link) }) { Text(stringResource(R.string.list_save)) }
        },
        dismissButton = {
            if (edit?.logo != null) {
                TextButton(onClick = { save(null) }) { Text(stringResource(R.string.channel_use_original_logo)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private const val TAG = "ChannelEdits"
private const val MAX_NAME = 60
