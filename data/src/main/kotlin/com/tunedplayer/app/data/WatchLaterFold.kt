package com.tunedplayer.app.data

import androidx.room.withTransaction
import com.tunedplayer.app.data.db.FavouriteEntity
import com.tunedplayer.app.data.db.TunedDatabase

/**
 * Watch later is part of Favourites now (Melvin, 2026-11-27, asked whether to merge the two: "Merge into Favourites").
 * The heart does both jobs, so anything still in the old `watch_later` table moves into Favourites: a film by its own
 * id, a whole show by its show id ([FavouriteShows]), each for the profile that saved it and with the time it was
 * saved. A title that was already a favourite stays as it was. Then the old rows go.
 *
 * It runs once the first screen is up, for what this device saved before it updated, and after every backup it takes
 * in, because a device that hasn't updated yet still sends its Watch later in the backup. The table stays (empty) so
 * those backups still read, and so there is no migration to collide with.
 */
object WatchLaterFold {
    /** How many rows moved, for the log. */
    suspend fun fold(db: TunedDatabase): Int {
        val sync = db.sync()
        val rows = sync.watchLater()
        if (rows.isEmpty()) return 0
        val favourites =
            rows.mapNotNull { row ->
                val id = row.itemId ?: row.showKey?.let(FavouriteShows::id) ?: return@mapNotNull null
                FavouriteEntity(id, row.addedAt, position = row.addedAt, profileId = row.profileId)
            }
        db.withTransaction {
            favourites.chunked(CHUNK).forEach { db.watchLater().insertFavouritesIfMissing(it) }
            sync.deleteWatchLater()
        }
        return favourites.size
    }

    private const val CHUNK = 200
}
