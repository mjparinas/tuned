package app.tuned.ui.offline

import android.app.Application
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.data.db.DownloadEntity
import app.tuned.data.db.DownloadRow
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.focusRing
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DownloadsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val offline = app.container.offline

    val page: StateFlow<List<DownloadRow>> = offline.rows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many are still to download, for the badge on the home screen's Downloads button. */
    val pending: StateFlow<Int> =
        offline.all
            .map { list -> list.count { it.state == DownloadEntity.QUEUED } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val byItem: StateFlow<Map<Long, DownloadEntity>> =
        offline.all
            .map { list ->
                list.associateBy { it.itemId }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun add(item: ItemEntity) {
        viewModelScope.launchSafely("Downloads") { offline.add(item) }
    }

    fun addAll(items: List<ItemEntity>) {
        viewModelScope.launchSafely("Downloads") { offline.addAll(items) }
    }

    fun hold(itemId: Long) {
        viewModelScope.launchSafely("Downloads") { offline.hold(itemId) }
    }

    fun remove(itemId: Long) {
        viewModelScope.launchSafely("Downloads") { offline.remove(itemId) }
    }

    fun retry(itemId: Long) {
        viewModelScope.launchSafely("Downloads") { offline.retry(itemId) }
    }
}

/** Download, its progress, done (tap to remove) or failed (tap to try again), for a film or episode. */
@Composable
fun DownloadButton(
    item: ItemEntity,
    vm: DownloadsViewModel = viewModel(),
) {
    val byItem by vm.byItem.collectAsStateWithLifecycle()
    val row = byItem[item.id]
    when (row?.state) {
        null -> {
            IconButton(onClick = { vm.add(item) }) {
                Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.download))
            }
        }

        DownloadEntity.DONE -> {
            IconButton(onClick = { vm.remove(item.id) }) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = stringResource(R.string.download_remove),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        DownloadEntity.FAILED -> {
            IconButton(onClick = { vm.retry(item.id) }) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.download_retry))
            }
        }

        else -> {
            IconButton(onClick = { vm.remove(item.id) }) {
                Box(contentAlignment = Alignment.Center) {
                    val fraction = row.fraction()
                    if (fraction == null) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

private fun DownloadEntity.fraction(): Float? = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null

/**
 * The Downloads page, from the bar at the bottom of the home screen: what's on its way (in the order it will
 * download, with progress, pause and cancel) and what's ready to watch offline (tap to play).
 */
@Composable
fun DownloadsScreen(
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    vm: DownloadsViewModel = viewModel(),
) {
    val rows by vm.page.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = remember(rows) { groupDownloads(rows.filter { it.download.state != DownloadEntity.DONE }, queueOrder = true) }
    val ready = remember(rows) { groupDownloads(rows.filter { it.download.state == DownloadEntity.DONE }, queueOrder = false) }
    val readyBytes = remember(rows) { rows.filter { it.download.state == DownloadEntity.DONE }.sumOf { it.download.bytes } }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.downloads), onBack) }) { padding ->
        if (rows.isEmpty()) {
            EmptyDownloads(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (active.isNotEmpty()) {
                item(key = "active") { SectionTitle(stringResource(R.string.downloads_section_active)) }
                groups(active, "active") { row ->
                    DownloadRowItem(row, onClick = null) {
                        ActiveActions(row.download, vm)
                    }
                }
            }
            if (ready.isNotEmpty()) {
                item(key = "ready") {
                    val size = Formatter.formatShortFileSize(context, readyBytes)
                    SectionTitle(stringResource(R.string.downloads_section_ready), stringResource(R.string.downloads_storage, size))
                }
                groups(ready, "ready") { row ->
                    DownloadRowItem(row, onClick = { onPlay(row.download.itemId) }) {
                        IconButton(onClick = { vm.remove(row.download.itemId) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.download_remove))
                        }
                    }
                }
            }
        }
    }
}

/** Each show under its name, then each season, then its episodes; films under their own heading. */
private fun LazyListScope.groups(
    groups: List<DownloadGroup>,
    section: String,
    row: @Composable (DownloadRow) -> Unit,
) {
    groups.forEach { group ->
        when (group) {
            is DownloadGroup.Show -> {
                item(key = "$section:${group.key}") {
                    GroupTitle(group.name, group.seasons.flatMap { it.rows })
                }
                group.seasons.forEach { season ->
                    season.number?.let { number ->
                        item(key = "$section:${group.key}:s$number") {
                            SeasonTitle(stringResource(R.string.season, number), season.rows)
                        }
                    }
                    items(season.rows, key = { "$section:${it.download.itemId}" }) { row(it) }
                }
            }

            is DownloadGroup.Movies -> {
                item(key = "$section:${group.key}") { GroupTitle(stringResource(R.string.tab_movies), group.rows) }
                items(group.rows, key = { "$section:${it.download.itemId}" }) { row(it) }
            }
        }
    }
}

