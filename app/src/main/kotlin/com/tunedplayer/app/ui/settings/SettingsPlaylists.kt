package com.tunedplayer.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.tunedplayer.app.R
import com.tunedplayer.app.data.AppReset
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.PlaylistsRoute

@Composable
internal fun PlaylistsPage(
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
    onSignedOut: () -> Unit,
) {
    PlaylistsRow(onOpen = { onOpen(PlaylistsRoute) })
    AccountStatusRows()
    TraktRows()
    TunedAccountRows()
    SignOutRow(vm, onSignedOut)
}

/** Sign out: what it removes, then, when anything is saved, whether the downloads go too. */
@Composable
private fun SignOutRow(
    vm: SettingsViewModel,
    onSignedOut: () -> Unit,
) {
    var step by remember { mutableStateOf(SignOutStep.NONE) }
    var saved by remember { mutableStateOf<AppReset.Saved?>(null) }
    val scope = rememberCoroutineScope()
    val signOut: (Boolean) -> Unit = { keep ->
        step = SignOutStep.WORKING
        vm.signOut(keepDownloads = keep, onDone = onSignedOut)
    }
    SettingRow(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_summary), onClick = {
        step = SignOutStep.CONFIRM
    })
    when (step) {
        SignOutStep.NONE -> {
            Unit
        }

        SignOutStep.CONFIRM -> {
            SignOutDialog(
                onSignOut = {
                    scope.launchSafely("SignOut") {
                        val found = vm.savedDownloads()
                        saved = found
                        if (found.count > 0) step = SignOutStep.DOWNLOADS else signOut(false)
                    }
                },
                onCancel = { step = SignOutStep.NONE },
            )
        }

        SignOutStep.DOWNLOADS -> {
            saved?.let {
                SignOutDownloadsDialog(
                    saved = it,
                    onDelete = { signOut(false) },
                    onKeep = { signOut(true) },
                    onCancel = { step = SignOutStep.NONE },
                )
            }
        }

        SignOutStep.WORKING -> {
            SigningOutDialog()
        }
    }
}

private enum class SignOutStep { NONE, CONFIRM, DOWNLOADS, WORKING }
