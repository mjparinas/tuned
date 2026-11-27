package com.tunedplayer.app.ui.details

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.ui.browse.FavouriteButton
import com.tunedplayer.app.ui.browse.rememberListPicker

/**
 * A film's favourite heart and Add to list on its page, beside Watched (the heart is Watch later too, since 2026-11-27), so nothing a poster's long press
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
