package com.tunedplayer.app.ui.settings

import android.app.Application
import android.content.Intent
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
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Simkl
import com.tunedplayer.app.data.SimklAccount
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.diagnostics.startActivitySafely
import com.tunedplayer.app.premium.LockedRow
import com.tunedplayer.app.premium.premiumUnlocked
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.unbreakable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SimklViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val account = app.container.simkl
    val available = account.available
    val session = account.session
    val scrobble = account.scrobbleOn

    /** Signing in: the code on screen, then how it ended. */
    sealed interface SignIn {
        data object Asking : SignIn

        data class Code(
            val code: SimklAccount.DeviceCode,
        ) : SignIn

        data class Ended(
            val result: Simkl.Poll,
        ) : SignIn
    }

    private val signInFlow = MutableStateFlow<SignIn?>(null)
    val signIn: StateFlow<SignIn?> = signInFlow.asStateFlow()
    private var job: Job? = null

    fun startSignIn() {
        job?.cancel()
        signInFlow.value = SignIn.Asking
        job =
            viewModelScope.launchSafely(TAG, onError = { signInFlow.value = SignIn.Ended(Simkl.Poll.FAILED) }) {
                val code = account.requestCode()
                if (code == null) {
                    signInFlow.value = SignIn.Ended(Simkl.Poll.FAILED)
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
        const val TAG = "SimklSettings"
    }
}

/**
 * Settings › Simkl: sign in with a code, see the account, choose whether films and episodes are scrobbled, sign out.
 * Hidden in builds without Simkl's app key.
 */
@Composable
fun SimklRows(vm: SimklViewModel = viewModel()) {
    if (!vm.available) return
    val session by vm.session.collectAsStateWithLifecycle()
    val scrobble by vm.scrobble.collectAsStateWithLifecycle()
    val signIn by vm.signIn.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.simkl))
    val signedIn = session
    if (signedIn == null) {
        // Simkl is Premium (docs/decisions/premium.md).
        if (premiumUnlocked()) {
            SettingRow(stringResource(R.string.simkl_sign_in), stringResource(R.string.simkl_sign_in_summary), onClick = vm::startSignIn)
        } else {
            LockedRow(stringResource(R.string.simkl_sign_in))
        }
    } else {
        val name = signedIn.username ?: stringResource(R.string.simkl_account_unnamed)
        SettingRow(stringResource(R.string.simkl_signed_in, name), stringResource(R.string.simkl_sign_out), onClick = vm::signOut)
        SwitchRow(stringResource(R.string.simkl_scrobble), scrobble, stringResource(R.string.simkl_scrobble_summary), vm::setScrobble)
    }
    signIn?.let { SignInDialog(it, onRetry = vm::startSignIn, onClose = vm::closeSignIn) }
}

@Composable
private fun SignInDialog(
    state: SimklViewModel.SignIn,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val ended = state as? SimklViewModel.SignIn.Ended
    TunedDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.simkl_sign_in)) },
        text = {
            when (state) {
                SimklViewModel.SignIn.Asking -> {
                    Text(stringResource(R.string.simkl_asking))
                }

                is SimklViewModel.SignIn.Code -> {
                    Column {
                        Text(stringResource(R.string.simkl_enter_code, unbreakable(state.code.verificationUrl.removePrefix("https://"))))
                        Spacer(Modifier.height(16.dp))
                        SignInCode(state.code.userCode)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.simkl_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                }

                is SimklViewModel.SignIn.Ended -> {
                    Text(
                        stringResource(
                            when (state.result) {
                                Simkl.Poll.SIGNED_IN -> R.string.simkl_done
                                Simkl.Poll.EXPIRED -> R.string.simkl_expired
                                else -> R.string.simkl_failed
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            when {
                state is SimklViewModel.SignIn.Code -> {
                    TextButton(onClick = {
                        context.startActivitySafely(Intent(Intent.ACTION_VIEW, state.code.verificationUrl.toUri()), "SimklSettings")
                    }) { Text(stringResource(R.string.simkl_open_site)) }
                }

                ended != null && ended.result != Simkl.Poll.SIGNED_IN -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.simkl_try_again)) }
                }

                else -> {
                    TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) }
                }
            }
        },
        dismissButton = {
            if (ended?.result != Simkl.Poll.SIGNED_IN) TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
