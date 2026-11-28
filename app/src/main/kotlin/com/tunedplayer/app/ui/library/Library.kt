package com.tunedplayer.app.ui.library

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.data.FavouriteShows
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.browse.rememberShowArt
import com.tunedplayer.app.ui.common.focusRing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * The Favourites row across the top of Movies and Shows, and marking films and episodes watched or unwatched by hand
 * (Melvin, 2026-11-17). Watch later used to be its own list here; it is part of Favourites now (Melvin, 2026-11-27:
 * "Merge into Favourites"), so the heart saves a film or a whole show for later ([com.tunedplayer.app.data.WatchLaterFold]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = app.container.db

    /** Favourite films, newest first, for the row across the top of Movies. */
    val favouriteFilms: StateFlow<List<FavouriteCard>> =
        db
            .library()
            .favouriteFilms()
            .map { rows -> rows.map { FavouriteCard("m:${it.id}", it.id, null, it.name, it.logo) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Shows favourited whole ([FavouriteShows]), newest first, for the row across the top of Shows. */
    val favouriteShows: StateFlow<List<FavouriteCard>> =
        db
            .library()
            .favouritesNewest()
            .mapLatest { favourites ->
                attempt("Library", "Favourite shows failed") {
                    val ids = favourites.map { it.itemId }
                    val keys = FavouriteShows.keys(db, ids.toSet())
                    val art = db.shows().showArt(keys).associateBy { FavouriteShows.id(it.showKey) }
                    ids.mapNotNull { id ->
                        art[id]?.let { FavouriteCard("s:${it.showKey}", null, it.showKey, it.showName, it.logo) }
                    }
                }.getOrDefault(emptyList())
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watched: StateFlow<Set<Long>> =
        db
            .library()
            .watchedIds()
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setWatched(
        ids: List<Long>,
        watched: Boolean,
    ) {
        viewModelScope.launchSafely("Library") { if (watched) db.library().markWatched(ids) else db.library().markUnwatched(ids) }
    }
}

/** Marks a film watched or unwatched. */
@Composable
fun WatchedButton(
    itemId: Long,
    vm: LibraryViewModel = viewModel(),
) {
    val watched by vm.watched.collectAsStateWithLifecycle()
    val done = itemId in watched
    IconButton(onClick = { vm.setWatched(listOf(itemId), !done) }) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
            contentDescription = stringResource(if (done) R.string.mark_unwatched else R.string.mark_watched),
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A favourite film or show on the Favourites row: its key for the row, what it opens, and its title and picture. */
data class FavouriteCard(
    val key: String,
    val itemId: Long?,
    val showKey: String?,
    val title: String,
    val image: String?,
)

/**
 * Favourites: a row across the top of Movies (films) or Shows (whole shows), newest first, under Continue Watching, in
 * the place Watch later had (2026-11-27). Like that row, it shows only on the full list, never above a search or a
 * category, and the Favourites chip lists them all.
 */
fun LazyGridScope.favouritesRow(
    shows: Boolean,
    visible: @Composable () -> Boolean,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (String, String) -> Unit,
) {
    item(key = "favourites", span = { GridItemSpan(maxLineSpan) }, contentType = "favourites") {
        val vm: LibraryViewModel = viewModel()
        val rows by (if (shows) vm.favouriteShows else vm.favouriteFilms).collectAsStateWithLifecycle()
        if (rows.isEmpty() || !visible()) return@item
        Column {
            Text(
                stringResource(R.string.filter_favourites),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.key }) { row ->
                    FavouriteCardView(row) {
                        val show = row.showKey
                        if (show != null) onOpenShow(show, row.title) else row.itemId?.let(onOpenMovie)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun FavouriteCardView(
    row: FavouriteCard,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(10.dp))
            .focusRing(RoundedCornerShape(10.dp), MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            // A show's cover, TMDB's poster first, rather than an episode's still.
            val image = if (row.showKey != null) rememberShowArt(row.title, row.image).image else row.image
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            row.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}
