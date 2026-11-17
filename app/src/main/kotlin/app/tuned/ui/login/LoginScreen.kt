package app.tuned.ui.login

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.XtreamUrls
import app.tuned.data.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** The default server; editable here and in Settings (Melvin, 2026-11-17). */
const val DEFAULT_SERVER = "http://eros4k.ddns.net"

class LoginViewModel(
    app: Application,
) : AndroidViewModel(app) {
    data class State(
        val busy: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    private val container = app.container

    /** Saves the login, downloads the playlist once in the foreground (so the first screen has channels), then plans refreshes. */
    fun signIn(
        credentials: Credentials,
        onDone: () -> Unit,
    ) {
        if (state.value.busy) return
        state.update { State(busy = true) }
        viewModelScope.launch {
            container.credentials.save(credentials.copy(server = XtreamUrls.normalizeServer(credentials.server)))
            try {
                container.sync.refreshAll()
                val s = container.settings.settings.first()
                container.scheduler.schedule(s.playlistRefreshHours, s.guideRefreshHours, s.unmeteredOnly)
                state.update { State() }
                onDone()
            } catch (e: IOException) {
                container.credentials.clear()
                state.update { State(error = e.message ?: "Couldn't sign in") }
            }
        }
    }
}

@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var server by rememberSaveable { mutableStateOf(DEFAULT_SERVER) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var playlistUrl by rememberSaveable { mutableStateOf("") }
    var guideUrl by rememberSaveable { mutableStateOf("") }
    val canSubmit = !state.busy && ((server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) || playlistUrl.isNotBlank())
    val submit = {
        if (canSubmit) vm.signIn(Credentials(server, username.trim(), password, playlistUrl.trim(), guideUrl.trim()), onSignedIn)
    }

    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(88.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val field = Modifier.fillMaxWidth().widthIn(max = 480.dp)
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.login_server)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = field,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.login_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = field,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription =
                                stringResource(
                                    if (showPassword) R.string.login_hide_password else R.string.login_show_password,
                                ),
                        )
                    }
                },
                modifier = field,
            )
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.login_advanced)) }
            if (advanced) {
                OutlinedTextField(
                    value = playlistUrl,
                    onValueChange = { playlistUrl = it },
                    label = { Text(stringResource(R.string.login_playlist_url)) },
                    supportingText = { Text(stringResource(R.string.login_playlist_url_help)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = field,
                )
                OutlinedTextField(
                    value = guideUrl,
                    onValueChange = { guideUrl = it },
                    label = { Text(stringResource(R.string.login_guide_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = field,
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = submit, enabled = canSubmit, modifier = field.height(52.dp)) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.login_sign_in))
                }
            }
            if (state.busy) {
                Text(
                    stringResource(R.string.login_downloading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.login_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
