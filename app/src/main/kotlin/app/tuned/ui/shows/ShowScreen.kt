package app.tuned.ui.shows

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.details.DetailsText
import app.tuned.ui.details.DetailsViewModel
import app.tuned.ui.details.TrailerButton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = app.container.db
    private val key = MutableStateFlow<String?>(null)

    fun load(showKey: String) {
        key.value = showKey
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
) {
    vm.load(showKey)
    LaunchedEffect(title) { details.load(title, tv = true) }
    val metadata by details.details.collectAsStateWithLifecycle()
    val hasKey by details.hasKey.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
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
                        DetailsText(metadata, hasKey)
                        metadata?.trailer?.let { TrailerButton(it) }
                    }
                }
                items(episodes.filter { season == null || (it.season ?: 1) == season }, key = { it.id }) { ep ->
                    EpisodeRow(ep, progress[ep.id], onClick = { onPlay(ep.id) })
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: ItemEntity,
    history: HistoryEntity?,
    onClick: () -> Unit,
) {
    val watched = history != null && history.durationMs > 0 && history.positionMs > history.durationMs * 0.92
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (watched) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = if (watched) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.episode_label, episode.episode ?: 1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    }
}
