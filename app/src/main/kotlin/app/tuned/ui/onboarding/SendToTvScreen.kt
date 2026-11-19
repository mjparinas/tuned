package app.tuned.ui.onboarding

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.TvHandoff
import app.tuned.data.Credentials
import app.tuned.data.HandoffAccount
import app.tuned.data.TvRelay
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The phone's side of signing in on a TV (docs/decisions/onboarding.md): opened from the TV's QR code (an app link)
 * or Settings › Playlists › Send to a TV. It sends what sync between devices sends, sealed for that one TV.
 */
class SendToTvViewModel(
    app: Application,
) : AndroidViewModel(app) {
    sealed interface State {
        /** Nothing to send yet. */
        data object Empty : State

        data class Enter(
            val error: Int? = null,
            val busy: Boolean = false,
        ) : State

        /** A typed code: the relay's key for it, to check against the four digits on the TV. */
        data class Confirm(
            val target: TvHandoff.Target,
            val digits: String,
        ) : State

        data class Ready(
            val target: TvHandoff.Target,
            val playlists: Int,
            val error: Int? = null,
            val busy: Boolean = false,
        ) : State

        data object Sent : State
    }

    private val container = app.container
    val state = MutableStateFlow<State>(State.Enter())
    private var started = false

    private fun playlists() = container.credentials.saved().count { it.kind != Credentials.Kind.File }

    /** Starts from a QR link when there is one, else from typing the code. */
    fun start(link: String) {
        if (started) return
        started = true
        state.value = TvHandoff.parseLink(link)?.let(::ready) ?: State.Enter()
    }

    /** Ready to send to [target], or nothing to send when this phone has no playlist yet. */
    private fun ready(target: TvHandoff.Target): State = playlists().let { if (it == 0) State.Empty else State.Ready(target, it) }

    fun lookUp(typed: String) {
        val code = TvHandoff.normalizeCode(typed)
        if (code == null) {
            state.value = State.Enter(R.string.send_tv_bad_code)
            return
        }
        state.value = State.Enter(busy = true)
        viewModelScope.launchSafely(TAG) {
            val found = container.tvRelay.key(code)
            state.value =
                when (val key = found.getOrNull()) {
                    null -> State.Enter(if (found.isSuccess) R.string.send_tv_not_found else R.string.send_tv_unreachable)
                    else -> State.Confirm(TvHandoff.Target(code, key), TvHandoff.checkDigits(key))
                }
        }
    }

    fun confirmed() {
        val confirm = state.value as? State.Confirm ?: return
        state.value = ready(confirm.target)
    }

    fun send() {
        val ready = state.value as? State.Ready ?: return
        if (ready.busy) return
        state.value = ready.copy(busy = true, error = null)
        viewModelScope.launchSafely(TAG) {
            // Signed in to a Tuned account: a one-time code for it goes along, so the TV joins the same account.
            val account = container.tunedAccount
            val link = if (account.signedIn.value) account.newLink() else null
            val box =
                withContext(Dispatchers.Default) {
                    attempt(TAG, "Couldn't seal the copy for the TV") {
                        val copy = HandoffAccount.withLink(container.syncCopy.handoffCopy(), link)
                        TvHandoff.seal(TvHandoff.pack(copy), ready.target)
                    }.getOrNull()
                }
            val outcome =
                when {
                    box == null -> R.string.send_tv_unreachable
                    box.size > TvHandoff.MAX_BOX_BYTES -> R.string.send_tv_too_big
                    else -> message(container.tvRelay.send(ready.target.code, box))
                }
            state.value = if (outcome == null) State.Sent else ready.copy(busy = false, error = outcome)
        }
    }

    private fun message(sent: TvRelay.Sent): Int? = when (sent) {
        TvRelay.Sent.Ok -> null
        TvRelay.Sent.Gone -> R.string.send_tv_gone
        TvRelay.Sent.AlreadySent -> R.string.send_tv_already
        TvRelay.Sent.TooMany -> R.string.send_tv_too_many
        TvRelay.Sent.Unreachable -> R.string.send_tv_unreachable
    }

    private companion object {
        const val TAG = "TvSignIn"
    }
}

@Composable
fun SendToTvScreen(
    link: String,
    onBack: () -> Unit,
    vm: SendToTvViewModel = viewModel(),
) {
    LaunchedEffect(link) { vm.start(link) }
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.send_tv_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                SendToTvViewModel.State.Empty -> Body(stringResource(R.string.send_tv_nothing))
                is SendToTvViewModel.State.Enter -> Enter(s, vm::lookUp)
                is SendToTvViewModel.State.Confirm -> Confirm(s, vm::confirmed)
                is SendToTvViewModel.State.Ready -> Ready(s, vm::send)
                SendToTvViewModel.State.Sent -> Text(stringResource(R.string.send_tv_sent), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ErrorText(id: Int?) {
    id?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun Enter(
    s: SendToTvViewModel.State.Enter,
    onNext: (String) -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    Body(stringResource(R.string.send_tv_enter))
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text(stringResource(R.string.send_tv_code)) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
        keyboardActions = KeyboardActions(onGo = { onNext(typed) }),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(s.error)
    Button(onClick = { onNext(typed) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.send_tv_next))
    }
}

@Composable
private fun Confirm(
    s: SendToTvViewModel.State.Confirm,
    onMatches: () -> Unit,
) {
    Body(stringResource(R.string.send_tv_confirm_digits))
    Text(s.digits, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    Button(onClick = onMatches, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.send_tv_matches)) }
}

@Composable
private fun Ready(
    s: SendToTvViewModel.State.Ready,
    onSend: () -> Unit,
) {
    Text(
        pluralStringResource(R.plurals.send_tv_playlists, s.playlists, s.playlists),
        style = MaterialTheme.typography.titleMedium,
    )
    Body(stringResource(R.string.send_tv_what))
    ErrorText(s.error)
    Button(onClick = onSend, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (s.busy) R.string.send_tv_sending else R.string.send_tv_send))
    }
}
