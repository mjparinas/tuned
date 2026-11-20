package com.tunedplayer.app.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteStatement

/**
 * Writes a downloaded guide without holding the database while it does. A guide runs to hundreds of thousands of
 * programmes and takes a while to read; written inside one transaction, every list and search would wait behind it,
 * because Room's paged lists read inside a transaction too. Instead the new guide goes into a side table that nothing
 * reads, a batch at a time, and only the swap at the end, a single statement copying rows inside SQLite, holds the
 * database: well under a second instead of the whole read. Until then the old guide shows and searches as before.
 */
class GuideWriter(
    private val db: TunedDatabase,
) {
    private val sql get() = db.openHelper.writableDatabase
    private var count = 0

    /** Starts a fresh side table, dropping one a killed refresh may have left. */
    fun begin() {
        sql.execSQL("DROP TABLE IF EXISTS $NEXT")
        sql.execSQL(
            "CREATE TABLE $NEXT (channelId TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, " +
                "title TEXT NOT NULL, description TEXT)",
        )
        count = 0
    }

    suspend fun add(batch: List<ProgrammeEntity>) {
        if (batch.isEmpty()) return
        db.withTransaction {
            sql.compileStatement("INSERT INTO $NEXT (channelId, start, stop, title, description) VALUES (?, ?, ?, ?, ?)").use { insert ->
                for (p in batch) {
                    insert.bindString(1, p.channelId)
                    insert.bindLong(2, p.start)
                    insert.bindLong(3, p.stop)
                    insert.bindString(4, p.title)
                    val description = p.description
                    if (description == null) insert.bindNull(5) else insert.bindString(5, description)
                    insert.executeInsert()
                    insert.clearBindings()
                }
            }
        }
        count += batch.size
    }

    /**
     * Swaps the new guide in and returns how many programmes it holds. An empty guide keeps the old one: a server
     * that answers with nothing shouldn't wipe a good cache.
     */
    suspend fun commit(): Int {
        if (count == 0) return 0
        db.withTransaction {
            sql.execSQL("DELETE FROM programmes")
            sql.execSQL(
                "INSERT INTO programmes (channelId, start, stop, title, description) " +
                    "SELECT channelId, start, stop, title, description FROM $NEXT",
            )
        }
        discard()
        Upkeep.optimize(db)
        return count
    }

    /** Throws the half-written guide away; the old one stays. */
    fun discard() {
        sql.execSQL("DROP TABLE IF EXISTS $NEXT")
    }

    private companion object {
        const val NEXT = "programmes_next"
    }
}

/**
 * Writes a downloaded playlist without touching the lists until it's all in. A playlist can run to hundreds of
 * thousands of streams; written straight into the live table, every batch would make each open list and search start
 * over, so on a big playlist they never finished while a refresh ran. Instead the new playlist goes into a side table
 * that nothing reads, and [commit] then changes only the streams that differ: removed, renamed, moved or new. It does
 * that a few thousand rows per transaction, so a search waits at most a moment, and each stream is always either its
 * old self or its new self. The very first download has no cache to keep, so it fills the lists as it arrives.
 */
