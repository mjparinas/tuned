package com.tunedplayer.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.tunedplayer.app.data.db.FavouriteRow
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.common.startFocus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class FavouriteOrderViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val library = app.container.db.library()

    val favourites: StateFlow<List<FavouriteRow>> =
        library.favouriteChannels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Moves the favourite at [from] by [by] places (−1 up, +1 down) and saves the whole order. */
    fun move(
        from: Int,
        by: Int,
    ) {
        val list = favourites.value.toMutableList()
        val to = from + by
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        viewModelScope.launchSafely("FavouriteOrder") { library.reorderFavourites(list.map { it.id }) }
    }
}

/** Settings › Order favourites: the Favourites list on Live TV, top to bottom, with arrows that work on a TV remote too. */
@Composable
fun FavouriteOrderScreen(
    onBack: () -> Unit,
    vm: FavouriteOrderViewModel = viewModel(),
) {
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_favourite_order), onBack) }) { padding ->
        if (favourites.isEmpty()) {
            Text(
                stringResource(R.string.favourite_order_empty),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        FavouriteOrderList(favourites, vm::move, Modifier.padding(padding).fillMaxSize())
    }
}

/**
 * The favourites with their arrows. On a TV the remote stays with the channel being moved (Melvin, 2026-11-21: Settings
 * should work with "up, down, left, right, OK, and back"): after a move, focus goes to the same arrow on the channel's new
 * row, or to the other arrow once it reaches the top or bottom, so OK pressed again keeps moving it. Before, focus stayed
 * on the old row, now another channel, and was dropped altogether when the arrow under it turned off.
 */
@Composable
internal fun FavouriteOrderList(
    favourites: List<FavouriteRow>,
    onMove: (from: Int, by: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The channel just moved, so its row can take focus back once the new order arrives from the database.
    var moved by remember { mutableStateOf<Moved?>(null) }
    val start = rememberStartFocus()
    val ring = MaterialTheme.colorScheme.primary
    LazyColumn(modifier) {
        itemsIndexed(favourites, key = { _, f -> f.id }) { i, f ->
            val up = remember { FocusRequester() }
            val down = remember { FocusRequester() }
            val canUp = i > 0
            val canDown = i < favourites.lastIndex
            LaunchedEffect(i, moved) {
                val m = moved ?: return@LaunchedEffect
                if (m.id != f.id || m.to != i) return@LaunchedEffect
                moved = null
                val target = if ((m.by < 0 && canUp) || !canDown) up else down
                attempt("FavouriteOrder", "Couldn't keep focus on the moved channel") { target.requestFocus() }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.animateItem().fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            ) {
                AsyncImage(model = f.logo, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    f.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        moved = Moved(f.id, i - 1, -1)
                        onMove(i, -1)
                    },
                    enabled = canUp,
                    modifier =
                        Modifier
                            .focusRequester(up)
                            .focusRing(CircleShape, ring),
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up, f.name))
                }
                IconButton(
                    onClick = {
                        moved = Moved(f.id, i + 1, 1)
                        onMove(i, 1)
                    },
                    enabled = canDown,
                    modifier =
                        Modifier
                            .focusRequester(down)
                            // The first channel's up arrow is off, so the remote starts on its down arrow.
                            .then(if (i == 0) Modifier.startFocus(start) else Modifier)
                            .focusRing(CircleShape, ring),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down, f.name))
                }
            }
        }
    }
}

/** A favourite moved [by] one place, to index [to]. */
private data class Moved(
    val id: Long,
    val to: Int,
    val by: Int,
)
