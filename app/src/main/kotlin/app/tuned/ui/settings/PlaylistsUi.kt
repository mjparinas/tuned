package app.tuned.ui.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.data.Credentials
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.flow.MutableStateFlow

class PlaylistsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    val saved = MutableStateFlow(container.credentials.saved())
    val current = MutableStateFlow(container.credentials.load())

    fun switchTo(
        login: Credentials,
        onDone: () -> Unit,
    ) {
        viewModelScope.launchSafely("Playlists") {
            container.useLogin(login)
            current.value = login
            onDone()
        }
    }

    fun forget(login: Credentials) {
        container.credentials.forget(login)
        saved.value = container.credentials.saved()
    }
}

/**
 * Settings › Playlists: every login used on this device, to switch between without typing it again. Switching
 * downloads the other playlist; the app only ever holds one at a time.
 */
@Composable
fun PlaylistsRow(
    onAdd: () -> Unit,
    onSwitched: () -> Unit,
    vm: PlaylistsViewModel = viewModel(),
) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.playlists),
        pluralStringResource(R.plurals.playlists_saved, saved.size, saved.size),
        onClick = { open = true },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.playlists)) },
        text = {
            Column {
                saved.forEach { login ->
                    val active = current?.samePlaylist(login) == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !active) {
                                open = false
                                vm.switchTo(login, onSwitched)
                            }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = active, onClick = null)
                        Text(login.label, Modifier.weight(1f).padding(start = 12.dp))
                        if (!active) {
                            IconButton(onClick = { vm.forget(login) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.playlists_forget))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                open = false
                onAdd()
            }) { Text(stringResource(R.string.playlists_add)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
