package com.tunedplayer.app.ui.settings

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.UpdateInstallButton
import com.tunedplayer.app.ui.common.UpdateText
import com.tunedplayer.app.updates.GitHubUpdates
import com.tunedplayer.app.updates.pending

/**
 * Settings › Check for updates: asks GitHub now, whatever the automatic setting, and says what it found. When a new
 * version is ready, Install is right there, and after a tap the summary says which step the install is on. Copies from
 * Play update through Play, so this row isn't shown for them.
 */
@Composable
fun UpdateCheckRow() {
    val context = LocalContext.current
    val updates = context.container.updates
    if (remember { updates.fromPlay() }) return
    val state by updates.state.collectAsStateWithLifecycle()
    var outcome by remember { mutableStateOf<GitHubUpdates.Outcome?>(null) }
    val scope = rememberCoroutineScope()
    val ready = state.pending
    val checking = state as? GitHubUpdates.State.Checking
    val summary =
        when {
            ready != null -> UpdateText.detail(context, state) ?: stringResource(R.string.update_ready, ready.versionName)
            checking != null -> checkingText(checking)
            outcome == GitHubUpdates.Outcome.UP_TO_DATE -> stringResource(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
            outcome == GitHubUpdates.Outcome.FAILED -> stringResource(R.string.update_check_failed)
            else -> stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }
    SettingRow(
        stringResource(R.string.settings_check_now),
        summary,
        onClick = { if (state !is GitHubUpdates.State.Checking) scope.launchSafely("Updates") { outcome = updates.check(force = true) } },
        trailing =
            when {
                ready != null -> {
                    { UpdateInstallButton(state) { updates.install(context) } }
                }

                checking != null -> {
                    { RowSpinner() }
                }

                else -> {
                    null
                }
            },
    )
}

/** "Checking GitHub…", then "Downloading Tuned 2.5.0 · 12 MB of 40 MB" while the new version comes down. */
@Composable
private fun checkingText(state: GitHubUpdates.State.Checking): String {
    val name = state.versionName ?: return stringResource(R.string.update_checking)
    val context = LocalContext.current
    val size =
        if (state.total > 0) {
            stringResource(
                R.string.sync_size_of,
                Formatter.formatShortFileSize(context, state.done),
                Formatter.formatShortFileSize(context, state.total),
            )
        } else {
            stringResource(R.string.sync_size_so_far, Formatter.formatShortFileSize(context, state.done))
        }
    return stringResource(R.string.update_downloading, name) + " · " + size
}
