package app.tuned.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What Live TV's quick filters ([app.tuned.ui.browse.LiveQuick]) can offer; the narrowing itself is [LIVE_QUICK]. */
@Dao
interface LiveFilterDao {
    /** The countries among the live channels on show, with how many each has; hidden and adult ones stay out. */
    @Query(
        "SELECT i.region, COUNT(*) AS count FROM items i WHERE i.kind = 0 AND i.region IS NOT NULL " +
            "AND i.region NOT IN (:hidden) AND ('g:0:' || i.groupName) NOT IN (:hidden) AND (:adult OR i.adult = 0) $KIDS_ONLY_I " +
            "GROUP BY i.region",
    )
    fun liveRegions(
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<RegionCount>>

    /** Whether the playlist has any live channel marked adult, so the Adult filter is offered only where it finds some. */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind = 0 AND adult = 1)")
    fun hasAdultChannels(): Flow<Boolean>
}
