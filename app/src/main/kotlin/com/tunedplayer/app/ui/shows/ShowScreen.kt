package com.tunedplayer.app.ui.shows

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Episodes
import com.tunedplayer.app.core.Titles
import com.tunedplayer.app.data.CastMember
import com.tunedplayer.app.data.EpisodeInfo
import com.tunedplayer.app.data.db.HistoryEntity
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.SeenEpisodeEntity
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.common.startFocus
import com.tunedplayer.app.ui.details.DetailsBody
import com.tunedplayer.app.ui.details.DetailsViewModel
import com.tunedplayer.app.ui.details.MetaLine
import com.tunedplayer.app.ui.details.TrailerButton
import com.tunedplayer.app.ui.library.LibraryViewModel
import com.tunedplayer.app.ui.library.WatchLaterButton
import com.tunedplayer.app.ui.offline.DownloadButton
import com.tunedplayer.app.ui.offline.SeasonDownloadButton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = app.container.db
    private val key = MutableStateFlow<String?>(null)

    private val freshIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Episodes that arrived since the show was last opened, worked out once as it opens; then they all count as seen. */
    val fresh: StateFlow<Set<Long>> = freshIds

    fun load(showKey: String) {
        if (key.value == showKey) return
        key.value = showKey
        // A show read from the Xtream API fetches its episodes the first time it opens (core/XtreamApi).
        viewModelScope.launchSafely("Show") { getApplication<Application>().container.sync.loadShow(showKey) }
        viewModelScope.launchSafely("Show") {
            val list = db.items().episodes(showKey).first { it.isNotEmpty() }
            val seen = db.seenEpisodes().seen(showKey).toSet()
            // A show opened for the first time has nothing new: everything is simply there.
            freshIds.value = if (seen.isEmpty()) emptySet() else list.map { it.id }.filterNot { it in seen }.toSet()
            db.seenEpisodes().add(list.map { SeenEpisodeEntity(it.id, showKey) })
        }
    }

    val episodes: StateFlow<List<ItemEntity>> =
        key
            .flatMapLatest { k -> if (k == null) MutableStateFlow(emptyList()) else db.items().episodes(k) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val seasonInfo = MutableStateFlow<Map<String, Map<Int, EpisodeInfo>>>(emptyMap())
    private val askedSeasons = mutableSetOf<String>()

    /** TMDB's episodes (names, summaries, screenshots) by "tmdbId|season", then by episode number. */
    val episodeInfo: StateFlow<Map<String, Map<Int, EpisodeInfo>>> = seasonInfo

    /**
     * Fetches one season's episode details the first time its tab shows: what the device has at once, then anything
     * newer. Only the season on screen is asked for, so opening a show with twenty seasons costs one request.
     */
    fun loadSeason(
        tmdbId: Long,
        season: Int,
    ) {
        val key = seasonKey(tmdbId, season)
        if (!askedSeasons.add(key)) return
        val metadata = getApplication<Application>().container.metadata
        viewModelScope.launchSafely("Show") {
            metadata.cachedSeason(tmdbId, season)?.let { found -> seasonInfo.value += key to found }
            metadata.season(tmdbId, season)?.let { found -> seasonInfo.value += key to found }
        }
    }

    val progress: StateFlow<Map<Long, HistoryEntity>> =
        episodes
            .flatMapLatest { list -> db.library().progressFor(list.map { it.id }) }
            .map { rows -> rows.associateBy { it.itemId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

@Composable
fun ShowScreen(
    showKey: String,
    title: String,
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenPerson: (CastMember) -> Unit = {},
    vm: ShowViewModel = viewModel(),
    details: DetailsViewModel = viewModel(),
    library: LibraryViewModel = viewModel(),
) {
    vm.load(showKey)
    LaunchedEffect(title) { details.load(title, tv = true) }
    val metadata by details.details.collectAsStateWithLifecycle()
    val hasKey by details.hasKey.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val fresh by vm.fresh.collectAsStateWithLifecycle()
    val episodeInfo by vm.episodeInfo.collectAsStateWithLifecycle()
    val seasons = episodes.map { it.season ?: 1 }.distinct().sorted()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val season = seasons.getOrNull(selected.coerceAtMost((seasons.size - 1).coerceAtLeast(0)))

    val tmdbId = metadata?.tmdbId
    LaunchedEffect(tmdbId, season) {
        if (tmdbId != null && season != null) vm.loadSeason(tmdbId, season)
    }
    val seasonEpisodes = if (tmdbId != null && season != null) episodeInfo[seasonKey(tmdbId, season)] else null

    val start = rememberStartFocus()
    Scaffold(topBar = { BackTopBar(Titles.display(title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (seasons.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = selected.coerceIn(0, seasons.size - 1), edgePadding = 12.dp) {
                    seasons.forEachIndexed { index, s ->
                        Tab(
                            selected = index == selected,
                            onClick = { selected = index },
                            // On a TV the remote starts on the season showing, one press from its episodes.
                            modifier = if (index == selected) Modifier.startFocus(start) else Modifier,
                            text = { Text(stringResource(R.string.season, s)) },
                        )
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                item(key = "details") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        metadata?.backdrop?.let { image ->
                            AsyncImage(
                                model = image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        MetaLine(metadata)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            metadata?.trailer?.let { TrailerButton(it) }
                            WatchLaterButton(LibraryViewModel.show(showKey, title, metadata?.poster ?: episodes.firstOrNull()?.logo))
                        }
                        Spacer(Modifier.height(12.dp))
                        DetailsBody(metadata, hasKey, onOpenPerson = onOpenPerson)
                    }
                }
                val shown = episodes.filter { season == null || (it.season ?: 1) == season }
                item(key = "season-actions") {
                    val allWatched = shown.isNotEmpty() && shown.all { progress[it.id].isWatched() }
                    // A flow row: when both buttons don't fit side by side, the second goes on its own line
                    // instead of squeezing to one letter per line.
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SeasonDownloadButton(shown)
                        if (shown.isNotEmpty()) {
                            TextButton(onClick = { library.setWatched(shown.map { it.id }, !allWatched) }) {
                                Text(
                                    stringResource(if (allWatched) R.string.mark_season_unwatched else R.string.mark_season_watched),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                items(shown, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        seasonEpisodes?.get(ep.episode ?: 1),
                        progress[ep.id],
                        isNew = ep.id in fresh,
                        onClick = { onPlay(ep.id) },
                        onToggleWatched = { done -> library.setWatched(listOf(ep.id), done) },
                        // With one season, the remote starts on its first episode.
                        modifier = if (seasons.size <= 1 && ep.id == shown.first().id) Modifier.startFocus(start) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: ItemEntity,
    info: EpisodeInfo?,
    history: HistoryEntity?,
    isNew: Boolean,
    onClick: () -> Unit,
    onToggleWatched: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val watched = history.isWatched()
    Row(
        modifier
            .fillMaxWidth()
            .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The check marks the episode watched or unwatched by hand; the rest of the row plays it.
        IconButton(onClick = { onToggleWatched(!watched) }) {
            Icon(
                if (watched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = stringResource(if (watched) R.string.mark_unwatched else R.string.mark_watched),
                tint = if (watched) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            )
        }
        EpisodeStill(info?.still, history.takeIf { !watched })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val number = episode.episode ?: 1
            // TMDB's name ("Kamp Krusty") reads better than the provider's ("EN - The Simpsons (1989) S04 E01"). Without
            // one, the provider's own episode name if its title has one; otherwise the row says "Episode 1" once, not
            // "Episode 1" over "Harbour Town S01E01" (Melvin, 2026-11-17: real names over numbers).
            val name = info?.name ?: Episodes.title(episode.name)?.let(Titles::display)
            val runtime = info?.runtime
            val label =
                when {
                    name == null -> runtime
                    runtime == null -> stringResource(R.string.episode_label, number)
                    else -> stringResource(R.string.episode_label_runtime, number, runtime)
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                if (isNew) {
                    Text(
                        stringResource(R.string.episode_new),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                name ?: stringResource(R.string.episode_label, number),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            info?.overview?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DownloadButton(episode)
    }
}

/**
 * The episode's screenshot at 16:9, with how far it was watched along the bottom. A plain tile with a play mark
 * stands in while it loads and when TMDB has none, so rows never change height. Coil loads it only when the row
 * scrolls into view and keeps it on the device.
 */
@Composable
private fun EpisodeStill(
    still: String?,
    started: HistoryEntity?,
) {
    Box(
        Modifier
            .width(STILL_WIDTH)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (still != null) {
            AsyncImage(model = still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (started != null && started.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (started.positionMs.toFloat() / started.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
        }
    }
}

private val STILL_WIDTH = 112.dp

private fun seasonKey(
    tmdbId: Long,
    season: Int,
) = "$tmdbId|$season"

/** Played to the end (92%), or marked watched by hand. */
private fun HistoryEntity?.isWatched(): Boolean = this != null && durationMs > 0 && positionMs >= durationMs * WATCHED_PERCENT / 100

private const val WATCHED_PERCENT = 92
