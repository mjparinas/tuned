package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.data.db.ListEntity
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.premium.Premium
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.focusRing

/** Opens "Add to list" for a channel or film, or the Premium screen while lists are locked (docs/decisions/premium.md). */
@Stable
class ListPicker internal constructor(
    private val unlocked: () -> Boolean,
    private val locked: () -> Unit,
) {
    internal var itemId by mutableStateOf<Long?>(null)

    fun open(id: Long) {
        if (unlocked()) itemId = id else locked()
    }
}

/** A [ListPicker], and its dialog while one is open. */
@Composable
fun rememberListPicker(): ListPicker {
    val context = LocalContext.current
    val unlocked by context.container.premium.unlocked
        .collectAsStateWithLifecycle()
    val isUnlocked by rememberUpdatedState(unlocked)
    val picker = remember { ListPicker({ isUnlocked }, { Premium.open(context) }) }
    picker.itemId?.let { id -> AddToListDialog(id) { picker.itemId = null } }
    return picker
}

/** Ticks for each of the person's lists, and a new one, for one channel or film. A tick saves at once. */
@Composable
private fun AddToListDialog(
    itemId: Long,
    onDismiss: () -> Unit,
) {
    val lists = LocalContext.current.container.lists
    val all by lists.all.collectAsStateWithLifecycle(emptyList())
    val holding by remember(itemId) { lists.holding(itemId) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        ListNameDialog(R.string.list_new, "", onDismiss = { naming = false }) { name ->
            naming = false
            scope.launchSafely(TAG) { lists.create(name, itemId) }
        }
        return
    }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_add_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                all.forEach { list ->
                    val on = list.id in holding
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .focusRing(RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primary)
                            .toggleable(value = on, role = Role.Checkbox) { now ->
                                scope.launchSafely(TAG) { lists.set(list.id, itemId, now) }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(list.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .focusRing(RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primary)
                        .clickable { naming = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(horizontal = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.list_new), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.list_done)) } },
    )
}

/** Asks for a list's name: a new one, or a new name for one. */
@Composable
internal fun ListNameDialog(
    title: Int,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                label = { Text(stringResource(R.string.list_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onDone(name) }) { Text(stringResource(R.string.list_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A list's chip in the row above Live TV or Movies. Filters only: rename and delete live in Settings › Manage lists
 * (Melvin, 2026-11-22: "Remove rename list and delete list on the chips on live tv", then the same for Movies).
 */
@Composable
internal fun OwnListChip(
    list: ListEntity,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text(list.name) },
    )
}

private const val TAG = "OwnLists"
private const val MAX_NAME = 40
