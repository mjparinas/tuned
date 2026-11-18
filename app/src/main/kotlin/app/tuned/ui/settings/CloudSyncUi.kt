package app.tuned.ui.settings

import android.app.Application
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.tuned.work.CloudSyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CloudSyncViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    private val sync = app.container.cloudSync
    private val drive = sync.drive
    val available = sync.available
    val signedIn = sync.signedIn
    val hasPassphrase = sync.hasPassphrase
    val status = sync.status

    /** Signing in: the code on screen, then how it ended. */
    sealed interface SignIn {
        data object Asking : SignIn

        data class Code(
            val code: GoogleDrive.DeviceCode,
        ) : SignIn

        data class Ended(
            val failure: CloudSync.Failure,
        ) : SignIn
    }

    private val signInFlow = MutableStateFlow<SignIn?>(null)
    val signIn: StateFlow<SignIn?> = signInFlow.asStateFlow()
    private var job: Job? = null

    fun startSignIn() {
        job?.cancel()
        signInFlow.value = SignIn.Asking
        job =
            viewModelScope.launchSafely(TAG, onError = { signInFlow.value = SignIn.Ended(CloudSync.failure(null, null, null)) }) {
                val answer = drive.requestCode()
                val code = answer.code
                if (code == null) {
                    signInFlow.value = SignIn.Ended(answer.failure ?: CloudSync.failure(null, null, null))
                    return@launchSafely
                }
                signInFlow.value = SignIn.Code(code)
                // Signed in (no failure): straight on to the passphrase, so the dialog closes and that one opens.
                signInFlow.value = drive.awaitSignIn(code)?.let { SignIn.Ended(it) }
            }
    }

    fun closeSignIn() {
        job?.cancel()
        signInFlow.value = null
    }

    fun setPassphrase(passphrase: String) {
        sync.setPassphrase(passphrase)
        syncNow()
    }

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
 * code, choose the passphrase that locks the synced copy, see when it last synced, stop. Hidden in builds without the
 * app's Google keys.
 */
@Composable
fun CloudSyncRows(vm: CloudSyncViewModel = viewModel()) {
    if (!vm.available) return
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val hasPassphrase by vm.hasPassphrase.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    var askPassphrase by remember { mutableStateOf(false) }
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

        !hasPassphrase || status.problem == CloudSyncRepository.Problem.PASSPHRASE -> {
            val summary =
                if (hasPassphrase) R.string.cloud_sync_wrong_passphrase else R.string.cloud_sync_set_passphrase_summary
            SettingRow(stringResource(R.string.cloud_sync_set_passphrase), stringResource(summary), onClick = { askPassphrase = true })
        }

        else -> {
            SettingRow(stringResource(R.string.cloud_sync_now), statusText(status), onClick = vm::syncNow)
        }
    }
    if (signedIn) {
        SettingRow(stringResource(R.string.cloud_sync_stop), stringResource(R.string.cloud_sync_stop_summary), onClick = vm::stop)
    }
    signIn?.let { SignInDialog(it, onRetry = vm::startSignIn, onClose = vm::closeSignIn) }
    // Just signed in: straight on to the passphrase.
    LaunchedEffect(signedIn, hasPassphrase) { if (signedIn && !hasPassphrase) askPassphrase = true }
    if (askPassphrase) {
        PassphraseDialog(
            create = true,
            wrong = false,
            onDismiss = { askPassphrase = false },
            help = stringResource(R.string.cloud_sync_passphrase_help),
        ) { passphrase ->
            askPassphrase = false
            vm.setPassphrase(passphrase)
        }
    }
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

@Composable
private fun SignInDialog(
    state: CloudSyncViewModel.SignIn,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.cloud_sync_start)) },
        text = {
            when (state) {
                CloudSyncViewModel.SignIn.Asking -> {
                    Text(stringResource(R.string.cloud_sync_asking))
                }

                is CloudSyncViewModel.SignIn.Code -> {
                    Column {
                        Text(stringResource(R.string.cloud_sync_enter_code, state.code.verificationUrl.removePrefix("https://")))
                        Spacer(Modifier.height(16.dp))
                        SignInCode(state.code.userCode)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.cloud_sync_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                }

                is CloudSyncViewModel.SignIn.Ended -> {
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
                is CloudSyncViewModel.SignIn.Code -> {
                    TextButton(onClick = {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, state.code.verificationUrl.toUri()), "CloudSyncSettings")
                    }) { Text(stringResource(R.string.cloud_sync_open_site)) }
                }

                is CloudSyncViewModel.SignIn.Ended -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.cloud_sync_try_again)) }
                }

                CloudSyncViewModel.SignIn.Asking -> {}
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
    )
}
