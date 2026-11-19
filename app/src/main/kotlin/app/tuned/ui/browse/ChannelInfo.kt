package app.tuned.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tuned.R
import app.tuned.container
import app.tuned.data.db.ChannelWithNow
import app.tuned.data.db.ProgrammeEntity
import app.tuned.ui.common.rememberTimeLabels
import kotlinx.coroutines.flow.emptyFlow

/**
 * Live TV on a TV uses the big screen (Melvin, 2026-11-19: "use the big screen"): beside the channel list, what the
 * channel under the remote's focus is showing now and next, with the programme's description, from the guide on the
 * device. It is words and pictures only, never a preview of the channel, so nothing opens a second stream connection
 * (docs/decisions/scope.md). It takes no focus, so the arrows stay in the list.
 */
@Composable
internal fun ChannelInfoPane(
    channel: ChannelWithNow?,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(
            Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (channel == null) {
                Text(
                    stringResource(R.string.tv_live_pick),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ChannelDetails(channel)
            }
        }
    }
}

@Composable
private fun ChannelDetails(channel: ChannelWithNow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(channel.logo, Modifier.size(width = 96.dp, height = 64.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            if (channel.number > 0) {
                Text(
                    channel.number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Programmes(channel)
}

@Composable
private fun Programmes(channel: ChannelWithNow) {
    val db = LocalContext.current.container.db
    val tvgId = channel.tvgId
    val now = remember(channel.id) { System.currentTimeMillis() }
    val programmes by remember(tvgId, now) {
        tvgId?.let { db.programmes().upcoming(it, now, 2) } ?: emptyFlow()
    }.collectAsStateWithLifecycle(emptyList())
    if (programmes.isEmpty()) {
        Text(
            stringResource(R.string.tv_live_no_guide),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        programmes.forEachIndexed { i, p ->
            val onNow = i == 0 && p.start <= now
            Programme(p, if (onNow) R.string.tv_live_on_now else R.string.tv_live_next, onNow, now, full = i == 0)
        }
    }
}

@Composable
private fun Programme(
    p: ProgrammeEntity,
    label: Int,
    onNow: Boolean,
    now: Long,
    full: Boolean,
) {
    val times = rememberTimeLabels()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${stringResource(label)} · ${stringResource(R.string.tv_live_time_range, times.time(p.start), times.time(p.stop))}",
            style = MaterialTheme.typography.labelLarge,
            color = if (onNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onNow && p.stop > p.start) {
            val progress = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                drawStopIndicator = {},
            )
        }
        p.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (full) 6 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
