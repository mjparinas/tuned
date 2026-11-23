package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.ServerFlags
import com.tunedplayer.app.data.SubtitleAccount
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.isTelevision

/**
 * Settings › Playback › Subtitles, only in builds with the OpenSubtitles.com key (OPENSUBTITLES_API_KEY): whether a video with no
 * subtitles fetches them by itself, and an optional account for a higher daily limit.
 * The account is kept encrypted with the login, never in settings or backups.
 */
@Composable
fun SubtitleSettings(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
) {
    val flags by ServerFlags.now.collectAsState()
    if (BuildConfig.OPENSUBTITLES_API_KEY.isEmpty() || !flags.subtitleSearch) return
    SectionHeader(stringResource(R.string.settings_subtitles))
    SwitchRow(
        stringResource(R.string.settings_auto_subtitles),
        settings.autoSubtitles,
        stringResource(R.string.settings_auto_subtitles_summary),
    ) { on -> update { it.copy(autoSubtitles = on) } }
    // OpenSubtitles username/password needs typing; on a TV that stays on the phone (the saved account still works).
    if (isTelevision()) return
    val store = LocalContext.current.container.credentials
    var account by remember { mutableStateOf(store.subtitleAccount()) }
    var editAccount by remember { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.settings_opensubtitles_account),
        account?.let { stringResource(R.string.settings_opensubtitles_account_set, it.username) }
            ?: stringResource(R.string.settings_opensubtitles_account_summary),
        onClick = { editAccount = true },
    )
    if (editAccount) {
        AccountDialog(account, onDismiss = { editAccount = false }) { changed ->
            store.saveSubtitleAccount(changed)
            account = changed
        }
    }
}

@Composable
private fun AccountDialog(
    initial: SubtitleAccount?,
    onDismiss: () -> Unit,
    onSave: (SubtitleAccount?) -> Unit,
) {
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_opensubtitles_account)) },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.settings_opensubtitles_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_opensubtitles_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    onSave(SubtitleAccount(username.trim(), password))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            if (initial != null) {
                TextButton(onClick = {
                    onSave(null)
                    onDismiss()
                }) { Text(stringResource(R.string.settings_opensubtitles_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
