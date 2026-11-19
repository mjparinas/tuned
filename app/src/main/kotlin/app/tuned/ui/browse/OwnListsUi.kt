package app.tuned.ui.browse

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tuned.R
import app.tuned.container
import app.tuned.data.db.ListEntity
import app.tuned.diagnostics.launchSafely
import app.tuned.premium.Premium
import app.tuned.ui.common.TunedDialog

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

/** A list's chip in the row above Live TV or Movies; its menu renames or deletes the list. */
@Composable
internal fun OwnListChip(
    list: ListEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val renameLabel = stringResource(R.string.list_rename)
    val deleteLabel = stringResource(R.string.list_delete)
    FilterChip(
        selected = selected,
        onClick = onSelect,
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text(list.name) },
        // The small menu button is hard to reach with a screen reader, so renaming and deleting are actions on the chip.
        modifier =
            Modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(renameLabel) {
                            renaming = true
                            true
                        },
                        CustomAccessibilityAction(deleteLabel) {
                            deleting = true
                            true
                        },
                    )
            },
        trailingIcon =
            if (selected) {
                {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.list_options, list.name),
                        modifier = Modifier.size(18.dp).clickable { menu = true },
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(renameLabel) }, onClick = {
                            menu = false
                            renaming = true
                        })
                        DropdownMenuItem(text = { Text(deleteLabel) }, onClick = {
                            menu = false
                            deleting = true
                        })
                    }
                }
            } else {
                null
            },
    )
    if (renaming) {
        ListNameDialog(R.string.list_rename, list.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            onRename(name)
        }
    }
    if (deleting) {
        TunedDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.list_delete_title, list.name)) },
            text = { Text(stringResource(R.string.list_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text(deleteLabel) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val TAG = "OwnLists"
private const val MAX_NAME = 40
