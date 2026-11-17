package app.tuned.ui.browse

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.tuned.R
import app.tuned.container
import app.tuned.core.ContentKind
import app.tuned.core.ScreenLayout
import app.tuned.data.AppSettings
import app.tuned.data.CastMember
import app.tuned.data.StartTab
import app.tuned.data.SyncProgress
import app.tuned.data.db.ChannelWithNow
import app.tuned.data.db.ContinueRow
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.ShowSummary
import app.tuned.data.db.WatchLaterEntity
import app.tuned.ui.SyncText
import app.tuned.ui.common.ChannelRowSkeleton
import app.tuned.ui.common.SKELETON_ROWS
import app.tuned.ui.common.VoiceSearchButton
import app.tuned.ui.common.firstLoad
import app.tuned.ui.common.focusRing
import app.tuned.ui.common.skeleton
import app.tuned.ui.common.windowWidthDp
import app.tuned.ui.guide.GuideGridView
import app.tuned.ui.library.LibraryViewModel
import app.tuned.ui.library.watchLaterRow
import app.tuned.ui.offline.DownloadsViewModel
import app.tuned.updates.GitHubUpdates
import coil3.compose.AsyncImage

internal data class Tab(
    val kind: ContentKind,
    val label: Int,
    val icon: ImageVector,
    val search: Int,
)

internal val tabs =
    listOf(
        Tab(ContentKind.LIVE, R.string.tab_live, Icons.Filled.Tv, R.string.search_live),
        Tab(ContentKind.MOVIE, R.string.tab_movies, Icons.Filled.Movie, R.string.search_movies),
        Tab(ContentKind.SERIES, R.string.tab_shows, Icons.Filled.VideoLibrary, R.string.search_shows),
    )

@Composable
fun HomeScreen(
    settings: AppSettings,
    onPlay: (Long) -> Unit,
    onOpenShow: (String, String) -> Unit,
    onOpenGuide: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenPerson: (CastMember) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenEvents: () -> Unit,
    onTune: (String) -> Unit,
    onSignedOut: () -> Unit,
    vm: BrowseViewModel = viewModel(),
) {
    val start =
        when (settings.startTab) {
            StartTab.LIVE -> 0
            StartTab.MOVIES -> 1
            StartTab.SHOWS -> 2
            StartTab.LAST -> settings.lastTab.coerceIn(0, 2)
        }
    var selected by rememberSaveable { mutableIntStateOf(start) }
    val tab = tabs[selected]
    val empty by vm.empty.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val liveSearch by vm.search(ContentKind.LIVE).collectAsStateWithLifecycle()
    val widthDp = windowWidthDp()
    val rail = ScreenLayout.rail(widthDp)
    val panes = ScreenLayout.panes(widthDp)
    // On a wide screen a film, show or guide opens beside the list; on anything narrower it opens full screen.
    var detail by rememberSaveable(stateSaver = Detail.saver) { mutableStateOf<Detail?>(null) }
    LaunchedEffect(panes) {
        // Folded, rotated or put in split screen with a pane open: carry on with that page full screen.
        val open = detail
        if (!panes && open != null) {
            detail = null
            when (open.kind) {
                Detail.Kind.MOVIE -> onOpenMovie(open.id)
                Detail.Kind.SHOW -> onOpenShow(open.showKey, open.title)
                Detail.Kind.GUIDE -> onOpenGuide(open.id)
            }
        }
    }
    BackHandler(enabled = detail != null) { detail = null }
    val openShow: (String, String) -> Unit = if (panes) { key, title -> detail = Detail.show(key, title) } else onOpenShow
    val openMovie: (Long) -> Unit = if (panes) { id -> detail = Detail.movie(id) } else onOpenMovie
    val openGuide: (Long) -> Unit = if (panes) { id -> detail = Detail.guide(id) } else onOpenGuide
    val select: (Int) -> Unit = { index ->
        selected = index
        vm.rememberTab(index)
    }

    Row(Modifier.fillMaxSize()) {
        if (rail) HomeRail(selected, select, onOpenDownloads)
        Scaffold(
            modifier = Modifier.weight(1 - ScreenLayout.DETAIL_FRACTION),
            floatingActionButton = {
                // Auto-tune: with a search on Live TV (a channel or an event, say "UFC"), find one that works and play it.
                if (tab.kind == ContentKind.LIVE && liveSearch.isNotBlank()) {
                    ExtendedFloatingActionButton(
                        onClick = { onTune(liveSearch.trim()) },
                        icon = { Icon(Icons.Filled.Radar, contentDescription = null) },
                        text = { Text(stringResource(R.string.tune_button)) },
                    )
                }
            },
            topBar = {
                val search = vm.search(tab.kind)
                val text by search.collectAsStateWithLifecycle()
                SearchBarRow(
                    text = text,
                    hint = stringResource(tab.search),
                    onChange = { search.value = it },
                    grid = if (tab.kind == ContentKind.LIVE) settings.liveGrid else null,
                    onToggleGrid = { vm.setLiveGrid(!settings.liveGrid) },
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings,
                    onOpenEvents = onOpenEvents.takeIf { tab.kind == ContentKind.LIVE },
                )
            },
            bottomBar = {
                if (!rail) {
                    NavigationBar {
                        tabs.forEachIndexed { index, t ->
                            NavigationBarItem(
                                selected = index == selected,
                                onClick = { select(index) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(stringResource(t.label)) },
                            )
                        }
                        DownloadsNavItem(onOpenDownloads)
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (empty == true) {
                    FirstRun(
                        refreshing = refreshing,
                        progress = progress,
                        error = lastError,
                        onRetry = vm::refresh,
                        onChangeLogin = { vm.signOut(onSignedOut) },
                    )
                    return@Column
                }
                progress?.let { RefreshBar(it) }
                UpdateBanner()
                CategoryChips(vm, tab.kind)
                val lists: @Composable () -> Unit = {
                    when (tab.kind) {
                        ContentKind.LIVE -> LiveTab(vm, settings, onPlay, openGuide)
                        ContentKind.MOVIE -> MovieGrid(vm, onPlay, openMovie)
                        ContentKind.SERIES -> ShowGrid(vm, openShow, onPlay)
                    }
                }
                // Pull to refresh is a setting, off by default; Settings › Refresh now always works.
                if (settings.pullToRefresh) {
                    PullToRefreshBox(
                        isRefreshing = refreshing && progress == null,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        lists()
                    }
                } else {
                    Box(Modifier.fillMaxSize()) { lists() }
                }
            }
        }
        val open = detail
        if (panes && open != null) {
            VerticalDivider()
            Box(Modifier.weight(ScreenLayout.DETAIL_FRACTION).fillMaxHeight()) {
                DetailPane(open, onPlay = onPlay, onClose = { detail = null }, onOpenPerson = onOpenPerson)
            }
        }
    }
}

/** A newer Tuned was downloaded from GitHub: one tap installs it. Nothing shows otherwise. */
@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val updates = context.container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val ready = state as? GitHubUpdates.State.Ready ?: return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.update_ready, ready.versionName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { updates.install(context) }) { Text(stringResource(R.string.update_install)) }
        }
    }
}

