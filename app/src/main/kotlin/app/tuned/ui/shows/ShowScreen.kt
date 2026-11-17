package app.tuned.ui.shows

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import app.tuned.R
import app.tuned.container
import app.tuned.data.db.HistoryEntity
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.SeenEpisodeEntity
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.focusRing
import app.tuned.ui.details.DetailsBody
import app.tuned.ui.details.DetailsViewModel
import app.tuned.ui.details.MetaLine
import app.tuned.ui.details.TrailerButton
import app.tuned.ui.library.LibraryViewModel
import app.tuned.ui.library.WatchLaterButton
import app.tuned.ui.offline.DownloadButton
import app.tuned.ui.offline.SeasonDownloadButton
import coil3.compose.AsyncImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        viewModelScope.launch {
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
    val seasons = episodes.map { it.season ?: 1 }.distinct().sorted()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val season = seasons.getOrNull(selected.coerceAtMost((seasons.size - 1).coerceAtLeast(0)))

    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (seasons.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = selected.coerceIn(0, seasons.size - 1), edgePadding = 12.dp) {
                    seasons.forEachIndexed { index, s ->
                        Tab(
                            selected = index == selected,
                            onClick = { selected = index },
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
                        DetailsBody(metadata, hasKey)
                    }
                }
                val shown = episodes.filter { season == null || (it.season ?: 1) == season }
                item(key = "season-actions") {
                    val allWatched = shown.isNotEmpty() && shown.all { progress[it.id].isWatched() }
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SeasonDownloadButton(shown)
                        if (shown.isNotEmpty()) {
                            TextButton(onClick = { library.setWatched(shown.map { it.id }, !allWatched) }) {
                                Text(stringResource(if (allWatched) R.string.mark_season_unwatched else R.string.mark_season_watched))
                            }
                        }
                    }
                }
                items(shown, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        progress[ep.id],
                        isNew = ep.id in fresh,
                        onClick = { onPlay(ep.id) },
                        onToggleWatched = { done -> library.setWatched(listOf(ep.id), done) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: ItemEntity,
    history: HistoryEntity?,
    isNew: Boolean,
    onClick: () -> Unit,
    onToggleWatched: (Boolean) -> Unit,
) {
    val watched = history.isWatched()
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.episode_label, episode.episode ?: 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isNew) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.episode_new),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(episode.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (history != null && !watched && history.durationMs > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.5f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    drawStopIndicator = {},
                )
            }
        }
        DownloadButton(episode)
    }
}

/** Played to the end (92%), or marked watched by hand. */
private fun HistoryEntity?.isWatched(): Boolean = this != null && durationMs > 0 && positionMs >= durationMs * WATCHED_PERCENT / 100

private const val WATCHED_PERCENT = 92
