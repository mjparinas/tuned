package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Everything every profile keeps, across all profiles at once, for sync between devices
 * ([com.tunedplayer.app.data.SyncCopyRepository]). The other DAOs only ever see the profile in use; this one reads and replaces
 * the lot, so the "Who's watching" profiles and what each of them saved travel together.
 */
@Dao
interface SyncDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun profiles(): List<ProfileEntity>

    @Query("SELECT * FROM favourites")
    suspend fun favourites(): List<FavouriteEntity>

    @Query("SELECT * FROM history")
    suspend fun history(): List<HistoryEntity>

    @Query("SELECT * FROM watch_later")
    suspend fun watchLater(): List<WatchLaterEntity>

    @Query("SELECT * FROM seen_episodes")
    suspend fun seenEpisodes(): List<SeenEpisodeEntity>

    @Query("SELECT * FROM lists")
    suspend fun lists(): List<ListEntity>

    @Query("SELECT * FROM list_items")
    suspend fun listItems(): List<ListItemEntity>

    @Query("SELECT * FROM guide_fixes")
    suspend fun guideFixes(): List<GuideFixEntity>

    @Query("SELECT profileId FROM current_profile")
    suspend fun currentProfile(): Long?

    /** One profile and everything it keeps, as [replaceProfiles] takes it. */
    data class Kept(
        val profiles: List<ProfileEntity>,
        val favourites: List<FavouriteEntity>,
        val history: List<HistoryEntity>,
        val watchLater: List<WatchLaterEntity>,
        val seen: List<SeenEpisodeEntity>,
        val lists: List<ListEntity>,
        val listItems: List<ListItemEntity>,
    )

    /**
     * Puts [kept] in place of every profile here and all they saved, in one go, so a list never shows half of each.
     * The profile in use stays when the new set has it; otherwise the first one takes over.
     */
    @Transaction
    suspend fun replaceProfiles(kept: Kept) {
        if (kept.profiles.isEmpty()) return
        deleteListItems()
        deleteLists()
        deleteSeen()
        deleteWatchLater()
        deleteHistory()
        deleteFavourites()
        deleteProfiles()
        insertProfiles(kept.profiles)
        kept.favourites.chunked(CHUNK).forEach { insertFavourites(it) }
        kept.history.chunked(CHUNK).forEach { insertHistory(it) }
        kept.watchLater.chunked(CHUNK).forEach { insertWatchLater(it) }
        kept.seen.chunked(CHUNK).forEach { insertSeen(it) }
        insertLists(kept.lists)
        kept.listItems.chunked(CHUNK).forEach { insertListItems(it) }
        val ids = kept.profiles.map { it.id }.toSet()
        val current = currentProfile()
        if (current == null || current !in ids) setCurrent(CurrentProfileEntity(profileId = kept.profiles.first().id))
    }

    /**
     * Puts [fixes] in place of this device's guide fixes. A channel that loses its fix gets its playlist guide entry
     * back; the new ones take their fixed ids ([GuideFixDao.reapply]) once the channel is in the playlist here.
     */
    @Transaction
    suspend fun replaceGuideFixes(fixes: List<GuideFixEntity>) {
        val keep = fixes.map { it.itemId }.toSet()
        guideFixes().filter { it.itemId !in keep }.forEach { setTvgId(it.itemId, it.playlistTvgId) }
        deleteGuideFixes()
        insertGuideFixes(fixes)
    }

    @Query("DELETE FROM list_items")
    suspend fun deleteListItems()

    @Query("DELETE FROM lists")
    suspend fun deleteLists()

    @Query("DELETE FROM seen_episodes")
    suspend fun deleteSeen()

    @Query("DELETE FROM watch_later")
    suspend fun deleteWatchLater()

    @Query("DELETE FROM history")
    suspend fun deleteHistory()

    @Query("DELETE FROM favourites")
    suspend fun deleteFavourites()

    @Query("DELETE FROM profiles")
    suspend fun deleteProfiles()

    @Query("DELETE FROM guide_fixes")
    suspend fun deleteGuideFixes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(rows: List<ProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavourites(rows: List<FavouriteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(rows: List<HistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchLater(rows: List<WatchLaterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeen(rows: List<SeenEpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLists(rows: List<ListEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListItems(rows: List<ListItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGuideFixes(rows: List<GuideFixEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCurrent(row: CurrentProfileEntity)

    @Query("UPDATE items SET tvgId = :tvgId WHERE id = :itemId")
    suspend fun setTvgId(
        itemId: Long,
        tvgId: String?,
    )
}

/** Rows per insert, well under SQLite's limit on bound values. */
private const val CHUNK = 200
