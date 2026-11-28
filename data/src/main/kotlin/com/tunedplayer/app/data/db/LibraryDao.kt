package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.tunedplayer.app.core.Trakt
import kotlinx.coroutines.flow.Flow

/** The person's own lists, each for the profile in use ([ME]): writes stamp it, reads and deletes keep to it. */
@Dao
interface LibraryDao {
    @Query("SELECT profileId FROM current_profile")
    suspend fun currentProfile(): Long?

    @Upsert
    suspend fun upsertFavourite(favourite: FavouriteEntity)

    @Transaction
    suspend fun addFavourite(favourite: FavouriteEntity) = upsertFavourite(favourite.copy(profileId = currentProfile() ?: Profiles.FIRST))

    @Query("DELETE FROM favourites WHERE itemId = :itemId AND profileId = $ME")
    suspend fun removeFavourite(itemId: Long)

    @Query("SELECT * FROM favourites WHERE profileId = $ME")
    suspend fun allFavourites(): List<FavouriteEntity>

    @Query("SELECT itemId FROM favourites WHERE profileId = $ME")
    fun favouriteIds(): Flow<List<Long>>

    /** Every favourite, newest first: the Shows tab's Favourites row picks the whole shows from it. */
    @Query("SELECT * FROM favourites WHERE profileId = $ME ORDER BY addedAt DESC")
    fun favouritesNewest(): Flow<List<FavouriteEntity>>

    /** Favourite films, newest first, for the Favourites row across the top of Movies (where Watch later was). */
    @Query(
        """
        SELECT i.id, i.name, i.logo FROM favourites f JOIN items i ON i.id = f.itemId
        WHERE f.profileId = $ME AND i.kind = 1 ORDER BY f.addedAt DESC LIMIT 60
        """,
    )
    fun favouriteFilms(): Flow<List<WarmArtRow>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE itemId = :itemId AND profileId = $ME)")
    fun isFavourite(itemId: Long): Flow<Boolean>

    /** Favourite channels in the order they're shown, for Settings › Order favourites. */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.tvgId, i.groupName, i.adult FROM favourites f JOIN items i ON i.id = f.itemId
        WHERE i.kind = 0 AND f.profileId = $ME
        GROUP BY i.id
        ORDER BY f.position, i.sortOrder
        """,
    )
    fun favouriteChannels(): Flow<List<FavouriteRow>>

    @Query("UPDATE favourites SET position = :position WHERE itemId = :itemId AND profileId = $ME")
    suspend fun setFavouritePosition(
        itemId: Long,
        position: Long,
    )

    /** Saves a new order: [ids] first to last. */
    @Transaction
    suspend fun reorderFavourites(ids: List<Long>) {
        ids.forEachIndexed { i, id -> setFavouritePosition(id, i.toLong()) }
    }

    @Upsert
    suspend fun upsertProgress(history: HistoryEntity)

    @Transaction
    suspend fun saveProgress(history: HistoryEntity) = upsertProgress(history.copy(profileId = currentProfile() ?: Profiles.FIRST))

    @Query("SELECT * FROM history WHERE itemId = :itemId AND profileId = $ME")
    suspend fun progress(itemId: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE itemId IN (:ids) AND profileId = $ME")
    fun progressFor(ids: List<Long>): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE profileId = $ME")
    suspend fun clearHistory()

    /** Films and episodes counted as watched: played to the end, or marked by hand. */
    @Query("SELECT itemId FROM history WHERE profileId = $ME AND durationMs > 0 AND positionMs >= durationMs * 92 / 100")
    fun watchedIds(): Flow<List<Long>>

    /** Marks [ids] watched without playing them: a finished entry, so they leave Continue Watching too. */
    @Transaction
    suspend fun markWatched(ids: List<Long>) {
        val now = System.currentTimeMillis()
        for (id in ids) {
            val length = progress(id)?.durationMs?.takeIf { it > 0 } ?: 1
            saveProgress(HistoryEntity(id, positionMs = length, durationMs = length, watchedAt = now))
        }
    }

    /**
     * Marks each match watched at Trakt's time. Skips an id that is already finished and watched more recently than
     * Trakt says, so a newer play in Tuned isn't pushed back. Uses a finished entry of length 1 when there was no
     * progress yet (the watched check only needs position at the end).
     */
    @Transaction
    suspend fun markWatchedFromTrakt(matches: List<Trakt.Match>) {
        if (matches.isEmpty()) return
        // Same id can match more than once (duplicate groups); keep the latest Trakt time.
        val byId = LinkedHashMap<Long, Long>(matches.size)
        for (match in matches) {
            val was = byId[match.itemId]
            if (was == null || match.watchedAtMs > was) byId[match.itemId] = match.watchedAtMs
        }
        for ((id, at) in byId) {
            val existing = progress(id)
            if (existing != null &&
                existing.durationMs > 0 &&
                existing.positionMs >= existing.durationMs * 92 / 100 &&
                existing.watchedAt >= at
            ) {
                continue
            }
            val length = existing?.durationMs?.takeIf { it > 0 } ?: 1
            saveProgress(HistoryEntity(id, positionMs = length, durationMs = length, watchedAt = at))
        }
    }

    /** Back to unwatched: forgets where they got to. */
    @Query("DELETE FROM history WHERE itemId IN (:ids) AND profileId = $ME")
    suspend fun markUnwatched(ids: List<Long>)

    @Query("DELETE FROM favourites WHERE profileId = $ME")
    suspend fun clearFavourites()

    @Query("SELECT * FROM channel_health WHERE itemId = :itemId")
    suspend fun health(itemId: Long): ChannelHealthEntity?

    @Upsert
    suspend fun saveHealth(health: ChannelHealthEntity)
}