/** A show's or the films' heading, with how many it holds. */
@Composable
private fun GroupTitle(
    title: String,
    rows: List<DownloadRow>,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(sizeOf(rows), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SeasonTitle(
    title: String,
    rows: List<DownloadRow>,
) {
    Text(
        listOf(title, pluralStringResource(R.plurals.episodes, rows.size, rows.size)).joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Space a group takes once saved; nothing while it's still downloading. */
@Composable
private fun sizeOf(rows: List<DownloadRow>): String {
    val done = rows.filter { it.download.state == DownloadEntity.DONE }
    return if (done.isEmpty()) "" else Formatter.formatShortFileSize(LocalContext.current, done.sumOf { it.download.bytes })
}

@Composable
private fun EmptyDownloads(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.DownloadForOffline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.downloads_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionTitle(
    title: String,
    trailing: String? = null,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Artwork, title and status, with [actions] at the end. Finished ones play on a tap. */
@Composable
private fun DownloadRowItem(
    row: DownloadRow,
    onClick: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    val d = row.download
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 78.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(model = row.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (onClick != null) {
                Icon(
                    Icons.Filled.PlayCircle,
                    null,
                    Modifier.size(28.dp),
                    tint = Color.White.copy(alpha = 0.9f),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val title = row.episode?.takeIf { row.showKey != null }?.let { stringResource(R.string.episode_label, it) } ?: d.name
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(status(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.state == DownloadEntity.QUEUED || d.state == DownloadEntity.PAUSED) {
                Spacer(Modifier.height(6.dp))
                val fraction = d.fraction()
                when {
                    fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    d.state == DownloadEntity.QUEUED && d.bytes > 0 -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        actions()
    }
}

@Composable
private fun status(d: DownloadEntity): String {
    val context = LocalContext.current
    val sizes =
        if (d.total > 0) {
            stringResource(
                R.string.download_progress,
                Formatter.formatShortFileSize(context, d.bytes),
                Formatter.formatShortFileSize(context, d.total),
            )
        } else {
            null
        }
    return when (d.state) {
        DownloadEntity.DONE -> Formatter.formatShortFileSize(context, d.bytes)
        DownloadEntity.FAILED -> stringResource(R.string.download_failed)
        DownloadEntity.PAUSED -> listOfNotNull(stringResource(R.string.download_paused), sizes).joinToString(" · ")
        else -> sizes ?: stringResource(R.string.download_waiting)
    }
}

/** Pause or resume (try again when it failed), and cancel. */
@Composable
private fun ActiveActions(
    d: DownloadEntity,
    vm: DownloadsViewModel,
) {
    when (d.state) {
        DownloadEntity.QUEUED -> {
            IconButton(onClick = { vm.hold(d.itemId) }) {
                Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.download_pause))
            }
        }

        DownloadEntity.PAUSED -> {
            IconButton(onClick = { vm.retry(d.itemId) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.download_resume))
            }
        }

        else -> {
            IconButton(onClick = { vm.retry(d.itemId) }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.download_retry))
            }
        }
    }
    IconButton(onClick = { vm.remove(d.itemId) }) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.download_cancel))
    }
}

/** "Download season", or how far the season has got once it's queued. */
@Composable
fun SeasonDownloadButton(
    episodes: List<ItemEntity>,
    vm: DownloadsViewModel = viewModel(),
) {
    if (episodes.isEmpty()) return
    val byItem by vm.byItem.collectAsStateWithLifecycle()
    val saved = episodes.count { byItem[it.id]?.state == DownloadEntity.DONE }
    val queued = episodes.count { byItem[it.id] != null }
    when {
        saved == episodes.size -> {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.download_season_done)) },
                leadingIcon = { Icon(Icons.Filled.DownloadDone, null, Modifier.size(18.dp)) },
            )
        }

        queued == episodes.size -> {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(pluralStringResource(R.plurals.download_season_progress, episodes.size, saved, episodes.size)) },
                leadingIcon = { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) },
            )
        }

        else -> {
            OutlinedButton(onClick = { vm.addAll(episodes) }) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(pluralStringResource(R.plurals.download_season, episodes.size - queued, episodes.size - queued))
            }
        }
    }
}