class PlaylistWriter(
    private val db: TunedDatabase,
) {
    private val sql get() = db.openHelper.writableDatabase
    private val seen = HashSet<Long>()
    private var direct = false
    private var batches = 0

    suspend fun begin() {
        seen.clear()
        batches = 0
        discard()
        direct = !db.items().any()
        if (!direct) sql.execSQL("CREATE TABLE $NEXT ($SCHEMA)")
    }

    /** Adds a stream unless the playlist already listed it; returns false for a repeat. */
    fun accept(id: Long): Boolean = seen.add(id)

    val count get() = seen.size

    suspend fun add(batch: List<ItemEntity>) {
        if (batch.isEmpty()) return
        if (direct) {
            db.items().insertAll(batch)
            // The first download fills the lists as it goes; the Shows tab catches up every so often.
            if (++batches % SHOWS_EVERY == 0) ShowGroups.rebuild(db)
            return
        }
        db.withTransaction {
            sql.compileStatement("INSERT OR REPLACE INTO $NEXT ($COLUMNS) VALUES (${COLUMN_LIST.joinToString { "?" }})").use { insert ->
                for (item in batch) {
                    insert.bind(item)
                    insert.executeInsert()
                    insert.clearBindings()
                }
            }
        }
    }

    /**
     * Brings the live table in line with the new playlist and returns how many streams it holds. An empty playlist
     * changes nothing, so a server that answers with nothing can't wipe a good cache.
     */
    suspend fun commit(): Int {
        if (seen.isEmpty()) {
            discard()
            return 0
        }
        // The person's renamed channels and logos go over the provider's, before the cache takes them.
        ChannelEdits.applyTo(sql, if (direct) "items" else NEXT)
        if (!direct) apply()
        ShowGroups.rebuild(db)
        Upkeep.optimize(db)
        return seen.size
    }

    /** Changes only the streams that differ, a chunk per transaction. */
    private suspend fun apply() {
        // Which streams differ, worked out once, inside SQLite.
        sql.execSQL("DROP TABLE IF EXISTS temp.$CHANGED")
        sql.execSQL("CREATE TEMP TABLE $CHANGED (seq INTEGER PRIMARY KEY, id INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO $CHANGED (id) SELECT id FROM items WHERE id NOT IN (SELECT id FROM $NEXT)")
        sql.execSQL("INSERT INTO $CHANGED (id) SELECT n.id FROM $NEXT n JOIN items i ON i.id = n.id WHERE NOT ($SAME)")
        sql.execSQL("INSERT INTO $CHANGED (id) SELECT id FROM $NEXT WHERE id NOT IN (SELECT id FROM items)")
        val total = sql.query("SELECT COUNT(*) FROM $CHANGED").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        var from = 1L
        while (from <= total) {
            val to = from + APPLY_CHUNK - 1
            val chunk = "(SELECT id FROM $CHANGED WHERE seq BETWEEN $from AND $to)"
            db.withTransaction {
                sql.execSQL("DELETE FROM items WHERE id IN $chunk")
                sql.execSQL("INSERT INTO items ($COLUMNS) SELECT $COLUMNS FROM $NEXT WHERE id IN $chunk")
            }
            from = to + 1
        }
        sql.execSQL("DROP TABLE IF EXISTS temp.$CHANGED")
        discard()
    }

    /** Throws a half-written playlist away; the cache stays as it was. */
    fun discard() {
        sql.execSQL("DROP TABLE IF EXISTS $NEXT")
    }

    private fun SupportSQLiteStatement.bind(item: ItemEntity) {
        bindLong(1, item.id)
        bindLong(2, item.kind.toLong())
        bindString(3, item.name)
        bindString(4, item.searchName)
        bindString(5, item.groupName)
        bindText(6, item.region)
        bindLong(7, if (item.adult) 1 else 0)
        bindText(8, item.logo)
        bindString(9, item.url)
        bindText(10, item.tvgId)
        bindText(11, item.showKey)
        bindText(12, item.showName)
        bindNumber(13, item.season)
        bindNumber(14, item.episode)
        bindLong(15, item.sortOrder.toLong())
        bindLong(16, item.number.toLong())
    }

    private fun SupportSQLiteStatement.bindText(
        index: Int,
        value: String?,
    ) = if (value == null) bindNull(index) else bindString(index, value)

    private fun SupportSQLiteStatement.bindNumber(
        index: Int,
        value: Int?,
    ) = if (value == null) bindNull(index) else bindLong(index, value.toLong())

    private companion object {
        const val NEXT = "items_next"
        const val CHANGED = "items_changed"

        /** Small enough that a search waiting behind one is never kept long. */
        const val APPLY_CHUNK = 2_000

        /** Batches between Shows tab refreshes during a first download: every 25,000 streams or so. */
        const val SHOWS_EVERY = 25

        /** The columns of [ItemEntity], in the order [bind] fills them. */
        val COLUMN_LIST =
            listOf(
                "id",
                "kind",
                "name",
                "searchName",
                "groupName",
                "region",
                "adult",
                "logo",
                "url",
                "tvgId",
                "showKey",
                "showName",
                "season",
                "episode",
                "sortOrder",
                "number",
            )
        val COLUMNS = COLUMN_LIST.joinToString()
        const val SCHEMA =
            "id INTEGER NOT NULL PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL, searchName TEXT NOT NULL, " +
                "groupName TEXT NOT NULL, region TEXT, adult INTEGER NOT NULL, logo TEXT, url TEXT NOT NULL, tvgId TEXT, " +
                "showKey TEXT, showName TEXT, season INTEGER, episode INTEGER, sortOrder INTEGER NOT NULL, " +
                "number INTEGER NOT NULL DEFAULT 0"

        /** A stream that came back exactly as it was. */
        val SAME = COLUMN_LIST.drop(1).joinToString(" AND ") { "i.$it IS n.$it" }
    }
}

/**
 * Keeps the Shows tab's per-show table ([ShowGroupEntity]) in step with the playlist. A show's picture is its own
 * cover when one is kept ([ShowCoverEntity]), ahead of its episodes' pictures, which can be their screenshots.
 */
object ShowGroups {
    const val COLUMNS = "showKey, groupName, region, adult, showName, logo, episodes, sortOrder"
    const val SELECT =
        "SELECT showKey, groupName, region, adult, COALESCE(MIN(showName), showKey), " +
            "COALESCE((SELECT c.cover FROM show_covers c WHERE c.showKey = items.showKey), MAX(logo)), COUNT(*), MIN(sortOrder) " +
            "FROM items WHERE kind = 2 AND showKey IS NOT NULL GROUP BY showKey, groupName, region, adult"

    /** Worked out beside the live table, then swapped in with one quick copy, so the Shows tab never waits long. */
    suspend fun rebuild(db: TunedDatabase) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TABLE IF EXISTS temp.show_groups_next")
        sql.execSQL("CREATE TEMP TABLE show_groups_next AS $SELECT")
        db.withTransaction {
            sql.execSQL("DELETE FROM show_groups")
            sql.execSQL("INSERT INTO show_groups ($COLUMNS) SELECT * FROM temp.show_groups_next")
        }
        sql.execSQL("DROP TABLE IF EXISTS temp.show_groups_next")
    }
}

/** Database housekeeping that keeps queries fast as the cache changes. */
object Upkeep {
    /**
     * Lets SQLite refresh what it knows about the tables (`PRAGMA optimize`) after a big change, so it keeps picking
     * the right index. Cheap: it only analyzes what changed enough to matter.
     */
    fun optimize(db: TunedDatabase) {
        db.openHelper.writableDatabase
            .query("PRAGMA optimize")
            .use { it.moveToFirst() }
    }
}
