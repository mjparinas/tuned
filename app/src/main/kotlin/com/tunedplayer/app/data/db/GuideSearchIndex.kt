package com.tunedplayer.app.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The full-text index over programme titles that whole-guide search runs on (Melvin, 2026-11-20: "improve guide
 * search"), so "leafs" finds tonight's and next week's games, not only what's on within the hour.
 *
 * It is an FTS4 table over `programmes` (external content), but not one of Room's entities: Room would keep it in
 * step with triggers, and a trigger per row would turn the guide's one-statement swap ([GuideWriter.commit]) into
 * seconds of indexing while every list waits. Instead [GuideWriter] indexes each batch into a side index as it writes
 * the side table, and the swap renames it into place. Titles are indexed as [Search.normalize] writes them, so
 * accents and case don't matter, the same as names in `items_fts`.
 *
 * Because Room doesn't create it, every use first makes sure it exists ([ensure]); a cache from before it existed is
 * indexed once in the background ([fillIfMissing]). Signing out empties it with the rest of the database: the wipe
 * rebuilds every virtual table from its content, and `programmes` is empty by then.
 */
object GuideSearchIndex {
    const val TABLE = "programmes_fts"
    const val NEXT = "programmes_fts_next"

    /** Held while the index is rebuilt or swapped, so a background fill never writes into a newer guide's index. */
    val lock = Mutex()

    private fun create(name: String) = "CREATE VIRTUAL TABLE IF NOT EXISTS `$name` USING FTS4(title, content=`programmes`)"

    fun ensure(sql: SupportSQLiteDatabase) = sql.execSQL(create(TABLE))

    /** A fresh side index for a guide being written, dropping one a killed refresh may have left. */
    fun beginNext(sql: SupportSQLiteDatabase) {
        sql.execSQL("DROP TABLE IF EXISTS `$NEXT`")
        sql.execSQL(create(NEXT))
    }

    fun dropNext(sql: SupportSQLiteDatabase) = sql.execSQL("DROP TABLE IF EXISTS `$NEXT`")

    /** Inside the swap's transaction: the side index becomes the index. */
    fun swapIn(sql: SupportSQLiteDatabase) {
        sql.execSQL("DROP TABLE IF EXISTS `$TABLE`")
        sql.execSQL("ALTER TABLE `$NEXT` RENAME TO `$TABLE`")
    }

    const val INSERT_NEXT = "INSERT INTO `$NEXT` (docid, title) VALUES (?, ?)"

    /** Indexes a guide cached before this index existed, a chunk per transaction so lists never wait long. */
    suspend fun fillIfMissing(db: TunedDatabase) = lock.withLock {
        val sql = db.openHelper.writableDatabase
        ensure(sql)
        if (count(sql, "SELECT COUNT(*) FROM `${TABLE}_docsize`") > 0) return@withLock
        if (count(sql, "SELECT COUNT(*) FROM programmes") == 0L) return@withLock
        var after = -1L
        while (true) {
            val chunk = mutableListOf<Pair<Long, String>>()
            sql.query("SELECT rowId, title FROM programmes WHERE rowId > ? ORDER BY rowId LIMIT $CHUNK", arrayOf<Any>(after)).use { c ->
                while (c.moveToNext()) chunk += c.getLong(0) to c.getString(1)
            }
            if (chunk.isEmpty()) break
            db.withTransaction {
                sql.compileStatement("INSERT INTO `$TABLE` (docid, title) VALUES (?, ?)").use { insert ->
                    for ((rowId, title) in chunk) {
                        insert.bindLong(1, rowId)
                        insert.bindString(2, Search.normalize(title))
                        insert.executeInsert()
                        insert.clearBindings()
                    }
                }
            }
            after = chunk.last().first
        }
    }

    /**
     * Programmes on now or still to come whose titles match every typed word by its start, soonest first, each on the
     * channel it's best watched on: a favourite first, then the playlist's order. A programme whose channels are all
     * hidden (country, group, dead, adult or kids profile) is left out, as on Live TV.
     */
    suspend fun search(
        db: TunedDatabase,
        text: String,
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        limit: Int,
    ): List<GuideHit> {
        val match = Search.match(text)
        if (match.isEmpty()) return emptyList()
        ensure(db.openHelper.writableDatabase)
        val keys = hidden.ifEmpty { listOf(NOTHING) }
        val marks = keys.joinToString { "?" }
        // The channel filter is the same as Live TV's, written for positional arguments: each `IN (:hidden)` takes
        // its own copy of the hidden keys.
        val filter = channelFilter(marks)
        // Positional arguments go in the order they appear: the channel filter (in the join) comes first.
        val args = mutableListOf<Any>()
        repeat(filter.uses) { args += keys }
        args += if (adult) 1 else 0
        repeat(filter.kidsUses) { args += keys }
        args += listOf(match, now, now + WEEK, limit)
        val query =
            """
            SELECT p.rowId AS rowId, p.channelId AS channelId, p.start AS start, p.stop AS stop, p.title AS title,
                c.id AS itemId, c.name AS channelName, c.logo AS logo
            FROM programmes p
            JOIN items c ON c.id = (
                SELECT i.id FROM items i
                LEFT JOIN favourites f ON f.itemId = i.id AND f.profileId = $ME
                WHERE i.tvgId = p.channelId AND ${filter.sql}
                ORDER BY (f.itemId IS NULL), i.sortOrder
                LIMIT 1
            )
            WHERE p.rowId IN (SELECT docid FROM `$TABLE` WHERE `$TABLE` MATCH ?) AND p.stop > ? AND p.start < ?
            ORDER BY p.start, c.sortOrder
            LIMIT ?
            """.trimIndent()
        return db.programmes().searchGuide(SimpleSQLiteQuery(query, args.toTypedArray()))
    }

    private class Filter(
        val sql: String,
        val uses: Int,
        val kidsUses: Int,
    )

    /** Live TV's channel filter (`searchChannels`), with `?` lists in place of Room's `:hidden`. */
    private fun channelFilter(marks: String): Filter {
        val before =
            "i.kind = 0 AND COALESCE(i.region, 'none') NOT IN ($marks) AND ('g:' || i.kind || ':' || i.groupName) NOT IN ($marks) " +
                "AND NOT ('scan:dead' IN ($marks) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1)) " +
                "AND NOT ('placeholder' IN ($marks) AND ((' ' || i.searchName) GLOB '* no event*' " +
                "OR (' ' || i.searchName) GLOB '* no live event*')) " +
                "AND NOT ('247' IN ($marks) AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*' " +
                "OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*' " +
                "OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*')) " +
                "AND (? OR i.adult = 0) "
        val kids = KIDS_ONLY_I.replace("(:hidden)", "($marks)")
        return Filter(before + kids, uses = 5, kidsUses = KIDS_ONLY_I.split("(:hidden)").size - 1)
    }

    private fun count(
        sql: SupportSQLiteDatabase,
        query: String,
    ): Long = sql.query(query).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** A key no setting writes, so an empty hidden list still makes valid SQL (`IN ()` isn't). */
    private const val NOTHING = "\u0000"
    private const val CHUNK = 5_000
    private const val WEEK = 7L * 24 * 60 * 60 * 1000
}

/** A programme found by [GuideSearchIndex.search], with the channel it plays on. */
data class GuideHit(
    val rowId: Long,
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val itemId: Long,
    val channelName: String,
    val logo: String?,
)
