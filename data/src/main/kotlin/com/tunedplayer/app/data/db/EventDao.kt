package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Query

/** Live Events: the guide's programmes on sports and pay-per-view channels, and those channels' names. */
@Dao
interface EventDao {
    /**
     * Live Events: what's on sports and pay-per-view channels from now until [to], for
     * [Events][com.tunedplayer.app.core.Events] to sort into events. The category words here are a quick first cut; the rules
     * in `Events` decide. Hidden countries and groups are left out; "No event" placeholders and channels a scan
     * found dead are kept, since the guide says an event is on them.
     */
    @Query(
        """
        SELECT i.id AS itemId, i.name AS channelName, i.groupName AS `group`, p.title, p.start, p.stop
        FROM programmes p
        JOIN items i ON i.tvgId = p.channelId AND i.kind = 0
        WHERE p.stop > :from AND p.start < :to
            $SPORTY_I
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        ORDER BY p.start, i.sortOrder
        LIMIT 20000
        """,
    )
    suspend fun eventProgrammes(
        from: Long,
        to: Long,
        hidden: List<String>,
        adult: Boolean,
    ): List<EventRow>

    /**
     * Live Events from channel names: the sports and pay-per-view channels [eventProgrammes] looks at, with or without a
     * guide, so [com.tunedplayer.app.core.ChannelEvents] can read an event from the name of one whose guide lists none.
     */
    @Query(
        """
        SELECT i.id AS itemId, i.name AS name FROM items i
        WHERE i.kind = 0
            $SPORTY_I
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        ORDER BY i.sortOrder
        LIMIT 20000
        """,
    )
    suspend fun eventChannels(
        hidden: List<String>,
        adult: Boolean,
    ): List<EventChannelRow>
}
