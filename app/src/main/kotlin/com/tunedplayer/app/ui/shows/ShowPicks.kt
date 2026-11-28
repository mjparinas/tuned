package com.tunedplayer.app.ui.shows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.container
import com.tunedplayer.app.data.FavouriteShows
import com.tunedplayer.app.ui.browse.FavouriteButton

/**
 * The show's own heart on its page (Melvin, 2026-11-27: "I can't seem to add actual full TV shows to
 * the favorites list … I'm talking about the actual show itself"). It favourites the whole show, which then sits in the
 * Shows tab's Favourites and its Favourites row, which took Watch later's place; on a TV it is the way in, since posters
 * there have no long-press menu. The heart fills the
 * moment it is pressed ([com.tunedplayer.app.data.Picks]).
 */
@Composable
internal fun ShowFavouriteButton(showKey: String) {
    val picks = LocalContext.current.container.picks
    val favourites by picks.favourites.value.collectAsStateWithLifecycle()
    val favourite = FavouriteShows.id(showKey) in favourites
    FavouriteButton(favourite) { picks.setShowFavourite(showKey, !favourite) }
}
