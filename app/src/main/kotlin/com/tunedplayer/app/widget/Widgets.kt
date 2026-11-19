package com.tunedplayer.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.tunedplayer.app.diagnostics.attempt

/**
 * Redraws the home screen widgets from the cache. Called after each background refresh (new guide, so "on now" moves
 * on), when Tuned or the player goes to the background (favourites, theme, profile or progress may have changed), and on
 * sign-out. A widget that isn't on the home screen costs nothing.
 */
object Widgets {
    suspend fun refresh(context: Context) {
        attempt(TAG, "Couldn't update the favourites widget") { FavouritesWidget().updateAll(context) }
        attempt(TAG, "Couldn't update the Continue watching widget") { ContinueWidget().updateAll(context) }
    }

    private const val TAG = "Widgets"
}
