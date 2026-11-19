package app.tuned.ui.settings

import android.app.Application
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import app.tuned.core.TvHandoff
import app.tuned.data.CloudSyncRepository
import app.tuned.data.TunedAccount
import app.tuned.data.TvRelay
import app.tuned.diagnostics.launchSafely
import app.tuned.diagnostics.startActivitySafely
import app.tuned.premium.LockedRow
import app.tuned.premium.premiumUnlocked
import app.tuned.ui.common.TunedDialog
import app.tuned.ui.common.isTelevision
import app.tuned.ui.onboarding.Qr
import app.tuned.ui.onboarding.QrModules
import app.tuned.work.CloudSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Signing this device in to the Tuned account (docs/decisions/accounts.md): a code from the TV sign-in relay, shown with
 * a QR code of the website's page for it; the person signs in with Google there and the relay hands this device a
 * one-time code, which [TunedAccount.signIn] swaps for its own token. The same on a phone and a TV remote, with no
 * Google sign-in in the app. Shared by Settings and the welcome screen's Restore from your Tuned account.
 */
class AccountSignIn(
    private val scope: CoroutineScope,
    private val relay: TvRelay,
    private val account: TunedAccount,
    /** Signed in: the dialog has closed. */
    private val onSignedIn: suspend () -> Unit,
) {
    sealed interface State {
        data object Asking : State

        data class Code(
            val code: String,
            val page: String,
            val qr: QrModules,
        ) : State

        data object Failed : State
    }

    private val stateFlow = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = stateFlow.asStateFlow()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        stateFlow.value = State.Asking
        job =
            scope.launchSafely(TAG, onError = { stateFlow.value = State.Failed }) {
                while (true) {
                    // The relay needs a key for its code; nothing is sealed to it here.
                    val keys = withContext(Dispatchers.Default) { TvHandoff.newKeys() }
                    val code = relay.code(keys.publicKey)
                    if (code == null) {
                        stateFlow.value = State.Failed
                        return@launchSafely
                    }
                    val page = account.signInPage(code.code)
                    stateFlow.value =
                        State.Code(TvHandoff.displayCode(code.code), page, withContext(Dispatchers.Default) { QrModules.of(page) })
                    when (val link = waitForLink(code)) {
                        null -> {
                            continue
                        }

                        "" -> {
                            stateFlow.value = State.Failed
                            return@launchSafely
                        }

                        else -> {
                            if (account.signIn(link)) {
                                stateFlow.value = null
                                onSignedIn()
                            } else {
                                stateFlow.value = State.Failed
                            }
                            return@launchSafely
                        }
                    }
                }
            }
    }

    /** The one-time code once the website sends it; null when the code ran out (make another), "" when unreachable. */
    private suspend fun waitForLink(code: TvRelay.Code): String? {
        val until = System.currentTimeMillis() + (code.seconds - MARGIN_SECONDS) * 1000L
        var misses = 0
        while (System.currentTimeMillis() < until) {
            delay(POLL_MS)
            when (val taken = relay.take(code)) {
                is TvRelay.Taken.Linked -> return taken.link

                TvRelay.Taken.Waiting -> misses = 0

                TvRelay.Taken.Unreachable -> if (++misses >= MAX_MISSES) return ""

                // A playlist sent to this code by mistake, or the code gone: start over with a new one.
                TvRelay.Taken.Gone, is TvRelay.Taken.Box -> return null
            }
        }
        return null
    }

    fun close() {
        job?.cancel()
        stateFlow.value = null
    }

    private companion object {
        const val TAG = "AccountSignIn"
        const val POLL_MS = 2_000L
        const val MARGIN_SECONDS = 5
        const val MAX_MISSES = 5
    }
}

class TunedAccountViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    private val sync = app.container.cloudSync
    val signedIn = sync.signedIn
    val email = sync.account.email
    val status = sync.status

    // Signed in: the dialog closes and the first backup starts.
    private val accountSignIn = AccountSignIn(viewModelScope, app.container.tvRelay, sync.account) { backUpNow() }
    val signIn: StateFlow<AccountSignIn.State?> = accountSignIn.state

    fun startSignIn() = accountSignIn.start()

    fun closeSignIn() = accountSignIn.close()

    fun backUpNow() = CloudSyncWorker.schedule(app)

    fun signOut() {
        viewModelScope.launchSafely(TAG) { sync.stop() }
    }

    private companion object {
        const val TAG = "TunedAccountSettings"
    }
}

/**
 * Settings › Playlists and account › Tuned account: sign in with a code, see when it last backed up, back up now, sign
 * out. Backing up between devices is Premium in the store build, as Drive sync was (docs/decisions/premium.md).
 */
@Composable
fun TunedAccountRows(vm: TunedAccountViewModel = viewModel()) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val email by vm.email.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.tuned_account))
    when {
        !signedIn && !premiumUnlocked() -> {
            LockedRow(stringResource(R.string.tuned_account_sign_in))
        }

        !signedIn -> {
            SettingRow(
                stringResource(R.string.tuned_account_sign_in),
                stringResource(R.string.tuned_account_sign_in_summary),
                onClick = vm::startSignIn,
            )
        }

        else -> {
            SettingRow(stringResource(R.string.tuned_account_backup_now), statusText(status), onClick = vm::backUpNow)
            SettingRow(
                stringResource(R.string.tuned_account_sign_out),
                listOfNotNull(
                    email?.let { stringResource(R.string.tuned_account_signed_in, it) },
                    stringResource(R.string.tuned_account_sign_out_summary),
                ).joinToString(" "),
                onClick = vm::signOut,
            )
        }
    }
    signIn?.let {
        AccountSignInDialog(it, stringResource(R.string.tuned_account_sign_in), onRetry = vm::startSignIn, onClose = vm::closeSignIn)
    }
}

@Composable
private fun statusText(status: CloudSyncRepository.Status): String = when {
    status.problem == CloudSyncRepository.Problem.OFFLINE -> {
        stringResource(R.string.tuned_account_offline)
    }

    status.lastSyncAt == 0L -> {
        stringResource(R.string.tuned_account_never)
    }

    else -> {
        val context = LocalContext.current
        val time =
            DateUtils.getRelativeDateTimeString(context, status.lastSyncAt, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0)
        stringResource(R.string.tuned_account_last, time)
    }
}

/** The code and QR code from [AccountSignIn]. A TV shows the QR code; a phone opens the page itself instead. */
@Composable
internal fun AccountSignInDialog(
    state: AccountSignIn.State,
    title: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // The screen's own mode, not the leanback feature, so a TV-sized window draws it as a TV does.
    val tv = isTelevision()
    TunedDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            when (state) {
                AccountSignIn.State.Asking -> {
                    Text(stringResource(R.string.tuned_account_asking))
                }

                is AccountSignIn.State.Code -> {
                    val site = state.page.substringAfter("://").substringBefore('?')
                    val steps: @Composable () -> Unit = {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(stringResource(if (tv) R.string.tuned_account_enter_code else R.string.tuned_account_open_or_enter, site))
                            Spacer(Modifier.height(16.dp))
                            SignInCode(state.code)
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.tuned_account_waiting), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (tv) {
                        // Side by side, so the dialog stays inside a TV's 540 dp with the QR code at a size a phone can scan.
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Qr(state.qr, side = 200.dp)
                            Box(Modifier.weight(1f)) { steps() }
                        }
                    } else {
                        steps()
                    }
                }

                AccountSignIn.State.Failed -> {
                    Text(stringResource(R.string.tuned_account_failed))
                }
            }
        },
        confirmButton = {
            when (state) {
                is AccountSignIn.State.Code -> {
                    if (!tv) {
                        TextButton(onClick = {
                            context.startActivitySafely(Intent(Intent.ACTION_VIEW, state.page.toUri()), "TunedAccountSettings")
                        }) { Text(stringResource(R.string.tuned_account_open_site)) }
                    }
                }

                AccountSignIn.State.Failed -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.tuned_account_try_again)) }
                }

                AccountSignIn.State.Asking -> {}
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
    )
}
