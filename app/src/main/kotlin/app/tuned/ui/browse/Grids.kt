package app.tuned.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.tuned.R
import app.tuned.core.ContentKind
import app.tuned.core.FastScroll
import app.tuned.core.ScreenLayout
import app.tuned.core.Titles
import app.tuned.data.db.ContinueRow
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.ShowSummary
import app.tuned.data.db.WatchLaterEntity
import app.tuned.ui.common.FastScrollbar
import app.tuned.ui.common.SKELETON_ROWS
import app.tuned.ui.common.firstLoad
import app.tuned.ui.common.focusRing
import app.tuned.ui.common.peekOrNull
import app.tuned.ui.common.rememberFastScroll
import app.tuned.ui.common.windowWidthDp
import app.tuned.ui.library.LibraryViewModel
import app.tuned.ui.library.watchLaterRow
import coil3.compose.AsyncImage

@Composable
internal fun MovieGrid(
    vm: BrowseViewModel,
    onPlay: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    alwaysShowScrollbar: Boolean,
    onSearchEverything: (String) -> Unit,
) {
    val movies = vm.movies.collectAsLazyPagingItems()
    val favourites by vm.favouriteIds.collectAsStateWithLifecycle()
    val library: LibraryViewModel = viewModel()
    val watched by library.watched.collectAsStateWithLifecycle()
    val later by library.laterKeys.collectAsStateWithLifecycle()
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = ScreenLayout.posterMinDp(windowWidthDp()).dp),
            state = state,
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
                        title = Titles.display(movie.name),
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
        GridScrollbar(state, movies.itemCount, alwaysShowScrollbar) { movies.peekOrNull(it)?.groupName }
        NoMatches(movies, vm, ContentKind.MOVIE, onSearchEverything)
    }
}

@Composable
internal fun ShowGrid(
    vm: BrowseViewModel,
    onOpenShow: (String, String) -> Unit,
    onPlay: (Long) -> Unit,
    alwaysShowScrollbar: Boolean,
    onSearchEverything: (String) -> Unit,
) {
    val shows = vm.shows.collectAsLazyPagingItems()
    val library: LibraryViewModel = viewModel()
    val later by library.laterKeys.collectAsStateWithLifecycle()
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = ScreenLayout.posterMinDp(windowWidthDp()).dp),
            state = state,
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
                        title = Titles.display(show.showName),
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
        GridScrollbar(state, shows.itemCount, alwaysShowScrollbar) { shows.peekOrNull(it)?.groupName }
        NoMatches(shows, vm, ContentKind.SERIES, onSearchEverything)
    }
}

/**
 * The poster grids' scroll bar. The rows across the top (Continue Watching, Watch later) come before the posters,
 * so a grid index is moved back by them before it's looked up among the [count] posters.
 */
@Composable
private fun GridScrollbar(
    state: LazyGridState,
    count: Int,
    alwaysShow: Boolean,
    group: (Int) -> String?,
) {
    FastScrollbar(rememberFastScroll(state), alwaysShow) { index ->
        val headers = state.layoutInfo.totalItemsCount - count
        FastScroll.label(group(index - headers))
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
                stringResource(R.string.continue_episode_named, Titles.display(row.showName), episodeName)
            }

            row.showName != null && row.season != null && row.episode != null -> {
                stringResource(R.string.continue_episode, Titles.display(row.showName), row.season, row.episode)
            }

            else -> {
                Titles.display(row.name)
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

/** The TMDB rating in the poster's corner, readable over any artwork. */
@Composable
internal fun RatingBadge(
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
