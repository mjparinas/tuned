package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.paging.compose.LazyPagingItems
import com.tunedplayer.app.container
import com.tunedplayer.app.work.BrowseWarm
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Enqueue logos for the visible window plus [BrowseWarm]'s page ahead into Coil's disk cache, so scrolling does not
 * wait on the network for pictures already about to appear (docs/decisions/speed.md › Speculative warm).
 */
@Composable
internal fun <T : Any> WarmVisibleArt(
    items: LazyPagingItems<T>,
    listState: LazyListState? = null,
    gridState: LazyGridState? = null,
    logo: (T) -> String?,
) {
    val warm = LocalContext.current.container.browseWarm
    LaunchedEffect(items, listState, gridState) {
        val visible =
            when {
                listState != null -> {
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.let { rows ->
                            val first = rows.firstOrNull()?.index ?: 0
                            val last = rows.lastOrNull()?.index ?: first
                            first to last
                        }
                    }
                }

                gridState != null -> {
                    snapshotFlow {
                        gridState.layoutInfo.visibleItemsInfo.let { rows ->
                            val first = rows.firstOrNull()?.index ?: 0
                            val last = rows.lastOrNull()?.index ?: first
                            first to last
                        }
                    }
                }

                else -> {
                    snapshotFlow { 0 to 0 }
                }
            }
        visible
            .map { (first, last) ->
                val end = (last + BrowseWarm.PAGE).coerceAtMost((items.itemCount - 1).coerceAtLeast(0))
                val start = first.coerceAtLeast(0)
                start to end
            }.distinctUntilChanged()
            .collect { (start, end) ->
                if (end < start) return@collect
                val urls =
                    (start..end).mapNotNull { i ->
                        items.peek(i)?.let(logo)?.takeIf { it.isNotBlank() }
                    }
                warm.enqueueArt(urls)
            }
    }
}
