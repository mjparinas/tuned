package app.tuned.ui.library

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
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BookmarkAdd
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
import app.tuned.R
import app.tuned.container
import app.tuned.data.db.WatchLaterEntity
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.focusRing
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The person's own lists beyond favourites (Melvin, 2026-11-17): Watch later for films and shows, and marking films
 * and episodes watched or unwatched by hand.
 */
class LibraryViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = app.container.db

    val later: StateFlow<List<WatchLaterEntity>> =
        db.watchLater().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val picks = app.container.picks

    /** Shown changed the moment Watch later is tapped ([app.tuned.data.Picks]). */
    val laterKeys: StateFlow<Set<String>> = picks.watchLater.value

    val watched: StateFlow<Set<Long>> =
        db
            .library()
            .watchedIds()
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleLater(row: WatchLaterEntity) {
        picks.setWatchLater(row, row.key !in laterKeys.value)
    }

    fun setWatched(
        ids: List<Long>,
        watched: Boolean,
    ) {
        viewModelScope.launchSafely("Library") { if (watched) db.library().markWatched(ids) else db.library().markUnwatched(ids) }
    }

    companion object {
        fun movie(
            itemId: Long,
            title: String,
            image: String?,
        ) = WatchLaterEntity(WatchLaterEntity.movieKey(itemId), itemId, null, title, image, System.currentTimeMillis())

        fun show(
            showKey: String,
            title: String,
            image: String?,
        ) = WatchLaterEntity(WatchLaterEntity.showKey(showKey), null, showKey, title, image, System.currentTimeMillis())
    }
}

/** Add to or remove from Watch later. */
@Composable
fun WatchLaterButton(
    row: WatchLaterEntity,
    vm: LibraryViewModel = viewModel(),
) {
    val keys by vm.laterKeys.collectAsStateWithLifecycle()
    val saved = row.key in keys
    IconButton(onClick = { vm.toggleLater(row) }) {
        Icon(
            if (saved) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd,
            contentDescription = stringResource(if (saved) R.string.watch_later_remove else R.string.watch_later_add),
            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * Watch later: a row across the top of Movies (films) or Shows (shows), newest first, under Continue Watching.
 * Like that row, it shows only on the full list, never above a search or a category.
 */
fun LazyGridScope.watchLaterRow(
    shows: Boolean,
    visible: @Composable () -> Boolean,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (String, String) -> Unit,
) {
    item(key = "later", span = { GridItemSpan(maxLineSpan) }, contentType = "later") {
        val vm: LibraryViewModel = viewModel()
        val all by vm.later.collectAsStateWithLifecycle()
        val rows = all.filter { (it.showKey != null) == shows }
        if (rows.isEmpty() || !visible()) return@item
        Column {
            Text(
                stringResource(R.string.watch_later),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.key }) { row ->
                    LaterCard(row) {
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
private fun LaterCard(
    row: WatchLaterEntity,
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
            AsyncImage(model = row.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
