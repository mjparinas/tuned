package com.tunedplayer.app.ui.onboarding

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.MediaServer
import com.tunedplayer.app.core.PlaylistLink
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.core.Streams
import com.tunedplayer.app.core.XtreamAccount
import com.tunedplayer.app.core.XtreamUrls
import com.tunedplayer.app.data.Credentials
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.DeviceClock
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.isTelevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What the form holds, before it becomes a [Credentials]. */
data class PlaylistDraft(
    val kind: Credentials.Kind,
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val link: String = "",
    val guide: String = "",
    /** A MAC portal's MAC address; its address is [server]. */
    val mac: String = "",
)

class AddPlaylistViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    data class State(
        val busy: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(State())
    private val container = app.container

    /** The saved playlist being edited, or null when adding one. */
    fun editing(index: Int): Credentials? = container.credentials.saved().getOrNull(index)

    /**
     * Checks the playlist (an Xtream login with the server's small account answer, a file by copying it in), saves
     * it and makes it the one in use, except an edit of a playlist that isn't in use, which only updates it.
     * [onDone] says whether the playlist in use changed, so the caller can open the lists.
     */
    fun save(
        draft: PlaylistDraft,
        file: Uri?,
        editing: Credentials?,
        onDone: (opened: Boolean) -> Unit,
    ) {
        if (state.value.busy) return
        state.update { State(busy = true) }
        viewModelScope.launchSafely("Playlists") {
            try {
                val login =
                    when (val result = build(draft, file, editing)) {
                        is Built.Error -> {
                            state.update { State(error = result.message) }
                            return@launchSafely
                        }

                        is Built.Ok -> {
                            // Extra guide links are set in Settings › Guide, so an edit keeps them.
                            result.login.copy(extraGuides = editing?.extraGuides.orEmpty())
                        }
                    }
                val active = container.credentials.load()
                val replaces = editing != null && !editing.samePlaylist(login)
                val opened =
                    if (editing != null && active?.samePlaylist(editing) != true) {
                        if (replaces) container.credentials.forget(editing)
                        container.credentials.remember(login)
                        false
                    } else {
                        if (replaces) container.credentials.forget(editing)
                        container.session.useLogin(login)
                        true
                    }
                state.update { State() }
                onDone(opened)
            } finally {
                // Whatever went wrong (and was logged), the button never stays stuck.
                if (state.value.busy) state.update { State(error = app.getString(R.string.login_failed)) }
            }
        }
    }

    private sealed interface Built {
        data class Ok(
            val login: Credentials,
        ) : Built

        data class Error(
            val message: String,
        ) : Built
    }

    private suspend fun build(
        draft: PlaylistDraft,
        file: Uri?,
        editing: Credentials?,
    ): Built {
        val name = draft.name.trim()
        val guide = draft.guide.trim()
        return when (draft.kind) {
            Credentials.Kind.Xtream -> {
                val server = XtreamUrls.normalizeServer(draft.server)
                xtream(Credentials(server, draft.username.trim(), draft.password, guideUrl = guide, name = name))
            }

            Credentials.Kind.Link -> {
                when (val link = PlaylistLink.parse(draft.link)) {
                    null -> Built.Error(app.getString(R.string.add_bad_link))

                    // A pasted Xtream link signs in properly, so catch-up and account status work too.
                    is PlaylistLink.Xtream -> xtream(Credentials(link.server, link.username, link.password, guideUrl = guide, name = name))

                    is PlaylistLink.M3u -> Built.Ok(Credentials("", "", "", link.url, guide, name = name))
                }
            }

            Credentials.Kind.Stalker -> {
                val mac = Stalker.normalizeMac(draft.mac) ?: return Built.Error(app.getString(R.string.add_bad_mac))
                stalker(Credentials.stalker(Stalker.normalizePortal(draft.server), mac, guide, name))
            }

            Credentials.Kind.Plex -> {
                media(
                    Credentials.mediaServer(
                        MediaServer.Product.Plex,
                        draft.server,
                        "",
                        draft.password.trim(),
                        guide,
                        name,
                    ),
                )
            }

            Credentials.Kind.Jellyfin -> {
                media(
                    Credentials.mediaServer(
                        MediaServer.Product.Jellyfin,
                        draft.server,
                        draft.username.trim(),
                        draft.password,
                        guide,
                        name,
                    ),
                )
            }

            Credentials.Kind.Emby -> {
                media(
                    Credentials.mediaServer(
                        MediaServer.Product.Emby,
                        draft.server,
                        draft.username.trim(),
                        draft.password,
                        guide,
                        name,
                    ),
                )
            }

            Credentials.Kind.File -> {
                val url =
                    when {
                        file != null -> copyIn(file) ?: return Built.Error(app.getString(R.string.add_bad_file))
                        editing != null -> editing.playlistUrl
                        else -> return Built.Error(app.getString(R.string.add_pick_file))
                    }
                Built.Ok(Credentials("", "", "", url, guide, name = name.ifBlank { file?.let(::displayName).orEmpty() }))
            }
        }
    }

    private suspend fun xtream(login: Credentials): Built {
        val error =
            try {
                if (check(login) == false) app.getString(R.string.login_wrong) else null
            } catch (e: IOException) {
                AppLog.w("Login", "Couldn't check the login", e)
                DeviceClock.explain(app, XtreamUrls.redact(e.message ?: e.javaClass.simpleName, login.username, login.password))
            } catch (e: IllegalArgumentException) {
                // OkHttp refuses an address it can't parse ("my server", "http://").
                AppLog.w("Login", "Not a server address", e)
                app.getString(R.string.login_bad_server)
            }
        return if (error == null) Built.Ok(login) else Built.Error(error)
    }

    /** A portal checks a MAC by handing the box a token; one it doesn't know gets none. */
    private suspend fun stalker(login: Credentials): Built {
        val error =
            try {
                val accepted = withContext(Dispatchers.IO) { container.stalker.portal(login).signIn() }
                if (accepted) null else app.getString(R.string.login_mac_refused)
            } catch (e: IOException) {
                AppLog.w("Login", "Couldn't reach the portal", e)
                XtreamUrls.redact(e.message ?: e.javaClass.simpleName, login.username, login.password)
            } catch (e: IllegalArgumentException) {
                AppLog.w("Login", "Not a portal address", e)
                app.getString(R.string.login_bad_server)
            }
        return if (error == null) Built.Ok(login) else Built.Error(error)
    }

    /** A Plex, Jellyfin or Emby server checks the login before the playlist is saved. */
    private suspend fun media(login: Credentials): Built {
        val error =
            try {
                val accepted =
                    withContext(Dispatchers.IO) {
                        when (login.kind) {
                            Credentials.Kind.Plex -> container.media.plex(login).signIn()
                            Credentials.Kind.Jellyfin, Credentials.Kind.Emby -> container.media.emby(login).signIn()
                            else -> false
                        }
                    }
                if (accepted) null else app.getString(R.string.login_media_refused)
            } catch (e: IOException) {
                AppLog.w("Login", "Couldn't reach the media server", e)
                XtreamUrls.redact(e.message ?: e.javaClass.simpleName, login.username, login.password)
            } catch (e: IllegalArgumentException) {
                AppLog.w("Login", "Not a media server address", e)
                app.getString(R.string.login_bad_server)
            }
        return if (error == null) Built.Ok(login) else Built.Error(error)
    }

    /** Copies a picked playlist into the app, so it still reads after the original moves. Null when it isn't one. */
    private suspend fun copyIn(uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val looksLikePlaylist =
            attempt("Playlists", "Couldn't read the picked file") {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(PEEK_BYTES)
                    val unzipped = Streams.maybeGunzip(input)
                    var read = 0
                    while (read < buffer.size) {
                        val n = unzipped.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    val head = String(buffer, 0, read)
                    "#EXTM3U" in head || "#EXTINF" in head
                }
            }.getOrNull() == true
        if (!looksLikePlaylist) return@withContext null
        val dir = container.session.playlistFiles.apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.m3u")
        val copied =
            attempt("Playlists", "Couldn't copy the picked file") {
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
        if (copied) target.toURI().toString() else null.also { target.delete() }
    }

    /** The picked file's name without its extension, to show and to name the playlist after. */
    fun displayName(uri: Uri): String {
        val name =
            attempt("Playlists", "Couldn't read the file's name") {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        return name.orEmpty().substringBeforeLast('.')
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
        const val PEEK_BYTES = 4_096
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/**
 * The form for one kind of playlist: an Xtream login, an M3U link or an M3U file, each with an optional name. Only
 * what that kind needs is asked. [editIndex] edits a saved playlist instead of adding one.
 */
@Composable
fun AddPlaylistScreen(
    kind: Credentials.Kind,
    editIndex: Int,
    onBack: () -> Unit,
    onDone: (opened: Boolean) -> Unit,
    vm: AddPlaylistViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editing = remember(editIndex) { vm.editing(editIndex) }
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var server by rememberSaveable {
        mutableStateOf(
            when (kind) {
                Credentials.Kind.Xtream, Credentials.Kind.Stalker,
                Credentials.Kind.Plex, Credentials.Kind.Jellyfin, Credentials.Kind.Emby,
                -> editing?.server.orEmpty()

                else -> ""
            },
        )
    }
    var mac by rememberSaveable { mutableStateOf(editing?.username?.takeIf { kind == Credentials.Kind.Stalker }.orEmpty()) }
    var username by rememberSaveable {
        mutableStateOf(
            editing
                ?.username
                ?.takeIf {
                    kind == Credentials.Kind.Xtream || kind == Credentials.Kind.Jellyfin ||
                        kind == Credentials.Kind.Emby
                }.orEmpty(),
        )
    }
    var password by rememberSaveable { mutableStateOf(editing?.password.orEmpty()) }
    var link by rememberSaveable { mutableStateOf(if (kind == Credentials.Kind.Link) editing?.playlistUrl.orEmpty() else "") }
    var guide by rememberSaveable { mutableStateOf(editing?.guideUrl.orEmpty()) }
    var file by rememberSaveable { mutableStateOf<Uri?>(null) }
    var fileName by rememberSaveable { mutableStateOf("") }
    // A TV's on-screen keyboard is slow to type on and hides what was typed, so there the password shows by default
    // (Melvin, 2026-11-19: "dont hide the password… we dont know if we mistyped it when we encounter an error").
    val tv = isTelevision()
    var showPassword by rememberSaveable { mutableStateOf(tv) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                file = uri
                fileName = vm.displayName(uri).ifBlank { uri.lastPathSegment.orEmpty() }
            }
        }
    val linkKind = PlaylistLink.parse(link)

    val canSubmit =
        !state.busy &&
            when (kind) {
                Credentials.Kind.Xtream, Credentials.Kind.Jellyfin, Credentials.Kind.Emby -> {
                    server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                }

                Credentials.Kind.Plex -> {
                    server.isNotBlank() && password.isNotBlank()
                }

                Credentials.Kind.Link -> {
                    linkKind != null
                }

                Credentials.Kind.File -> {
                    file != null || editing != null
                }

                Credentials.Kind.Stalker -> {
                    server.isNotBlank() && Stalker.normalizeMac(mac) != null
                }
            }
    val submit = {
        if (canSubmit) vm.save(PlaylistDraft(kind, name, server, username, password, link, guide, mac), file, editing, onDone)
    }
    // Pasting a whole Xtream link into the server box fills in all three.
    val onServer: (String) -> Unit = { text ->
        val parsed = PlaylistLink.parse(text) as? PlaylistLink.Xtream
        if (parsed != null && text.contains("password=", ignoreCase = true)) {
            server = parsed.server
            username = parsed.username
            password = parsed.password
        } else {
            server = text
        }
    }

    val title =
        stringResource(
            when (kind) {
                Credentials.Kind.Xtream -> R.string.kind_xtream
                Credentials.Kind.Link -> R.string.kind_link
                Credentials.Kind.File -> R.string.kind_file
                Credentials.Kind.Stalker -> R.string.kind_stalker
                Credentials.Kind.Plex -> R.string.kind_plex
                Credentials.Kind.Jellyfin -> R.string.kind_jellyfin
                Credentials.Kind.Emby -> R.string.kind_emby
            },
        )
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val field = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            when (kind) {
                Credentials.Kind.Xtream -> {
                    Hint(R.string.add_xtream_hint)
                    OutlinedTextField(
                        value = server,
                        onValueChange = onServer,
                        label = { Text(stringResource(R.string.login_server)) },
                        placeholder = { Text(stringResource(R.string.add_server_placeholder)) },
                        supportingText = { Text(stringResource(R.string.add_server_help)) },
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
                        // Ascii is Android's visible-password keyboard: no suggestions, and the keyboard shows what's typed.
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = if (showPassword) KeyboardType.Ascii else KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Next,
                            ),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription =
                                        stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                                )
                            }
                        },
                        modifier = field,
                    )
                    // A remote can't reach the eye inside the field (left and right move the cursor), so a TV also gets
                    // the choice as its own row.
                    if (tv) ShowPasswordRow(showPassword, field) { showPassword = it }
                }

                Credentials.Kind.Link -> {
                    Hint(R.string.add_link_hint)
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text(stringResource(R.string.login_playlist_url)) },
                        supportingText = {
                            if (linkKind is PlaylistLink.Xtream) Text(stringResource(R.string.add_link_is_xtream))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = field,
                    )
                }

                Credentials.Kind.Stalker -> {
                    Hint(R.string.add_stalker_hint)
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(R.string.login_portal)) },
                        supportingText = { Text(stringResource(R.string.add_portal_help)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = field,
                    )
                    OutlinedTextField(
                        value = mac,
                        onValueChange = { mac = it },
                        label = { Text(stringResource(R.string.login_mac)) },
                        supportingText = {
                            val typed = mac.isNotBlank() && Stalker.normalizeMac(mac) == null
                            Text(stringResource(if (typed) R.string.add_bad_mac else R.string.add_mac_help))
                        },
                        isError = mac.length >= MAC_LENGTH && Stalker.normalizeMac(mac) == null,
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                        modifier = field,
                    )
                }

                Credentials.Kind.Plex -> {
                    Hint(R.string.add_plex_hint)
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(R.string.login_server)) },
                        supportingText = { Text(stringResource(R.string.add_plex_server_help)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = field,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.login_token)) },
                        supportingText = { Text(stringResource(R.string.add_token_help)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = if (showPassword) KeyboardType.Ascii else KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Next,
                            ),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription =
                                        stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                                )
                            }
                        },
                        modifier = field,
                    )
                    if (tv) ShowPasswordRow(showPassword, field) { showPassword = it }
                }

                Credentials.Kind.Jellyfin, Credentials.Kind.Emby -> {
                    Hint(if (kind == Credentials.Kind.Jellyfin) R.string.add_jellyfin_hint else R.string.add_emby_hint)
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(R.string.login_server)) },
                        supportingText = { Text(stringResource(R.string.add_media_server_help)) },
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
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = if (showPassword) KeyboardType.Ascii else KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Next,
                            ),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription =
                                        stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                                )
                            }
                        },
                        modifier = field,
                    )
                    if (tv) ShowPasswordRow(showPassword, field) { showPassword = it }
                }

                Credentials.Kind.File -> {
                    Hint(R.string.add_file_hint)
                    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = field.heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(
                            when {
                                file != null -> fileName
                                editing != null -> stringResource(R.string.add_file_replace)
                                else -> stringResource(R.string.add_file_choose)
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.add_name)) },
                supportingText = { Text(stringResource(R.string.add_name_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = if (kind == Credentials.Kind.Xtream) ImeAction.Go else ImeAction.Next),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = field,
            )
            if (kind != Credentials.Kind.Xtream || guide.isNotBlank()) {
                OutlinedTextField(
                    value = guide,
                    onValueChange = { guide = it },
                    label = { Text(stringResource(R.string.add_guide)) },
                    supportingText = { Text(stringResource(R.string.add_guide_help)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = field,
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = submit, enabled = canSubmit, modifier = field.heightIn(min = 52.dp)) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (editing != null) R.string.add_save else R.string.add_submit))
                }
            }
            val checks =
                kind == Credentials.Kind.Xtream ||
                    kind == Credentials.Kind.Stalker ||
                    kind.isMediaServer ||
                    linkKind is PlaylistLink.Xtream
            if (state.busy && kind != Credentials.Kind.File && checks) {
                Text(
                    stringResource(R.string.login_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "00:1A:79:12:34:56": shorter than this is still being typed, so it isn't marked wrong yet. */
private const val MAC_LENGTH = 17

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    )
}

/** "Show password" as a row a remote can land on and press, for TVs. */
@Composable
private fun ShowPasswordRow(
    shown: Boolean,
    modifier: Modifier,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .focusRing(shape, MaterialTheme.colorScheme.primary)
            .toggleable(value = shown, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = shown, onCheckedChange = null)
        Text(stringResource(R.string.login_show_password), modifier = Modifier.padding(start = 12.dp))
    }
}