@Composable
private fun SearchBarRow(
    text: String,
    hint: String,
    onChange: (String) -> Unit,
    grid: Boolean?,
    onToggleGrid: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Live Events, shown on the Live TV tab only. */
    onOpenEvents: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = text,
                onValueChange = onChange,
                placeholder = { Text(hint) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { onChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    } else {
                        VoiceSearchButton(onResult = onChange)
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.weight(1f).height(52.dp),
            )
            // Live TV only: switch between the channel list and the grid guide.
            if (grid != null) {
                IconButton(onClick = onToggleGrid) {
                    Icon(
                        if (grid) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                        contentDescription = stringResource(if (grid) R.string.grid_hide else R.string.grid_show),
                    )
                }
            }
            if (onOpenEvents != null) {
                IconButton(onClick = onOpenEvents) {
                    Icon(Icons.Filled.EmojiEvents, contentDescription = stringResource(R.string.events_title))
                }
            }
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.TravelExplore, contentDescription = stringResource(R.string.search_everything))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
    }
}

@Composable
private fun CategoryChips(
    vm: BrowseViewModel,
    kind: ContentKind,
) {
    val groups by vm.groups(kind).collectAsStateWithLifecycle()
    val filterFlow = vm.filter(kind)
    val filter by filterFlow.collectAsStateWithLifecycle()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(selected = filter == Filter.All, onClick = {
                filterFlow.value = Filter.All
            }, label = { Text(stringResource(R.string.filter_all)) })
        }
        item(key = "fav") {
            FilterChip(
                selected = filter == Filter.Favourites,
                onClick = { filterFlow.value = Filter.Favourites },
                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(stringResource(R.string.filter_favourites)) },
            )
        }
        if (kind == ContentKind.LIVE) {
            item(key = "recent") {
                FilterChip(
                    selected = filter == Filter.Recent,
                    onClick = { filterFlow.value = if (filter == Filter.Recent) Filter.All else Filter.Recent },
                    leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(stringResource(R.string.filter_recent)) },
                )
            }
        }
        items(groups, key = { "g:" + it.groupName }) { g ->
            val on = filter == Filter.Group(g.groupName)
            val context = LocalContext.current
            val hiddenMessage = stringResource(R.string.group_hidden, g.groupName)
            FilterChip(
                selected = on,
                onClick = { filterFlow.value = if (on) Filter.All else Filter.Group(g.groupName) },
                label = { Text(g.groupName) },
                // The chosen group can be hidden from here; Settings › Hidden groups brings it back.
                trailingIcon =
                    if (on) {
                        {
                            Icon(
                                Icons.Filled.VisibilityOff,
                                contentDescription = stringResource(R.string.group_hide),
                                modifier =
                                    Modifier.size(18.dp).clickable {
                                        vm.hideGroup(kind, g.groupName)
                                        Toast
                                            .makeText(
                                                context,
                                                hiddenMessage,
                                                Toast.LENGTH_LONG,
                                            ).show()
                                    },
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

/** Live TV as the channel list, or as the grid guide when that's switched on. */
@Composable
private fun LiveTab(
    vm: BrowseViewModel,
    settings: AppSettings,
    onPlay: (Long) -> Unit,
    onOpenGuide: (Long) -> Unit,
) {
    if (settings.liveGrid) {
        GuideGridView(vm.channels.collectAsLazyPagingItems(), settings.guideDays, onPlay, onOpenGuide)
    } else {
        LiveList(vm, settings.showGuideInList, onPlay, onOpenGuide)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveList(
    vm: BrowseViewModel,
    showGuide: Boolean,
    onPlay: (Long) -> Unit,
    onOpenGuide: (Long) -> Unit,
) {
    val channels = vm.channels.collectAsLazyPagingItems()
    val favourites by vm.favouriteIds.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (channels.firstLoad) items(SKELETON_ROWS) { ChannelRowSkeleton() }
        items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
            val channel = channels[index]
            if (channel == null) {
                ChannelRowSkeleton()
            } else {
                val favourite = channel.id in favourites
                ChannelRow(
                    channel = channel,
                    favourite = favourite,
                    showGuide = showGuide,
                    onPlay = { onPlay(channel.id) },
                    onGuide = { onOpenGuide(channel.id) },
                    onToggleFavourite = { vm.toggleFavourite(channel.id, favourite) },
                )
            }
        }
    }
}

/** One tap to favourite a channel or take it off: a filled heart in the accent colour when it is a favourite. */
@Composable
internal fun FavouriteButton(
    favourite: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        if (favourite) {
            Icon(Icons.Filled.Favorite, stringResource(R.string.favourite_remove), tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(Icons.Filled.FavoriteBorder, stringResource(R.string.favourite_add))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: ChannelWithNow,
    favourite: Boolean,
    showGuide: Boolean,
    onPlay: () -> Unit,
    onGuide: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Each channel is a rounded card, like a row on a modern website.
    Box(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier
                .fillMaxWidth()
                .focusRing(MaterialTheme.shapes.medium, MaterialTheme.colorScheme.primary)
                .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
                .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(channel.logo, Modifier.size(width = 64.dp, height = 44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                val now = channel.nowTitle
                if (showGuide && now != null) {
                    Text(
                        now,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val start = channel.nowStart ?: 0
                    val stop = channel.nowStop ?: 0
                    if (stop > start) {
                        val progress = ((System.currentTimeMillis() - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier =
                                Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(0.6f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            drawStopIndicator = {},
                        )
                    }
                }
            }
            FavouriteButton(favourite, onToggleFavourite)
            if (channel.tvgId != null) {
                IconButton(onClick = onGuide) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.guide_open))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add)) },
                onClick = {
                    menu = false
                    onToggleFavourite()
                },
            )
            if (channel.tvgId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.guide_open)) },
                    onClick = {
                        menu = false
                        onGuide()
                    },
                )
            }
        }
    }
}

@Composable
private fun MovieGrid(
    vm: BrowseViewModel,
    onPlay: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
) {
    val movies = vm.movies.collectAsLazyPagingItems()
    val favourites by vm.favouriteIds.collectAsStateWithLifecycle()
    val library: LibraryViewModel = viewModel()
    val watched by library.watched.collectAsStateWithLifecycle()
    val later by library.laterKeys.collectAsStateWithLifecycle()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ScreenLayout.posterMinDp(windowWidthDp()).dp),
        state = rememberLazyGridState(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        continueWatching(vm, ContentKind.MOVIE, onPlay)
        watchLaterRow(shows = false, visible = { fullList(vm, ContentKind.MOVIE) }, onOpenMovie = onOpenMovie, onOpenShow = { _, _ -> })
        if (movies.firstLoad) items(SKELETON_ROWS) { PosterPlaceholder() }
        items(count = movies.itemCount, key = movies.itemKey { it.id }) { index ->
            val movie: ItemEntity? = movies[index]
            if (movie == null) {
                PosterPlaceholder()
            } else {
                val favourite = movie.id in favourites
                val rating by produceState<Double?>(null, movie.name) { value = vm.rating(movie.name, tv = false) }
                // A tap opens the film's page; Play now is in the long-press menu for a straight start.
                val done = movie.id in watched
                val saved = WatchLaterEntity.movieKey(movie.id) in later
                Poster(
                    title = movie.name,
                    image = movie.logo,
                    favourite = favourite,
                    rating = rating,
                    watched = done,
                    onClick = { onOpenMovie(movie.id) },
                    onToggleFavourite = { vm.toggleFavourite(movie.id, favourite) },
                    onPlayNow = { onPlay(movie.id) },
                    extraMenu =
                        listOf(
                            stringResource(if (saved) R.string.watch_later_remove else R.string.watch_later_add) to {
                                library.toggleLater(LibraryViewModel.movie(movie.id, movie.name, movie.logo))
                            },
                            stringResource(if (done) R.string.mark_unwatched else R.string.mark_watched) to {
                                library.setWatched(listOf(movie.id), !done)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun ShowGrid(
    vm: BrowseViewModel,
    onOpenShow: (String, String) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val shows = vm.shows.collectAsLazyPagingItems()
    val library: LibraryViewModel = viewModel()
    val later by library.laterKeys.collectAsStateWithLifecycle()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ScreenLayout.posterMinDp(windowWidthDp()).dp),
        state = rememberLazyGridState(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        continueWatching(vm, ContentKind.SERIES, onPlay)
        watchLaterRow(shows = true, visible = { fullList(vm, ContentKind.SERIES) }, onOpenMovie = {}, onOpenShow = onOpenShow)
        if (shows.firstLoad) items(SKELETON_ROWS) { PosterPlaceholder() }
        items(count = shows.itemCount, key = shows.itemKey { it.showKey }) { index ->
            val show: ShowSummary? = shows[index]
            if (show == null) {
                PosterPlaceholder()
            } else {
                val rating by produceState<Double?>(null, show.showName) { value = vm.rating(show.showName, tv = true) }
                Poster(
                    title = show.showName,
                    image = show.logo,
                    rating = rating,
                    subtitle =
                        if (show.newEpisodes > 0) {
                            stringResource(
                                R.string.episodes_with_new,
                                pluralEpisodes(show.episodes),
                                pluralStringResource(R.plurals.new_episodes, show.newEpisodes, show.newEpisodes),
                            )
                        } else {
                            pluralEpisodes(show.episodes)
                        },
                    onClick = { onOpenShow(show.showKey, show.showName) },
                    extraMenu =
                        listOf(
                            stringResource(
                                if (WatchLaterEntity.showKey(show.showKey) in
                                    later
                                ) {
                                    R.string.watch_later_remove
                                } else {
                                    R.string.watch_later_add
                                },
                            ) to { library.toggleLater(LibraryViewModel.show(show.showKey, show.showName, show.logo)) },
                        ),
                )
            }
        }
    }
}

/**
 * Continue Watching: a row across the top of Movies or Shows with what was started and not finished. Shows only on
 * the full list (no category, favourites or search), so it never pushes a search result down.
 */
private fun LazyGridScope.continueWatching(
    vm: BrowseViewModel,
    kind: ContentKind,
    onPlay: (Long) -> Unit,
) {
    item(key = "continue", span = { GridItemSpan(maxLineSpan) }, contentType = "continue") {
        val rows by vm.continueWatching(kind).collectAsStateWithLifecycle()
        val names by vm.continueNames.collectAsStateWithLifecycle()
        if (rows.isEmpty() || !fullList(vm, kind)) return@item
        Column {
            Text(
                stringResource(R.string.continue_watching),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.id }) { row ->
                    LaunchedEffect(row.id) { vm.loadEpisodeName(row) }
                    ContinueCard(row, names[row.id], onClick = { onPlay(row.id) })
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** True on the whole list: no category, favourites or search, where the rows across the top belong. */
@Composable
private fun fullList(
    vm: BrowseViewModel,
    kind: ContentKind,
): Boolean {
    val filter by vm.filter(kind).collectAsStateWithLifecycle()
    val search by vm.search(kind).collectAsStateWithLifecycle()
    return filter == Filter.All && search.isBlank()
}

@Composable
private fun ContinueCard(
    row: ContinueRow,
    episodeName: String?,
    onClick: () -> Unit,
) {
    // TMDB's episode name ("The Simpsons · Kamp Krusty") reads better than its number, when it's known.
    val title =
        when {
            row.showName != null && episodeName != null -> {
                stringResource(R.string.continue_episode_named, row.showName, episodeName)
            }

            row.showName != null && row.season != null && row.episode != null -> {
                stringResource(R.string.continue_episode, row.showName, row.season, row.episode)
            }

            else -> {
                row.name
            }
        }
    Column(
        Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(12.dp))
            .focusRing(RoundedCornerShape(12.dp), MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(model = row.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            LinearProgressIndicator(
                progress = { row.fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                drawStopIndicator = {},
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun pluralEpisodes(count: Int): String = pluralStringResource(R.plurals.episodes, count, count)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Poster(
    title: String,
    image: String?,
    onClick: () -> Unit,
    subtitle: String? = null,
    favourite: Boolean = false,
    onToggleFavourite: (() -> Unit)? = null,
    onPlayNow: (() -> Unit)? = null,
    rating: Double? = null,
    watched: Boolean = false,
    /** More long-press menu entries: label and action. */
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var menu by remember { mutableStateOf(false) }
    val hasMenu = onToggleFavourite != null || onPlayNow != null || extraMenu.isNotEmpty()
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .focusRing(RoundedCornerShape(10.dp), MaterialTheme.colorScheme.primary)
            .combinedClickable(onClick = onClick, onLongClick = if (hasMenu) ({ menu = true }) else null),
    ) {
        Box {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            rating?.let { RatingBadge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
            if (favourite) {
                Icon(
                    Icons.Filled.Favorite,
                    null,
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (watched) {
                Icon(
                    Icons.Filled.CheckCircle,
                    stringResource(R.string.watched),
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    tint = Color.White,
                )
            }
            if (hasMenu) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onPlayNow?.let { play ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.play_now)) },
                            onClick = {
                                menu = false
                                play()
                            },
                        )
                    }
                    onToggleFavourite?.let { toggle ->
                        DropdownMenuItem(
                            text = { Text(stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add)) },
                            onClick = {
                                menu = false
                                toggle()
                            },
                        )
                    }
                    extraMenu.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menu = false
                                action()
                            },
                        )
                    }
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun PosterPlaceholder() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .skeleton(),
    )
}

@Composable
fun Logo(
    url: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
    )
}

@Composable
private fun FirstRun(
    refreshing: Boolean,
    progress: SyncProgress?,
    error: String,
    onRetry: () -> Unit,
    onChangeLogin: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (refreshing || error.isBlank()) {
            Text(stringResource(R.string.first_run_loading), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            SyncProgressBar(progress, Modifier.fillMaxWidth().widthIn(max = 420.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                progress?.let { SyncText.describe(LocalContext.current, it) } ?: stringResource(R.string.login_downloading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(stringResource(R.string.first_run_failed), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            TextButton(onClick = onChangeLogin) { Text(stringResource(R.string.first_run_change_login)) }
        }
    }
}

/** Determinate while the size is known, otherwise the moving bar. */
@Composable
private fun SyncProgressBar(
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

/** A thin bar and one line above the lists while a refresh downloads, so a slow server is never a mystery. */
@Composable
private fun RefreshBar(progress: SyncProgress) {
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

/** The TMDB rating in the poster's corner, readable over any artwork. */
@Composable
private fun RatingBadge(
    rating: Double,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, null, Modifier.size(12.dp), tint = RATING_GOLD)
        Spacer(Modifier.width(3.dp))
        Text(String.format(locale, "%.1f", rating), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** The usual star gold, which reads on the dark badge whatever the app's accent. */
private val RATING_GOLD = Color(0xFFF5C518)

/** Downloads, beside the three sections: opens its own page, with a badge while anything is still to download. */
@Composable
private fun RowScope.DownloadsNavItem(onClick: () -> Unit) {
    NavigationBarItem(
        selected = false,
        onClick = onClick,
        icon = { DownloadsIcon() },
        label = { Text(stringResource(R.string.downloads)) },
    )
}

/** The downloads icon, with how many are still to come. */
@Composable
internal fun DownloadsIcon(downloads: DownloadsViewModel = viewModel()) {
    val pending by downloads.pending.collectAsStateWithLifecycle()
    BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) {
        Icon(Icons.Filled.DownloadForOffline, contentDescription = null)
    }
}
