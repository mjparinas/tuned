package com.tunedplayer.app.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.tunedplayer.app.updates.GitHubUpdates

/**
 * Settings › Check for updates: asks GitHub now, whatever the automatic setting, and says what it found. When a new
 * version is ready, Install is right there. Copies from Play update through Play, so this row isn't shown for them.
 */
@Composable
fun UpdateCheckRow() {
    val context = LocalContext.current
    val updates = context.container.updates
    if (remember { updates.fromPlay() }) return
    val state by updates.state.collectAsStateWithLifecycle()
    var outcome by remember { mutableStateOf<GitHubUpdates.Outcome?>(null) }
    val scope = rememberCoroutineScope()
    val ready = state as? GitHubUpdates.State.Ready
    val summary =
        when {
            ready != null -> stringResource(R.string.update_ready, ready.versionName)
            state is GitHubUpdates.State.Checking -> stringResource(R.string.update_checking)
            outcome == GitHubUpdates.Outcome.UP_TO_DATE -> stringResource(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
            outcome == GitHubUpdates.Outcome.FAILED -> stringResource(R.string.update_check_failed)
            else -> stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }
    SettingRow(
        stringResource(R.string.settings_check_now),
        summary,
        onClick = { if (state !is GitHubUpdates.State.Checking) scope.launchSafely("Updates") { outcome = updates.check(force = true) } },
        trailing =
            ready?.let {
                { TextButton(onClick = { updates.install(context) }) { Text(stringResource(R.string.update_install)) } }
            },
    )
}
