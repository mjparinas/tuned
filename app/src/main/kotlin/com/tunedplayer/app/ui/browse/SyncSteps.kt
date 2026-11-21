package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.ui.SyncText

/**
 * The first download's progress, as numbered steps (Melvin, 2026-11-20: "if there are other steps, it should mention
 * those as well, like processing, or inserting to database"): done steps are ticked, the current one has its own bar
 * and what it has got through (bytes of the full size, or how many channels so far), and the rest wait their turn.
 * Before the refresh first reports, a moving bar and a line say the download is on its way.
 *
 * [sideBySide] puts the title and the line about big playlists beside the steps rather than above and below them, for
 * a short, wide screen such as a TV: stacked, seven steps pushed that line off the bottom of Melvin's TV, where nothing
 * on the screen takes focus to scroll to it (Melvin, 2026-11-21: "there's like descriptive text that is slightly cut
 * off at the very bottom").
 */
@Composable
internal fun FirstDownload(
    progress: SyncProgress?,
    modifier: Modifier = Modifier,
    sideBySide: Boolean = false,
) {
    if (progress != null && sideBySide) {
        Row(modifier.widthIn(max = 880.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.first_run_loading), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Hint(stringResource(R.string.first_run_keeps_going), TextAlign.Start)
            }
            Spacer(Modifier.width(48.dp))
            Steps(progress, Modifier.weight(1.25f))
        }
        return
    }
    Column(modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.first_run_loading), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (progress == null) {
            SyncProgressBar(null, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Hint(stringResource(R.string.login_downloading))
            return@Column
        }
        Steps(progress, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Hint(stringResource(R.string.first_run_keeps_going))
    }
}

/** Every step of the plan in order, the running one with its bar and detail. */
@Composable
private fun Steps(
    progress: SyncProgress,
    modifier: Modifier = Modifier,
) {
    val steps = if (progress.step in progress.steps) progress.steps else progress.steps + progress.step
    val current = steps.indexOf(progress.step)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, step ->
            val state =
                when {
                    index < current -> StepState.Done
                    index == current -> StepState.Now
                    else -> StepState.Waiting
                }
            StepRow(index + 1, step, state, progress.takeIf { state == StepState.Now })
        }
    }
}

private enum class StepState { Done, Now, Waiting }

@Composable
private fun StepRow(
    number: Int,
    step: SyncProgress.Step,
    state: StepState,
    progress: SyncProgress?,
) {
    val words =
        stringResource(
            when (state) {
                StepState.Done -> R.string.sync_step_done
                StepState.Now -> R.string.sync_step_now
                StepState.Waiting -> R.string.sync_step_waiting
            },
        )
    val colours = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics(mergeDescendants = true) { stateDescription = words },
        verticalAlignment = Alignment.Top,
    ) {
        StepBadge(number, state)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            Text(
                stringResource(SyncText.name(step)),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (state == StepState.Now) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == StepState.Waiting) colours.onSurfaceVariant else colours.onSurface,
            )
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                SyncProgressBar(progress, Modifier.fillMaxWidth())
                SyncText.detail(LocalContext.current, progress)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colours.onSurfaceVariant)
                }
            }
        }
    }
}

/** The step's number in a circle: filled with a tick when done, tinted while running, outlined while waiting. */
@Composable
private fun StepBadge(
    number: Int,
    state: StepState,
) {
    val colours = MaterialTheme.colorScheme
    val shape = Modifier.size(28.dp).clip(CircleShape)
    val look =
        when (state) {
            StepState.Done -> shape.background(colours.primary)
            StepState.Now -> shape.background(colours.primaryContainer)
            StepState.Waiting -> shape.border(BorderStroke(1.5.dp, colours.outline), CircleShape)
        }
    Box(look, contentAlignment = Alignment.Center) {
        if (state == StepState.Done) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colours.onPrimary, modifier = Modifier.size(18.dp))
        } else {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (state == StepState.Now) colours.onPrimaryContainer else colours.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Hint(
    text: String,
    align: TextAlign = TextAlign.Center,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
    )
}

/** Determinate while the size is known, otherwise the moving bar. */
@Composable
internal fun SyncProgressBar(
    progress: SyncProgress?,
    modifier: Modifier = Modifier,
) {
    val fraction = progress?.fraction
    val shaped = modifier.height(6.dp).clip(RoundedCornerShape(3.dp))
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = shaped, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = shaped)
    }
}

/** The same place as [RefreshBar], between the tap and the refresh's first report: a moving bar and "Starting the refresh…". */
@Composable
internal fun RefreshStarting() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.settings_refresh_starting),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SyncProgressBar(null, Modifier.fillMaxWidth())
    }
}

/**
 * A thin bar and one line above the lists while a refresh the person started runs, so a slow server is never a
 * mystery: "Step 2 of 6 · Processing channels · 45% · 12,345 items".
 */
@Composable
internal fun RefreshBar(progress: SyncProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            SyncText.describe(LocalContext.current, progress),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SyncProgressBar(progress, Modifier.fillMaxWidth())
    }
}
