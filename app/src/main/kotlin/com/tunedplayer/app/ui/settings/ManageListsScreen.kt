package com.tunedplayer.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.data.db.ListEntity
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.browse.ListNameDialog
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.rememberStartFocus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ManageListsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val listsRepository = app.container.lists

    val lists: StateFlow<List<ListEntity>> =
        listsRepository.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rename(
        id: Long,
        name: String,
    ) {
        viewModelScope.launchSafely("ManageLists") { listsRepository.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launchSafely("ManageLists") { listsRepository.delete(id) }
    }
}

/** Settings › Channels and lists › Manage lists: rename or delete every list for the profile in use. */
@Composable
fun ManageListsScreen(
    onBack: () -> Unit,
    vm: ManageListsViewModel = viewModel(),
) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.lists_manage), onBack) }) { padding ->
        if (lists.isEmpty()) {
            Text(
                stringResource(R.string.lists_manage_empty),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CompositionLocalProvider(LocalRowStartFocus provides rememberStartFocus()) {
                ManageListsContent(lists, vm::rename, vm::delete, Modifier.padding(padding).fillMaxSize())
            }
        }
    }
}

@Composable
internal fun ManageListsContent(
    lists: List<ListEntity>,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<ListEntity?>(null) }
    var deleting by remember { mutableStateOf<ListEntity?>(null) }
    LazyColumn(modifier) {
        items(lists, key = { it.id }) { list ->
            SettingRow(
                title = list.name,
                summary = stringResource(R.string.lists_manage_row_summary),
                onClick = { renaming = list },
                trailing = {
                    IconButton(onClick = { renaming = list }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.list_rename))
                    }
                    IconButton(onClick = { deleting = list }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.list_delete))
                    }
                },
            )
        }
    }
    renaming?.let { list ->
        ListNameDialog(R.string.list_rename, list.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            onRename(list.id, name)
        }
    }
    deleting?.let { list ->
        TunedDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.list_delete_title, list.name)) },
            text = { Text(stringResource(R.string.list_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(list.id)
                }) { Text(stringResource(R.string.list_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
