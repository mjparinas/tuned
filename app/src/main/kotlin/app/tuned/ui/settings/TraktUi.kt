package app.tuned.ui.settings

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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
import app.tuned.core.Trakt
import app.tuned.data.TraktAccount
import app.tuned.diagnostics.launchSafely
import app.tuned.diagnostics.startActivitySafely
import app.tuned.premium.LockedRow
import app.tuned.premium.premiumUnlocked
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TraktViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val account = app.container.trakt
    val available = account.available
    val session = account.session
    val scrobble = account.scrobbleOn

    /** Signing in: the code on screen, then how it ended. */
    sealed interface SignIn {
        data object Asking : SignIn

        data class Code(
            val code: TraktAccount.DeviceCode,
        ) : SignIn

        data class Ended(
            val result: Trakt.Poll,
        ) : SignIn
    }

    private val signInFlow = MutableStateFlow<SignIn?>(null)
    val signIn: StateFlow<SignIn?> = signInFlow.asStateFlow()
    private var job: Job? = null

    fun startSignIn() {
        job?.cancel()
        signInFlow.value = SignIn.Asking
        job =
            viewModelScope.launchSafely(TAG, onError = { signInFlow.value = SignIn.Ended(Trakt.Poll.FAILED) }) {
                val code = account.requestCode()
                if (code == null) {
                    signInFlow.value = SignIn.Ended(Trakt.Poll.FAILED)
                    return@launchSafely
                }
                signInFlow.value = SignIn.Code(code)
                signInFlow.value = SignIn.Ended(account.awaitSignIn(code))
            }
    }

    fun closeSignIn() {
        job?.cancel()
        signInFlow.value = null
    }

    fun signOut() {
        viewModelScope.launchSafely(TAG) { account.signOut() }
    }

    fun setScrobble(on: Boolean) = account.setScrobble(on)

    private companion object {
        const val TAG = "TraktSettings"
    }
}

/**
 * Settings › Trakt (from the app audit, item 14): sign in with a code, see the account, choose whether films and
 * episodes are scrobbled, sign out. Hidden in builds without Trakt's app keys.
 */
@Composable
fun TraktRows(vm: TraktViewModel = viewModel()) {
    if (!vm.available) return
    val session by vm.session.collectAsStateWithLifecycle()
    val scrobble by vm.scrobble.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.trakt))
    val signedIn = session
    if (signedIn == null) {
        // Trakt is Premium (docs/decisions/premium.md).
        if (premiumUnlocked()) {
            SettingRow(stringResource(R.string.trakt_sign_in), stringResource(R.string.trakt_sign_in_summary), onClick = vm::startSignIn)
        } else {
            LockedRow(stringResource(R.string.trakt_sign_in))
        }
    } else {
        val name = signedIn.username ?: stringResource(R.string.trakt_account_unnamed)
        SettingRow(stringResource(R.string.trakt_signed_in, name), stringResource(R.string.trakt_sign_out), onClick = vm::signOut)
        SwitchRow(stringResource(R.string.trakt_scrobble), scrobble, stringResource(R.string.trakt_scrobble_summary), vm::setScrobble)
    }
    signIn?.let { SignInDialog(it, onRetry = vm::startSignIn, onClose = vm::closeSignIn) }
}

@Composable
private fun SignInDialog(
    state: TraktViewModel.SignIn,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val ended = state as? TraktViewModel.SignIn.Ended
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.trakt_sign_in)) },
        text = {
            when (state) {
                TraktViewModel.SignIn.Asking -> {
                    Text(stringResource(R.string.trakt_asking))
                }

                is TraktViewModel.SignIn.Code -> {
                    Column {
                        Text(stringResource(R.string.trakt_enter_code, state.code.verificationUrl.removePrefix("https://")))
                        Spacer(Modifier.height(16.dp))
                        SignInCode(state.code.userCode)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.trakt_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                }

                is TraktViewModel.SignIn.Ended -> {
                    Text(
                        stringResource(
                            when (state.result) {
                                Trakt.Poll.SIGNED_IN -> R.string.trakt_done
                                Trakt.Poll.EXPIRED -> R.string.trakt_expired
                                Trakt.Poll.DENIED -> R.string.trakt_denied
                                else -> R.string.trakt_failed
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            when {
                state is TraktViewModel.SignIn.Code -> {
                    TextButton(onClick = {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, state.code.verificationUrl.toUri()), "TraktSettings")
                    }) { Text(stringResource(R.string.trakt_open_site)) }
                }

                ended != null && ended.result != Trakt.Poll.SIGNED_IN -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.trakt_try_again)) }
                }

                else -> {
                    TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) }
                }
            }
        },
        dismissButton = {
            if (ended?.result != Trakt.Poll.SIGNED_IN) TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
