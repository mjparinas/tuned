package com.tunedplayer.app.data

import com.tunedplayer.app.core.StableId
import com.tunedplayer.app.data.db.TunedDatabase

/**
 * Whole shows as favourites (Melvin, 2026-11-27: "I can't seem to add actual full TV shows to the favorites list … I'm
 * talking about the actual show itself"). A show has no row of its own in `items`, only its episodes, so its favourite
 * is kept under [id], the show's own stable id, in the same favourites table as channels and films. That way it goes
 * through the backup, the website and the merge between devices exactly as any other favourite, with nothing new to
 * store or migrate. The Shows tab's Favourites finds them by turning those ids back into show keys ([keys]).
 */
object FavouriteShows {
    /** SQLite on Android 8.0 takes at most 999 values in one query; nobody favourites anywhere near this many shows. */
    private const val MAX = 900

    fun id(showKey: String): Long = StableId.show(showKey)

    /** The keys of the shows among [favourites] (favourite ids of any kind), for the Shows tab's Favourites. */
    suspend fun keys(
        db: TunedDatabase,
        favourites: Set<Long>,
    ): List<String> {
        if (favourites.isEmpty()) return emptyList()
        return db
            .shows()
            .showKeys()
            .filter { id(it) in favourites }
            .take(MAX)
    }
}
