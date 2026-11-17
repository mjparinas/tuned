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
import app.tuned.core.XtreamAccount
import app.tuned.core.XtreamUrls
import app.tuned.data.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The default server; editable here and in Settings (Melvin, 2026-11-17). */
const val DEFAULT_SERVER = "http://eros4k.ddns.net"

class LoginViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    data class State(
        val busy: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    private val container = app.container

    /**
     * Checks a server login with the server's small account answer (seconds, not the 400 MB playlist), saves it,
     * and opens the app. The playlist then downloads in the background with a progress bar; the first screen waits
     * for it.
     */
    fun signIn(
        credentials: Credentials,
        onDone: () -> Unit,
    ) {
        if (state.value.busy) return
        state.update { State(busy = true) }
        viewModelScope.launch {
            val login = credentials.copy(server = XtreamUrls.normalizeServer(credentials.server))
            val error =
                if (login.playlistUrl.isBlank()) {
                    try {
                        if (check(login) == false) app.getString(R.string.login_wrong) else null
                    } catch (e: IOException) {
                        XtreamUrls.redact(e.message ?: e.javaClass.simpleName, login.username, login.password)
                    }
                } else {
                    null
                }
            if (error != null) {
                state.update { State(error = error) }
                return@launch
            }
            container.credentials.save(login)
            container.settings.update { it.copy(lastSyncError = "") }
            container.scheduler.refreshNow(first = true)
            val s = container.settings.settings.first()
            container.scheduler.schedule(s.playlistRefreshHours, s.guideRefreshHours, s.unmeteredOnly)
            state.update { State() }
            onDone()
        }
    }

    /** True or false when the server says whether it accepts the login, null when it doesn't answer the question. */
    private suspend fun check(login: Credentials): Boolean? = withContext(Dispatchers.IO) {
        val ua =
            container.settings.settings
                .first()
                .userAgent
        val request =
            Request
                .Builder()
                .url(XtreamUrls.account(login.server, login.username, login.password))
                .apply { if (ua.isNotBlank()) header("User-Agent", ua) }
                .build()
        var last: IOException? = null
        repeat(CHECK_ATTEMPTS) { attempt ->
            try {
                return@withContext checkHttp.newCall(request).execute().use { response ->
                    when {
                        response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN -> false
                        !response.isSuccessful -> null
                        else -> XtreamAccount.accepted(response.body.string().take(MAX_ANSWER))
                    }
                }
            } catch (e: IOException) {
                last = e
                if (attempt < CHECK_ATTEMPTS - 1) delay((attempt + 1) * RETRY_DELAY_MS)
            }
        }
        throw last ?: IOException("Couldn't reach the server")
    }

    private val checkHttp by lazy {
        container.http
            .newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val CHECK_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
        const val MAX_ANSWER = 64 * 1024
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
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
                    stringResource(R.string.login_checking),
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
