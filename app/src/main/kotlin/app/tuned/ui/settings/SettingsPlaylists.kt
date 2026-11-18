package app.tuned.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.ui.PlaylistsRoute

@Composable
internal fun PlaylistsPage(
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
    onSignedOut: () -> Unit,
) {
    PlaylistsRow(onOpen = { onOpen(PlaylistsRoute) })
    AccountStatusRows()
    BackupRows()
    var confirmSignOut by remember { mutableStateOf(false) }
    SettingRow(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_summary), onClick = {
        confirmSignOut =
            true
    })
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    vm.signOut(onSignedOut)
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
