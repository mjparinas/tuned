package com.tunedplayer.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The person's own lists beside Favourites ([ListEntity]), each for the profile in use ([ME]). */
@Dao
interface ListDao {
    @Query("SELECT profileId FROM current_profile")
    suspend fun currentProfile(): Long?

    @Query("SELECT * FROM lists WHERE profileId = $ME ORDER BY position, createdAt")
    fun all(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE profileId = $ME ORDER BY position, createdAt")
    suspend fun allNow(): List<ListEntity>

    /** The channels in one of the person's own lists, in the list's order, with Live TV's filters. */
    @Suppress("LongParameterList") // Every Live TV filter is a parameter of the one query, so the list stays one read.
    @Query(
        "SELECT $CHANNEL_COLUMNS, 0 AS favourite FROM items i JOIN list_items li ON li.itemId = i.id AND li.listId = :listId $NOW_JOIN " +
            "WHERE $CHANNEL_FILTER $LIVE_QUICK ORDER BY li.position",
    )
    fun listChannels(
        listId: Long,
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
        topic: String,
        quick: List<String>,
    ): PagingSource<Int, ChannelWithNow>

    /** The lists with a channel (kind 0) or film (kind 1) in them, for that tab's row of chips. */
    @Query(
        """
        SELECT l.* FROM lists l
        WHERE l.profileId = $ME
            AND EXISTS (SELECT 1 FROM list_items li JOIN items i ON i.id = li.itemId WHERE li.listId = l.id AND i.kind = :kind)
        ORDER BY l.position, l.createdAt
        """,
    )
    fun withKind(kind: Int): Flow<List<ListEntity>>

    @Upsert
    suspend fun upsert(list: ListEntity)

    /** A new list for the profile in use. */
    @Transaction
    suspend fun create(list: ListEntity) = upsert(list.copy(profileId = currentProfile() ?: Profiles.FIRST))

    @Query("UPDATE lists SET name = :name WHERE id = :id AND profileId = $ME")
    suspend fun rename(
        id: Long,
        name: String,
    )

    @Query("DELETE FROM lists WHERE id = :id AND profileId = $ME")
    suspend fun deleteRow(id: Long)

    @Query("DELETE FROM list_items WHERE listId = :id")
    suspend fun deleteItems(id: Long)

    /** Removes a list and what was in it (the channels and films themselves stay, of course). */
    @Transaction
    suspend fun delete(id: Long) {
        deleteItems(id)
        deleteRow(id)
    }

    @Upsert
    suspend fun addItem(item: ListItemEntity)

    @Query("DELETE FROM list_items WHERE listId = :listId AND itemId = :itemId")
    suspend fun removeItem(
        listId: Long,
        itemId: Long,
    )

    /** Which of this profile's lists hold [itemId], for the ticks in "Add to list". */
    @Query("SELECT li.listId FROM list_items li JOIN lists l ON l.id = li.listId WHERE li.itemId = :itemId AND l.profileId = $ME")
    fun listsWith(itemId: Long): Flow<List<Long>>

    @Query("SELECT li.* FROM list_items li JOIN lists l ON l.id = li.listId WHERE l.profileId = $ME")
    suspend fun allItems(): List<ListItemEntity>

    @Query("DELETE FROM list_items WHERE listId IN (SELECT id FROM lists WHERE profileId = :profileId)")
    suspend fun deleteItemsOf(profileId: Long)

    @Query("DELETE FROM lists WHERE profileId = :profileId")
    suspend fun deleteListsOf(profileId: Long)

    /** Removes every list of one profile, as deleting the profile does. */
    @Transaction
    suspend fun clearProfile(profileId: Long) {
        deleteItemsOf(profileId)
        deleteListsOf(profileId)
    }

    /** Sync: another device's lists in place of this profile's, so a removal there removes it here too. */
    @Transaction
    suspend fun replaceMine(
        lists: List<ListEntity>,
        items: List<ListItemEntity>,
    ) {
        val me = currentProfile() ?: Profiles.FIRST
        clearProfile(me)
        lists.forEach { upsert(it.copy(profileId = me)) }
        val ids = lists.map { it.id }.toSet()
        items.filter { it.listId in ids }.forEach { addItem(it) }
    }
}
