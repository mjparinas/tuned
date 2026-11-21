package com.tunedplayer.app.ui.common

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.updates.GitHubUpdates
import com.tunedplayer.app.updates.GitHubUpdates.InstallProblem
import com.tunedplayer.app.updates.GitHubUpdates.InstallStep
import com.tunedplayer.app.updates.pending
import java.text.NumberFormat

/**
 * A newer Tuned was downloaded from GitHub: one tap installs it. Nothing shows otherwise. On Home, and on the welcome
 * screen, so a first install that is already out of date can update before a playlist is added. After the tap it says
 * which step the install is on, with a bar, until Android's installer takes over, and what went wrong with Try again
 * if it stops (Melvin, 2026-11-21).
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updates = context.container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    if (state.pending == null) return
    UpdateBannerContent(state, onInstall = { updates.install(context) }, modifier)
}

/** The banner for [state], drawn on its own for screenshots. */
@Composable
internal fun UpdateBannerContent(
    state: GitHubUpdates.State,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state.pending ?: return
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state is GitHubUpdates.State.Installing) {
                        stringResource(R.string.update_installing, ready.versionName)
                    } else {
                        stringResource(R.string.update_ready, ready.versionName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                UpdateInstallButton(state, onInstall)
            }
            UpdateText.detail(context, state)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (state is GitHubUpdates.State.InstallFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    modifier = Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state is GitHubUpdates.State.Installing) {
                Spacer(Modifier.height(6.dp))
                UpdateProgressBar(state, Modifier.padding(end = 8.dp, bottom = 8.dp).fillMaxWidth())
            }
        }
    }
}

/**
 * Install, a spinner while installing, or Try again after a problem: one button throughout, so a TV remote's focus stays
 * on it as the words change instead of falling off a button that went away. While installing, a press does nothing.
 */
@Composable
internal fun UpdateInstallButton(
    state: GitHubUpdates.State,
    onInstall: () -> Unit,
) {
    val installing = state is GitHubUpdates.State.Installing
    val busy = stringResource(R.string.update_busy)
    TextButton(
        onClick = { if (!installing) onInstall() },
        modifier =
            Modifier
                .focusRing(ButtonDefaults.textShape, MaterialTheme.colorScheme.primary)
                .semantics { if (installing) stateDescription = busy },
    ) {
        when (state) {
            is GitHubUpdates.State.Installing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is GitHubUpdates.State.InstallFailed -> Text(stringResource(R.string.retry))
            else -> Text(stringResource(R.string.update_install))
        }
    }
}

/** Determinate while the step has a size to count, otherwise the moving bar. */
@Composable
internal fun UpdateProgressBar(
    state: GitHubUpdates.State.Installing,
    modifier: Modifier = Modifier,
) {
    val fraction = state.fraction
    val shaped = modifier.height(6.dp).clip(RoundedCornerShape(3.dp))
    // On the banner's own colour: the default track is the secondary container, a green that clashes with it.
    val track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = TRACK_ALPHA)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = shaped, trackColor = track, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = shaped, trackColor = track)
    }
}

private const val TRACK_ALPHA = 0.24f

/** The words for an install under way or stopped, for the banner and Settings › Check for updates. */
object UpdateText {
    /**
     * "Step 1 of 3 · Downloading the update · 12 MB of 40 MB", "Step 2 of 3 · Preparing to install · 45%", or what went
     * wrong. Null when nothing is happening.
     */
    fun detail(
        context: Context,
        state: GitHubUpdates.State,
    ): String? = when (state) {
        is GitHubUpdates.State.Installing -> {
            listOfNotNull(
                context.getString(R.string.sync_step_of, state.step.number, GitHubUpdates.INSTALL_STEPS),
                context.getString(stepName(state.step)),
                amount(context, state),
            ).joinToString(" · ")
        }

        is GitHubUpdates.State.InstallFailed -> {
            context.getString(
                when (state.problem) {
                    InstallProblem.DOWNLOAD -> R.string.update_problem_download
                    InstallProblem.SPACE -> R.string.update_problem_space
                    InstallProblem.REFUSED, InstallProblem.OTHER -> R.string.update_problem_other
                },
            )
        }

        else -> {
            null
        }
    }

    private fun stepName(step: InstallStep): Int = when (step) {
        InstallStep.DOWNLOAD -> R.string.update_step_download
        InstallStep.CHECK -> R.string.update_step_check
        InstallStep.PREPARE -> R.string.update_step_prepare
        InstallStep.OPEN_INSTALLER -> R.string.update_step_open
        InstallStep.CONFIRM -> R.string.update_step_confirm
    }

    private fun amount(
        context: Context,
        state: GitHubUpdates.State.Installing,
    ): String? = when {
        state.step == InstallStep.DOWNLOAD && state.total > 0 -> {
            context.getString(
                R.string.sync_size_of,
                Formatter.formatShortFileSize(context, state.done),
                Formatter.formatShortFileSize(context, state.total),
            )
        }

        state.step == InstallStep.DOWNLOAD -> {
            context.getString(R.string.sync_size_so_far, Formatter.formatShortFileSize(context, state.done))
        }

        else -> {
            state.fraction?.let { NumberFormat.getPercentInstance().format(it.toDouble()) }
        }
    }
}
