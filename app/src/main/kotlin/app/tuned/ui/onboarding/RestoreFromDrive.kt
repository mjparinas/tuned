package app.tuned.ui.onboarding

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.data.CloudSyncRepository
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.asksProfileAtStart
import app.tuned.ui.common.TunedDialog
import app.tuned.ui.settings.DriveSignIn
import app.tuned.ui.settings.DriveSignInDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The welcome screen's Restore from Google Drive (Melvin, 2026-11-19: "The homepage of the tuned app should let you also
 * sign into Google drive and load all your settings automatically"): Google's sign-in with a code, as in Settings › Sync
 * between devices, then the copy in Drive in place of this fresh install's, and straight into the app.
 */
class RestoreViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val sync = app.container.cloudSync
    val available = sync.available

    sealed interface State {
        data object Restoring : State

        /** Back in: open the app, on "Who's watching?" first when the restored settings ask for it. */
        data class Restored(
            val askProfile: Boolean,
        ) : State

        data class Ended(
            val result: CloudSyncRepository.Restore,
        ) : State
    }

    private val stateFlow = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = stateFlow.asStateFlow()

    private val driveSignIn = DriveSignIn(viewModelScope, sync.drive) { restore() }
    val signIn: StateFlow<DriveSignIn.State?> = driveSignIn.state

    /** Signs in first, unless this device already is (an earlier try that found nothing). */
    fun start() {
        if (sync.signedIn.value) restore() else driveSignIn.start()
    }

    fun closeSignIn() = driveSignIn.close()

    fun dismiss() {
        stateFlow.value = null
    }

    private fun restore() {
        stateFlow.value = State.Restoring
        viewModelScope.launchSafely(TAG, onError = { stateFlow.value = State.Ended(CloudSyncRepository.Restore.OFFLINE) }) {
            stateFlow.value =
                when (val result = sync.restore()) {
                    CloudSyncRepository.Restore.RESTORED -> {
                        val settings =
                            getApplication<Application>()
                                .container.settings.settings
                                .first()
                        State.Restored(settings.asksProfileAtStart())
                    }

                    else -> {
                        State.Ended(result)
                    }
                }
        }
    }

    private companion object {
        const val TAG = "RestoreFromDrive"
    }
}

/** The card under the three kinds of playlist, and the dialogs it opens. Hidden in builds without the app's Google keys. */
@Composable
fun RestoreFromDrive(
    onRestored: (askProfile: Boolean) -> Unit,
    vm: RestoreViewModel = viewModel(),
) {
    if (!vm.available) return
    val context = LocalContext.current
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    // Sync is Premium (docs/decisions/premium.md); a Play purchase comes back on its own on a new device.
    KindCard(Icons.Filled.CloudDownload, R.string.restore_drive, R.string.restore_drive_summary) {
        if (context.container.premium.allow(context)) vm.start()
    }
    signIn?.let { DriveSignInDialog(it, stringResource(R.string.restore_drive), onRetry = vm::start, onClose = vm::closeSignIn) }
    when (val s = state) {
        null -> {}

        RestoreViewModel.State.Restoring -> {
            TunedDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.restore_drive)) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.restore_drive_working))
                    }
                },
                confirmButton = {},
            )
        }

        is RestoreViewModel.State.Restored -> {
            LaunchedEffect(s) { onRestored(s.askProfile) }
        }

        is RestoreViewModel.State.Ended -> {
            TunedDialog(
                onDismissRequest = vm::dismiss,
                title = { Text(stringResource(R.string.restore_drive)) },
                text = { Text(stringResource(endedText(s.result))) },
                confirmButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(android.R.string.ok)) } },
                dismissButton =
                    if (s.result == CloudSyncRepository.Restore.OFFLINE) {
                        { TextButton(onClick = vm::start) { Text(stringResource(R.string.cloud_sync_try_again)) } }
                    } else {
                        null
                    },
            )
        }
    }
}

private fun endedText(result: CloudSyncRepository.Restore): Int = when (result) {
    CloudSyncRepository.Restore.NOTHING_SAVED -> R.string.restore_drive_nothing
    CloudSyncRepository.Restore.NO_PLAYLIST -> R.string.restore_drive_no_playlist
    CloudSyncRepository.Restore.DRIVE_API_OFF -> R.string.cloud_sync_drive_api_off
    CloudSyncRepository.Restore.OFFLINE, CloudSyncRepository.Restore.RESTORED -> R.string.restore_drive_offline
}
