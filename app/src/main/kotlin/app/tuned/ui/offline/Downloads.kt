package app.tuned.ui.offline

import android.app.Application
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.ScreenLayout
import app.tuned.core.Titles
import app.tuned.data.EpisodeInfo
import app.tuned.data.Recordings.Recording
import app.tuned.data.db.DownloadEntity
import app.tuned.data.db.DownloadRow
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.launchSafely
import app.tuned.player.PlayerActivity
import app.tuned.premium.Premium
import app.tuned.ui.browse.Poster
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.focusRing
import app.tuned.ui.common.windowWidthDp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class DownloadsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val offline = app.container.offline
    private val recordingStore = app.container.recordings

    /** Recordings of live channels ([app.tuned.player.LiveRecorder]), newest first. */
    val recordings: StateFlow<List<Recording>> =
        recordingStore.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteRecording(r: Recording) {
        viewModelScope.launchSafely("Downloads") { withContext(Dispatchers.IO) { recordingStore.delete(r) } }
    }

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
        if (!roomFor(1)) return
        viewModelScope.launchSafely("Downloads") { offline.add(item) }
    }

    fun addAll(items: List<ItemEntity>) {
        val saved = byItem.value
        if (!roomFor(items.count { it.id !in saved })) return
        viewModelScope.launchSafely("Downloads") { offline.addAll(items) }
    }

    /** A free copy keeps [Premium.FREE_DOWNLOADS] downloads at once; more is Premium (docs/decisions/premium.md). */
    private fun roomFor(more: Int): Boolean {
        val premium = getApplication<Application>().container.premium
        if (premium.unlocked.value || byItem.value.size + more <= Premium.FREE_DOWNLOADS) return true
        return premium.allow(getApplication<Application>())
    }

    fun hold(itemId: Long) {
        viewModelScope.launchSafely("Downloads") { offline.hold(itemId) }
    }

    fun remove(itemId: Long) {
        viewModelScope.launchSafely("Downloads") { offline.remove(itemId) }
    }

    fun freeBytes(): Long? = offline.freeBytes()

    private val metadata = app.container.metadata
    private val episodeInfo = MutableStateFlow<Map<Long, EpisodeInfo>>(emptyMap())
    private val askedEpisodes = mutableSetOf<Long>()

    /** TMDB's name and screenshot for downloaded episodes, by item id, as they become known. */
    val episodes: StateFlow<Map<Long, EpisodeInfo>> = episodeInfo

    /**
     * Looks up an episode's TMDB name and screenshot: from the device when known, else one cached TMDB request for
     * its whole season, and nothing while offline. The page shows the provider's picture until then.
     */
    fun loadEpisode(row: DownloadRow) {
        val show = row.showName ?: return
        val season = row.season ?: return
        val episode = row.episode ?: return
        if (!askedEpisodes.add(row.download.itemId)) return
        viewModelScope.launchSafely("Downloads") {
            val info = metadata.cachedEpisode(show, season, episode) ?: metadata.episode(show, season, episode)
            if (info != null) episodeInfo.value += row.download.itemId to info
        }
    }

    private val posterInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    private val askedPosters = mutableSetOf<String>()

    /** TMDB's poster for each downloaded show, by show key, as they become known. */
    val posters: StateFlow<Map<String, String>> = posterInfo

    /**
     * Looks up a show's main poster on TMDB: from the device first, where it was saved with its episodes
     * ([app.tuned.data.SavedArtwork]), so it shows offline too; the network only when the device has none.
     */
    fun loadPoster(show: ShowDownloads) {
        if (!askedPosters.add(show.showKey)) return
        viewModelScope.launchSafely("Downloads") {
            val poster = metadata.summary(show.lookupName, tv = true)?.poster
            if (!poster.isNullOrBlank()) posterInfo.value += show.showKey to poster
        }
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

/** The poster grid's side padding, and how far its full-width rows step in so their text lines up at 16dp. */
private val GRID_PADDING = 12.dp
private val GRID_INSET = 4.dp

private fun DownloadEntity.fraction(): Float? = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null

/**
 * The Downloads page, from the bar at the bottom of the home screen (Melvin, 2026-11-17): each show once with its
 * poster, opening onto its seasons and then its episodes; films with their own controls; and how much is saved and
 * how much space is left on the device.
 */
@Composable
fun DownloadsScreen(
    onPlay: (Long) -> Unit,
    /** Null on Home, where Downloads is a section with the bar below rather than a page on top. */
    onBack: (() -> Unit)?,
    vm: DownloadsViewModel = viewModel(),
) {
    val rows by vm.page.collectAsStateWithLifecycle()
    val recorded by vm.recordings.collectAsStateWithLifecycle()
    val posters by vm.posters.collectAsStateWithLifecycle()
    val library = remember(rows, posters) { DownloadLibrary.of(rows, posters) }
    LaunchedEffect(library.shows) { library.shows.forEach(vm::loadPoster) }
    var showKey by rememberSaveable { mutableStateOf<String?>(null) }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    var inSeason by rememberSaveable { mutableStateOf(false) }
    // A show or season whose downloads were all removed closes by itself.
    val show = showKey?.let(library::show)
    val openSeason = show?.seasons?.firstOrNull { it.number == season }?.takeIf { inSeason }
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    LaunchedEffect(show?.rows) { show?.rows?.forEach(vm::loadEpisode) }
    // Up one level: from a season to its show, from a show to the list.
    val up: () -> Unit = {
        if (openSeason != null) {
            inSeason = false
        } else {
            showKey = null
        }
    }
    BackHandler(enabled = show != null, onBack = up)
    val title =
        when {
            show != null && openSeason != null -> seasonName(openSeason.number, show.name)
            show != null -> show.name
            else -> stringResource(R.string.downloads)
        }
    Scaffold(topBar = { BackTopBar(title, if (show != null) up else onBack) }) { padding ->
        if (rows.isEmpty() && recorded.isEmpty()) {
            EmptyDownloads(Modifier.padding(padding))
            return@Scaffold
        }
        if (show == null) {
            DownloadGrid(library, rows, recorded, vm, onPlay, Modifier.padding(padding)) {
                showKey = it
                inSeason = false
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (openSeason != null) {
                items(openSeason.rows, key = { it.download.itemId }) { row ->
                    SavedOrComing(row, episodes[row.download.itemId], vm, onPlay)
                }
            } else {
                items(show.seasons, key = { "s${it.number}" }) { s ->
                    GroupRow(
                        // TMDB's screenshot of its first episode, when known.
                        picture = s.rows.firstNotNullOfOrNull { episodes[it.download.itemId]?.still } ?: s.picture,
                        wide = true,
                        title = seasonName(s.number, null),
                        rows = s.rows,
                    ) {
                        season = s.number
                        inSeason = true
                    }
                }
            }
        }
    }
}

/**
 * The top of the Downloads page: shows and films as posters, like the Movies and Shows tabs (Melvin, 2026-11-18),
 * each with what's saved and how far the rest has got. Recordings stay a list under them.
 */
@Composable
private fun DownloadGrid(
    library: DownloadLibrary,
    rows: List<DownloadRow>,
    recorded: List<Recording>,
    vm: DownloadsViewModel,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenShow: (String) -> Unit,
) {
    val context = LocalContext.current
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ScreenLayout.posterMinDp(windowWidthDp()).dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = GRID_PADDING, end = GRID_PADDING, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "space", span = full) { SpaceSummary(rows, vm, inset = GRID_INSET) }
        if (library.shows.isNotEmpty()) {
            item(key = "shows", span = full) { SectionTitle(stringResource(R.string.tab_shows), inset = GRID_INSET) }
            items(library.shows, key = { "show:${it.showKey}" }) { s ->
                Column {
                    Poster(title = s.name, image = s.poster, subtitle = groupSummary(s.rows), onClick = { onOpenShow(s.showKey) })
                    if (s.rows.any { it.download.state != DownloadEntity.DONE }) {
                        LinearProgressIndicator(
                            progress = { s.savedFraction },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (library.movies.isNotEmpty()) {
            item(key = "movies", span = full) { SectionTitle(stringResource(R.string.tab_movies), inset = GRID_INSET) }
            items(library.movies, key = { it.download.itemId }) { row -> MoviePoster(row, vm, onPlay) }
        }
        if (recorded.isNotEmpty()) {
            item(key = "recordings", span = full) {
                val size = Formatter.formatShortFileSize(context, recorded.sumOf { it.bytes })
                SectionTitle(stringResource(R.string.recordings_section_size, size), inset = GRID_INSET)
            }
            items(recorded, key = { "recording:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { r ->
                RecordingRow(r, onPlay = { PlayerActivity.recording(context, it.id) }, onDelete = vm::deleteRecording, inset = GRID_INSET)
            }
        }
    }
}

/**
 * A downloaded film: plays on a tap, removed with the button under it (or the long-press menu); one on its way shows
 * progress, pause and cancel.
 */
@Composable
private fun MoviePoster(
    row: DownloadRow,
    vm: DownloadsViewModel,
    onPlay: (Long) -> Unit,
) {
    val d = row.download
    val done = d.state == DownloadEntity.DONE
    Column {
        Poster(
            title = Titles.display(d.name),
            image = row.logo,
            subtitle = status(d),
            onClick = { if (done) onPlay(d.itemId) },
            onPlayNow = if (done) ({ onPlay(d.itemId) }) else null,
            extraMenu = listOf(stringResource(if (done) R.string.download_remove else R.string.download_cancel) to { vm.remove(d.itemId) }),
        )
        if (done) {
            // Removing is a button under the poster, not only in its long-press menu, so a remote reaches it in one press.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { vm.remove(d.itemId) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.download_remove_named, Titles.display(d.name)))
                }
            }
        } else {
            val bar = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp)
            val fraction = d.fraction()
            when {
                fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = bar)
                d.state == DownloadEntity.QUEUED && d.bytes > 0 -> LinearProgressIndicator(bar)
                else -> LinearProgressIndicator(progress = { 0f }, modifier = bar)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ActiveActions(d, vm) }
        }
    }
}

@Composable
private fun seasonName(
    number: Int?,
    show: String?,
): String {
    val season = number?.let { stringResource(R.string.season, it) } ?: stringResource(R.string.downloads_other_episodes)
    return if (show == null) season else stringResource(R.string.downloads_show_season, show, season)
}

/** A saved film or episode plays on a tap and has a bin; one on its way shows progress, pause and cancel. */
@Composable
private fun SavedOrComing(
    row: DownloadRow,
    info: EpisodeInfo?,
    vm: DownloadsViewModel,
    onPlay: (Long) -> Unit,
) {
    if (row.download.state == DownloadEntity.DONE) {
        DownloadRowItem(row, info, onClick = { onPlay(row.download.itemId) }) {
            IconButton(onClick = { vm.remove(row.download.itemId) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.download_remove))
            }
        }
    } else {
        DownloadRowItem(row, info, onClick = null) { ActiveActions(row.download, vm) }
    }
}

/** How much the downloads take, and how much room is left on the device. */
@Composable
private fun SpaceSummary(
    rows: List<DownloadRow>,
    vm: DownloadsViewModel,
    inset: Dp = 16.dp,
) {
    val context = LocalContext.current
    val used = rows.sumOf { it.download.bytes }
    // Read again as downloads grow, so the free space keeps up.
    val free = remember(used) { vm.freeBytes() }
    val text =
        if (free != null) {
            stringResource(
                R.string.downloads_space,
                Formatter.formatShortFileSize(context, used),
                Formatter.formatShortFileSize(context, free),
            )
        } else {
            stringResource(R.string.downloads_space_used, Formatter.formatShortFileSize(context, used))
        }
    Column(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (free != null && used + free > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { used.toFloat() / (used + free) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A show (poster) or a season (picture of its first episode), with what's saved and what's on its way. */
@Composable
private fun GroupRow(
    picture: String?,
    wide: Boolean,
    title: String,
    rows: List<DownloadRow>,
    onClick: () -> Unit,
) {
    val summary = groupSummary(rows)
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .then(if (wide) Modifier.size(width = 128.dp, height = 72.dp) else Modifier.size(width = 52.dp, height = 78.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "5 episodes · 1.7 GB · 2 downloading": what's saved of a show or season, and what's on its way. */
@Composable
private fun groupSummary(rows: List<DownloadRow>): String {
    val context = LocalContext.current
    val saved = rows.filter { it.download.state == DownloadEntity.DONE }
    val coming = rows.size - saved.size
    return listOfNotNull(
        pluralStringResource(R.plurals.episodes, saved.size, saved.size).takeIf { saved.isNotEmpty() },
        Formatter.formatShortFileSize(context, saved.sumOf { it.download.bytes }).takeIf { saved.isNotEmpty() },
        pluralStringResource(R.plurals.downloads_coming, coming, coming).takeIf { coming > 0 },
    ).joinToString(" · ")
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
    inset: Dp = 16.dp,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 20.dp, bottom = 8.dp),
    )
}

/** Artwork, title and status, with [actions] at the end. Finished ones play on a tap. */
@Composable
private fun DownloadRowItem(
    row: DownloadRow,
    info: EpisodeInfo?,
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
        // Episodes show their screenshot (wide), films their poster (tall).
        val episode = row.showKey != null
        Box(
            Modifier
                .then(if (episode) Modifier.size(width = 128.dp, height = 72.dp) else Modifier.size(width = 52.dp, height = 78.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(
                model = info?.still ?: row.logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
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
            // TMDB's episode name ("Kamp Krusty") over its number, when known.
            val title =
                info?.name?.takeIf { episode }
                    ?: row.episode?.takeIf { episode }?.let { stringResource(R.string.episode_label, it) }
                    ?: Titles.display(d.name)
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
