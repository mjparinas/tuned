package app.tuned.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelScanDao {
    /** The live channels a scan looks at: those not hidden by country or group, in list order. */
    @Query(
        """
        SELECT id FROM items
        WHERE kind = 0 AND (region IS NULL OR region NOT IN (:hidden)) AND ('g:' || kind || ':' || groupName) NOT IN (:hidden)
            AND NOT ('placeholder' IN (:hidden)
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND kind = 0
                AND ((' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR adult = 0)
        ORDER BY sortOrder
        """,
    )
    suspend fun channels(
        hidden: List<String>,
        adult: Boolean,
    ): List<Long>

    /** The channel that played most recently, in a scan or in the player, to compare failures against. */
    @Query(
        """
        SELECT itemId FROM (
            SELECT itemId, lastOkAt AS at FROM channel_scan WHERE lastOkAt > 0
            UNION ALL SELECT itemId, okAt AS at FROM channel_health WHERE okAt > 0
        ) WHERE itemId IN (SELECT id FROM items WHERE kind = 0) ORDER BY at DESC LIMIT 1
        """,
    )
    suspend fun knownGood(): Long?

    @Query("SELECT * FROM channel_scan WHERE itemId = :itemId")
    suspend fun get(itemId: Long): ChannelScanEntity?

    @Upsert
    suspend fun save(row: ChannelScanEntity)

    @Query("DELETE FROM channel_scan")
    suspend fun deleteAll()

    /** Hidden channels with their names, for [app.tuned.data.ChannelScanner.releaseEventChannels]. */
    @Query("SELECT s.itemId, i.name, i.groupName FROM channel_scan s JOIN items i ON i.id = s.itemId WHERE s.hidden = 1")
    suspend fun hiddenNames(): List<HiddenScanRow>

    @Query("UPDATE channel_scan SET hidden = 0 WHERE itemId IN (:ids)")
    suspend fun unhide(ids: List<Long>)

    /** Channels that failed in at least one scan and still exist, hidden ones first, then the most recent. */
    @Query(
        """
        SELECT s.itemId, i.name, i.logo, i.groupName, s.failures, s.lastFailAt, s.reason, s.hidden
        FROM channel_scan s JOIN items i ON i.id = s.itemId
        WHERE s.failures > 0
        ORDER BY s.hidden DESC, s.lastFailAt DESC
        """,
    )
    fun report(): Flow<List<ScanReportRow>>
}
