package app.tuned.ui.offline

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.data.Recordings.Recording
import app.tuned.ui.common.focusRing
import coil3.compose.AsyncImage

/** A recording of a live channel on the Downloads page, newest first: a tap plays it, the bin deletes it. */
@Composable
internal fun RecordingRow(
    r: Recording,
    onPlay: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    inset: Dp = 16.dp,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
            .clickable { onPlay(r) }
            .padding(start = inset, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 78.dp, height = 52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
            AsyncImage(model = r.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title ?: r.channel, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val started = DateUtils.formatDateTime(context, r.id, WHEN_FORMAT)
            val details =
                listOfNotNull(
                    r.channel.takeIf { r.title != null },
                    stringResource(R.string.recording_when, started, length(r.durationMs)),
                    Formatter.formatShortFileSize(context, r.bytes),
                ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onDelete(r) }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.recording_remove))
        }
    }
}

@Composable
private fun length(ms: Long): String {
    val minutes = (ms / MINUTE_MS).toInt().coerceAtLeast(1)
    return if (minutes >= MINUTES_PER_HOUR) {
        stringResource(R.string.recording_hours_minutes, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    } else {
        stringResource(R.string.recording_minutes, minutes)
    }
}

private const val MINUTE_MS = 60_000L
private const val WHEN_FORMAT =
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
private const val MINUTES_PER_HOUR = 60
