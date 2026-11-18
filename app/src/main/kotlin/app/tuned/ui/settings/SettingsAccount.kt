package app.tuned.ui.settings

import android.text.format.DateUtils
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
import app.tuned.data.AppSettings
import app.tuned.ui.PlaylistsRoute

@Composable
internal fun AccountSettings(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
    onSignedOut: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.settings_account))
    PlaylistsRow(onOpen = { onOpen(PlaylistsRoute) })
    AccountStatusRows()
    SettingRow(
        stringResource(R.string.settings_refresh_now),
        lastSync(settings),
        onClick = vm::refreshNow,
    )
    SwitchRow(
        stringResource(R.string.settings_pull_to_refresh),
        settings.pullToRefresh,
        stringResource(R.string.settings_pull_to_refresh_summary),
    ) { on -> vm.update { it.copy(pullToRefresh = on) } }
    BackupRows()
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

@Composable
private fun lastSync(settings: AppSettings): String = when {
    settings.lastSyncError.isNotBlank() -> {
        stringResource(R.string.settings_last_error, settings.lastSyncError)
    }

    settings.lastPlaylistSync == 0L -> {
        stringResource(R.string.settings_never_synced)
    }

    else -> {
        stringResource(
            R.string.settings_last_synced,
            DateUtils
                .getRelativeTimeSpanString(
                    settings.lastPlaylistSync,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
        )
    }
}
