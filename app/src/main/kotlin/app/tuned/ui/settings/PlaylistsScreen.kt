package app.tuned.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.data.Credentials
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.focusRing
import kotlinx.coroutines.flow.MutableStateFlow

class PlaylistsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    val saved = MutableStateFlow(container.credentials.saved())
    val current = MutableStateFlow(container.credentials.load())

    /** Settings and the list re-read on return from the add and edit forms. */
    fun reload() {
        saved.value = container.credentials.saved()
        current.value = container.credentials.load()
    }

    fun switchTo(
        login: Credentials,
        onDone: () -> Unit,
    ) {
        viewModelScope.launchSafely("Playlists") {
            container.session.useLogin(login)
            current.value = login
            onDone()
        }
    }

    fun rename(
        login: Credentials,
        name: String,
    ) {
        container.credentials.rename(login, name)
        reload()
    }

    /** [onEmpty] runs when the last playlist went, so the welcome screen shows. */
    fun remove(
        login: Credentials,
        onSwitched: () -> Unit,
        onEmpty: () -> Unit,
    ) {
        val wasActive = current.value?.samePlaylist(login) == true
        viewModelScope.launchSafely("Playlists") {
            val empty = container.session.removePlaylist(login)
            reload()
            when {
                empty -> onEmpty()
                wasActive -> onSwitched()
            }
        }
    }
}

/** Settings › Playlists: how many are saved, opening the full list. */
@Composable
fun PlaylistsRow(
    onOpen: () -> Unit,
    vm: PlaylistsViewModel = viewModel(),
) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reload() }
    SettingRow(
        stringResource(R.string.playlists),
        when {
            current == null -> pluralStringResource(R.plurals.playlists_saved, saved.size, saved.size)
            saved.size > 1 -> pluralStringResource(R.plurals.playlists_in_use, saved.size, current?.label.orEmpty(), saved.size)
            else -> current?.label.orEmpty()
        },
        onClick = onOpen,
    )
}

/**
 * Every playlist saved on this device: tap one to switch to it, and its menu renames, edits or removes it. The app
 * holds one playlist's channels at a time, so switching downloads the other one (Settings › Playlists, 2026-11-17).
 */
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (index: Int, kind: Credentials.Kind) -> Unit,
    onSwitched: () -> Unit,
    onEmpty: () -> Unit,
    vm: PlaylistsViewModel = viewModel(),
) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Credentials?>(null) }
    var removing by remember { mutableStateOf<Credentials?>(null) }
    LaunchedEffect(Unit) { vm.reload() }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.playlists), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.playlists_add)) },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.playlists_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(saved, key = { _, login -> login.kind.name + login.detail + login.playlistUrl }) { index, login ->
                PlaylistRow(
                    login = login,
                    active = current?.samePlaylist(login) == true,
                    onSwitch = { vm.switchTo(login, onSwitched) },
                    onRename = { renaming = login },
                    onEdit = { onEdit(index, login.kind) },
                    onRemove = { removing = login },
                )
            }
        }
    }

    renaming?.let { login ->
        TextDialog(stringResource(R.string.playlists_rename), login.name, onDismiss = { renaming = null }) { name ->
            vm.rename(login, name)
            renaming = null
        }
    }
    removing?.let { login ->
        val last = saved.size == 1
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.playlists_remove_title, login.label)) },
            text = { Text(stringResource(if (last) R.string.playlists_remove_last else R.string.playlists_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    vm.remove(login, onSwitched, onEmpty)
                }) { Text(stringResource(R.string.playlists_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PlaylistRow(
    login: Credentials,
    active: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primary)
            .clickable(enabled = !active, onClick = onSwitch)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (login.kind) {
                Credentials.Kind.Xtream -> Icons.Filled.Dns
                Credentials.Kind.Link -> Icons.Filled.Link
                Credentials.Kind.File -> Icons.Filled.FolderOpen
                Credentials.Kind.Stalker -> Icons.Filled.Router
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(login.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind =
                stringResource(
                    when (login.kind) {
                        Credentials.Kind.Xtream -> R.string.kind_xtream
                        Credentials.Kind.Link -> R.string.kind_link
                        Credentials.Kind.File -> R.string.kind_file
                        Credentials.Kind.Stalker -> R.string.kind_stalker
                    },
                )
            Text(
                if (login.name.isBlank()) kind else "$kind · ${login.detail}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.playlists_active),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.playlists_options, login.label))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.playlists_rename)) }, onClick = {
                    menu = false
                    onRename()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.playlists_edit)) }, onClick = {
                    menu = false
                    onEdit()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.playlists_remove)) }, onClick = {
                    menu = false
                    onRemove()
                })
            }
        }
    }
}
