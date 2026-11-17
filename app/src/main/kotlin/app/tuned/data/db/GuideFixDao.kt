package app.tuned.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A live channel's own guide fix ([app.tuned.core.GuideFixes]): a guide entry picked by hand, a time offset, or both.
 * The channel's `tvgId` in `items` becomes its fixed id while this row exists; [playlistTvgId] keeps the playlist's
 * own, to fall back on and to restore.
 */
@Entity(tableName = "guide_fixes")
data class GuideFixEntity(
    @PrimaryKey val itemId: Long,
    /** The guide channel picked by hand, or null to keep the playlist's. */
    val guideId: String?,
    val offsetMinutes: Int,
    val playlistTvgId: String?,
)

/** A channel the downloaded guide lists, for picking a guide entry by hand. */
@Entity(tableName = "guide_channels")
data class GuideChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Dao
interface GuideFixDao {
    @Query("SELECT * FROM guide_fixes")
    suspend fun all(): List<GuideFixEntity>

    @Query("SELECT * FROM guide_fixes WHERE itemId = :itemId")
    fun fix(itemId: Long): Flow<GuideFixEntity?>

    @Query("SELECT * FROM guide_fixes WHERE itemId = :itemId")
    suspend fun get(itemId: Long): GuideFixEntity?

    @Upsert
    suspend fun save(fix: GuideFixEntity)

    @Query("DELETE FROM guide_fixes WHERE itemId = :itemId")
    suspend fun delete(itemId: Long)

    @Query("UPDATE items SET tvgId = :tvgId WHERE id = :itemId")
    suspend fun setTvgId(
        itemId: Long,
        tvgId: String?,
    )

    /** Replaces channel [to]'s programmes with [from]'s, moved by [shiftMs]. */
    @Transaction
    suspend fun copyProgrammes(
        from: String,
        to: String,
        shiftMs: Long,
    ) {
        deleteProgrammes(to)
        insertShifted(from, to, shiftMs)
    }

    @Query("DELETE FROM programmes WHERE channelId = :channelId")
    suspend fun deleteProgrammes(channelId: String)

    @Query(
        "INSERT INTO programmes (channelId, start, stop, title, description) " +
            "SELECT :to, start + :shiftMs, stop + :shiftMs, title, description FROM programmes WHERE channelId = :from",
    )
    suspend fun insertShifted(
        from: String,
        to: String,
        shiftMs: Long,
    )

    @Query("SELECT COUNT(*) FROM programmes WHERE channelId = :channelId")
    suspend fun programmeCount(channelId: String): Int

    /** The whole guide's offset changed by [shiftMs]. */
    @Query("UPDATE programmes SET start = start + :shiftMs, stop = stop + :shiftMs")
    suspend fun shiftAll(shiftMs: Long)

    /**
     * After a playlist refresh: note each fixed channel's new tvg-id from the playlist, then give it its fixed id again.
     * Fixes for channels the playlist dropped go too.
     */
    @Transaction
    suspend fun reapply() {
        dropOrphans()
        notePlaylistIds()
        useFixedIds()
    }

    @Query("DELETE FROM guide_fixes WHERE itemId NOT IN (SELECT id FROM items)")
    suspend fun dropOrphans()

    @Query(
        "UPDATE guide_fixes SET playlistTvgId = (SELECT tvgId FROM items WHERE id = guide_fixes.itemId) " +
            "WHERE itemId IN (SELECT id FROM items WHERE tvgId IS NULL OR tvgId NOT LIKE 'fix:%')",
    )
    suspend fun notePlaylistIds()

    @Query("UPDATE items SET tvgId = 'fix:' || id WHERE id IN (SELECT itemId FROM guide_fixes)")
    suspend fun useFixedIds()

    /** Guide channels whose id or name has [query], for the picker. */
    @Query(
        "SELECT * FROM guide_channels WHERE name LIKE '%' || :query || '%' OR id LIKE '%' || :query || '%' " +
            "ORDER BY name LIMIT 200",
    )
    suspend fun searchChannels(query: String): List<GuideChannelEntity>

    @Query("SELECT name FROM guide_channels WHERE id = :id")
    suspend fun channelName(id: String): String?

    @Transaction
    suspend fun replaceChannels(channels: List<GuideChannelEntity>) {
        deleteChannels()
        channels.chunked(CHANNEL_CHUNK).forEach { insertChannels(it) }
    }

    @Query("DELETE FROM guide_channels")
    suspend fun deleteChannels()

    @Upsert
    suspend fun insertChannels(channels: List<GuideChannelEntity>)
}

private const val CHANNEL_CHUNK = 500
