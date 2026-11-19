package app.tuned.ui.settings

import android.app.Application
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.CloudSync
import app.tuned.data.CloudSyncRepository
import app.tuned.data.GoogleDrive
import app.tuned.diagnostics.launchSafely
import app.tuned.diagnostics.startActivitySafely
import app.tuned.premium.LockedRow
import app.tuned.premium.premiumUnlocked
import app.tuned.ui.common.TunedDialog
import app.tuned.work.CloudSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Signing in to Google with a code: the code on screen, then how it ended. Shared by Settings › Sync between devices
 * and the welcome screen's Restore from Google Drive ([app.tuned.ui.onboarding.RestoreFromDrive]).
 */
class DriveSignIn(
    private val scope: CoroutineScope,
    private val drive: GoogleDrive,
    /** Signed in: the dialog has closed. */
    private val onSignedIn: suspend () -> Unit,
) {
    sealed interface State {
        data object Asking : State

        data class Code(
            val code: GoogleDrive.DeviceCode,
        ) : State

        data class Ended(
            val failure: CloudSync.Failure,
        ) : State
    }

    private val stateFlow = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = stateFlow.asStateFlow()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        stateFlow.value = State.Asking
        job =
            scope.launchSafely(TAG, onError = { stateFlow.value = State.Ended(CloudSync.failure(null, null, null)) }) {
                val answer = drive.requestCode()
                val code = answer.code
                if (code == null) {
                    stateFlow.value = State.Ended(answer.failure ?: CloudSync.failure(null, null, null))
                    return@launchSafely
                }
                stateFlow.value = State.Code(code)
                val failure = drive.awaitSignIn(code)
                stateFlow.value = failure?.let { State.Ended(it) }
                if (failure == null) onSignedIn()
            }
    }

    fun close() {
        job?.cancel()
        stateFlow.value = null
    }

    private companion object {
        const val TAG = "DriveSignIn"
    }
}

class CloudSyncViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    private val sync = app.container.cloudSync
    val available = sync.available
    val signedIn = sync.signedIn
    val status = sync.status

    // Signed in: the dialog closes and the first sync starts.
    private val driveSignIn = DriveSignIn(viewModelScope, sync.drive) { syncNow() }
    val signIn: StateFlow<DriveSignIn.State?> = driveSignIn.state

    fun startSignIn() = driveSignIn.start()

    fun closeSignIn() = driveSignIn.close()

    fun syncNow() = CloudSyncWorker.schedule(app)

    fun stop() {
        viewModelScope.launchSafely(TAG) { sync.stop() }
    }

    private companion object {
        const val TAG = "CloudSyncSettings"
    }
}

/**
 * Settings › Playlists and account › Sync between devices (from the app audit, item 13): sign in to Google with a
 * code, see when it last synced, stop. Hidden in builds without the
 * app's Google keys.
 */
@Composable
fun CloudSyncRows(vm: CloudSyncViewModel = viewModel()) {
    if (!vm.available) return
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.cloud_sync))
    when {
        // Sync is Premium (docs/decisions/premium.md).
        !signedIn && !premiumUnlocked() -> {
            LockedRow(stringResource(R.string.cloud_sync_start))
        }

        !signedIn -> {
            SettingRow(
                stringResource(R.string.cloud_sync_start),
                stringResource(R.string.cloud_sync_start_summary),
                onClick = vm::startSignIn,
            )
        }

        else -> {
            SettingRow(stringResource(R.string.cloud_sync_now), statusText(status), onClick = vm::syncNow)
        }
    }
    if (signedIn) {
        SettingRow(stringResource(R.string.cloud_sync_stop), stringResource(R.string.cloud_sync_stop_summary), onClick = vm::stop)
    }
    signIn?.let { DriveSignInDialog(it, stringResource(R.string.cloud_sync_start), onRetry = vm::startSignIn, onClose = vm::closeSignIn) }
}

@Composable
private fun statusText(status: CloudSyncRepository.Status): String = when {
    status.problem == CloudSyncRepository.Problem.OFFLINE -> {
        stringResource(R.string.cloud_sync_offline)
    }

    status.problem == CloudSyncRepository.Problem.DRIVE_API_OFF -> {
        stringResource(R.string.cloud_sync_drive_api_off)
    }

    status.lastSyncAt == 0L -> {
        stringResource(R.string.cloud_sync_never)
    }

    else -> {
        val context = LocalContext.current
        val time =
            DateUtils.getRelativeDateTimeString(context, status.lastSyncAt, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0)
        stringResource(R.string.cloud_sync_last, time)
    }
}

private fun failureText(reason: CloudSync.Reason): Int = when (reason) {
    CloudSync.Reason.OFFLINE -> R.string.cloud_sync_failed
    CloudSync.Reason.EXPIRED -> R.string.cloud_sync_expired
    CloudSync.Reason.DENIED -> R.string.cloud_sync_denied
    CloudSync.Reason.UNKNOWN_CLIENT -> R.string.cloud_sync_unknown_client
    CloudSync.Reason.WRONG_CLIENT_TYPE -> R.string.cloud_sync_wrong_client_type
    CloudSync.Reason.WRONG_SECRET -> R.string.cloud_sync_wrong_secret
    CloudSync.Reason.SCOPE_NOT_ALLOWED -> R.string.cloud_sync_scope
    CloudSync.Reason.OTHER -> R.string.cloud_sync_other
}

/** Google's sign-in with a code, from [DriveSignIn]. */
@Composable
internal fun DriveSignInDialog(
    state: DriveSignIn.State,
    title: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    TunedDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            when (state) {
                DriveSignIn.State.Asking -> {
                    Text(stringResource(R.string.cloud_sync_asking))
                }

                is DriveSignIn.State.Code -> {
                    Column {
                        Text(stringResource(R.string.cloud_sync_enter_code, state.code.verificationUrl.removePrefix("https://")))
                        Spacer(Modifier.height(16.dp))
                        SignInCode(state.code.userCode)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.cloud_sync_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                }

                is DriveSignIn.State.Ended -> {
                    Column {
                        Text(stringResource(failureText(state.failure.reason)))
                        // Google's own words, so a setup problem on Google's side can be told from a network one.
                        state.failure.detail?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.cloud_sync_google_said, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is DriveSignIn.State.Code -> {
                    TextButton(onClick = {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, state.code.verificationUrl.toUri()), "CloudSyncSettings")
                    }) { Text(stringResource(R.string.cloud_sync_open_site)) }
                }

                is DriveSignIn.State.Ended -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.cloud_sync_try_again)) }
                }

                DriveSignIn.State.Asking -> {}
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
    )
}
