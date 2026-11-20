package com.tunedplayer.app.ui.search

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.Titles
import com.tunedplayer.app.data.db.ChannelWithNow
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.ShowSummary
import com.tunedplayer.app.data.hiddenKeys
import com.tunedplayer.app.ui.browse.FavouriteButton
import com.tunedplayer.app.ui.browse.Logo
import com.tunedplayer.app.ui.browse.rememberShowArt
import com.tunedplayer.app.ui.common.VoiceSearchButton
import com.tunedplayer.app.ui.common.focusRing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** One search across Live TV, Movies and Shows, typed or spoken (also what the Assistant and TV remote open). */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    val query = MutableStateFlow("")

    data class Results(
        val channels: List<ChannelWithNow> = emptyList(),
        val movies: List<ItemEntity> = emptyList(),
        val shows: List<ShowSummary> = emptyList(),
    ) {
        val empty get() = channels.isEmpty() && movies.isEmpty() && shows.isEmpty()
    }

    val results =
        combine(
            query.debounce(200).distinctUntilChanged(),
            container.settings.settings,
        ) { q, s -> Triple(q, s.hiddenKeys(), s.showAdult) }
            .mapLatest { (q, hidden, adult) ->
                val text = Search.normalize(q).trim()
                if (text.isEmpty()) {
                    null
                } else {
                    val items = container.db.items()
                    val match = Search.match(text)
                    // The three lists are separate reads, so they run side by side on the database's reader connections.
                    coroutineScope {
                        val channels = async { items.searchChannels(text, match, System.currentTimeMillis(), hidden, adult, LIMIT) }
                        val movies = async { items.searchMovies(match, hidden, adult, LIMIT) }
                        val shows = async { items.searchShows(match, hidden, adult, LIMIT) }
                        Results(channels.await(), movies.await(), shows.await())
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which channels are favourites, live, so a heart fills the moment it's tapped. */
    val favourites = container.picks.favourites.value

    fun toggleFavourite(
        itemId: Long,
        favourite: Boolean,
    ) {
        container.picks.setFavourite(itemId, !favourite)
    }

    private companion object {
        const val LIMIT = 25
    }
}

@Composable
fun SearchScreen(
    initialQuery: String,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit,
    onOpenShow: (String, String) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onTune: (String) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    LaunchedEffect(initialQuery) { if (initialQuery.isNotBlank()) vm.query.value = initialQuery }
    val text by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initialQuery.isBlank()) runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    TextField(
                        value = text,
                        onValueChange = { vm.query.value = it },
                        placeholder = { Text(stringResource(R.string.search_everything)) },
                        trailingIcon = {
                            if (text.isNotEmpty()) {
                                IconButton(onClick = { vm.query.value = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                                }
                            } else {
                                VoiceSearchButton { vm.query.value = it }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors =
                            TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).focusRequester(focus),
                    )
                }
            }
        },
        floatingActionButton = {
            if (results?.channels?.isNotEmpty() == true) {
                ExtendedFloatingActionButton(
                    onClick = { onTune(text.trim()) },
                    icon = { Icon(Icons.Filled.Radar, contentDescription = null) },
                    text = { Text(stringResource(R.string.tune_button)) },
                )
            }
        },
    ) { padding ->
        val r = results
        if (r != null && r.empty) {
            Text(
                stringResource(R.string.search_nothing, text.trim()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (r == null) return@LazyColumn
            section(R.string.tab_live, r.channels) { c ->
                val favourite = c.id in favourites
                ResultRow(c.name, c.nowTitle ?: c.groupName, c.logo, trailing = {
                    FavouriteButton(favourite) { vm.toggleFavourite(c.id, favourite) }
                }) { onPlay(c.id) }
            }
            section(R.string.tab_movies, r.movies) { m -> ResultRow(Titles.display(m.name), m.groupName, m.logo) { onOpenMovie(m.id) } }
            section(R.string.tab_shows, r.shows) { s ->
                ResultRow(Titles.display(s.showName), s.groupName, rememberShowArt(s.showName, s.logo).image) {
                    onOpenShow(s.showKey, s.showName)
                }
            }
        }
    }
}

private fun <T> LazyListScope.section(
    title: Int,
    rows: List<T>,
    row: @Composable (T) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "h:$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(rows) { row(it) }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    image: String?,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(image, Modifier.size(44.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}
