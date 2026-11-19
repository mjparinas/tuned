package app.tuned.ui.details

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tuned.R
import app.tuned.container
import app.tuned.ui.browse.FavouriteButton
import app.tuned.ui.browse.rememberListPicker

/**
 * A film's favourite heart and Add to list on its page, beside Watch later and Watched, so nothing a poster's long press
 * offers needs that long press (Melvin, 2026-11-19: "Optimize TV for remotes, avoid 3 dot drop downs").
 */
@Composable
internal fun FilmPicks(itemId: Long) {
    val picks = LocalContext.current.container.picks
    val favourites by picks.favourites.value.collectAsStateWithLifecycle()
    val favourite = itemId in favourites
    FavouriteButton(favourite) { picks.setFavourite(itemId, !favourite) }
    val lists = rememberListPicker()
    IconButton(onClick = { lists.open(itemId) }) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = stringResource(R.string.list_add))
    }
}
