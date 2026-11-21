package com.tunedplayer.app.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteStatement
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.sync.withLock

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
                "title TEXT NOT NULL, description TEXT, genre INTEGER NOT NULL DEFAULT 0)",
        )
        GuideSearchIndex.beginNext(sql)
        count = 0
    }

    suspend fun add(batch: List<ProgrammeEntity>) {
        if (batch.isEmpty()) return
        db.withTransaction {
            val index = sql.compileStatement(GuideSearchIndex.INSERT_NEXT)
            sql.compileStatement(INSERT).use { insert ->
                index.use {
                    for (p in batch) {
                        insert.bindString(1, p.channelId)
                        insert.bindLong(2, p.start)
                        insert.bindLong(3, p.stop)
                        insert.bindString(4, p.title)
                        val description = p.description
                        if (description == null) insert.bindNull(5) else insert.bindString(5, description)
                        insert.bindLong(6, p.genre.toLong())
                        val rowId = insert.executeInsert()
                        insert.clearBindings()
                        // The title goes in the side index under the same row id, which the swap keeps.
                        index.bindLong(1, rowId)
                        index.bindString(2, Search.normalize(p.title))
                        index.executeInsert()
                        index.clearBindings()
                    }
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
        GuideSearchIndex.lock.withLock {
            db.withTransaction {
                sql.execSQL("DELETE FROM programmes")
                // Row ids are carried over so the side search index, keyed by them, still points at the right titles.
                sql.execSQL(
                    "INSERT INTO programmes (rowId, channelId, start, stop, title, description, genre) " +
                        "SELECT rowid, channelId, start, stop, title, description, genre FROM $NEXT",
                )
                GuideSearchIndex.swapIn(sql)
            }
        }
        discard()
        Upkeep.optimize(db)
        return count
    }

    /** Throws the half-written guide away; the old one stays. */
    fun discard() {
        sql.execSQL("DROP TABLE IF EXISTS $NEXT")
        GuideSearchIndex.dropNext(sql)
    }

    private companion object {
        const val NEXT = "programmes_next"
        const val INSERT = "INSERT INTO $NEXT (channelId, start, stop, title, description, genre) VALUES (?, ?, ?, ?, ?, ?)"
    }
}

/**
 * Writes a downloaded playlist without touching the lists until it's all in. A playlist can run to hundreds of
 * thousands of streams; written straight into the live table, every batch would make each open list and search start
 * over, so on a big playlist they never finished while a refresh ran. Instead the new playlist goes into a side table
 * that nothing reads, and [commit] then changes only the streams that differ: removed, renamed, moved or new. It does
 * that a few thousand rows per transaction, so a search waits at most a moment, and each stream is always either its
 * old self or its new self.
 *
 * When most of the cache changes, above all on the very first download, changing it row by row is the slow part: each
 * row updates ten indexes and the search index, and on a TV box that ran at a few hundred streams a second (Melvin,
 * 2026-11-21: "processing channels on my TV is incredibly slow"). Then [commit] builds the table in one go instead:
 * the indexes and the search index are dropped, the side table copied in, and they're built again, sorted once, inside
 * one transaction, so a refresh killed halfway leaves the cache as it was. The first download still puts its first
 * [PREVIEW] streams straight into the lists, so there is something to watch at once.
 */
class PlaylistWriter(
    private val db: TunedDatabase,
    /**
     * Which cached streams this playlist replaces, as SQL on `items`; the rest are left alone. A portal's live channels
     * and its films and shows are refreshed apart (`kind = 0`, `kind != 0`). Null: the whole cache.
     */
    private val scope: String? = null,
) {
    private val sql get() = db.openHelper.writableDatabase
    private val where = scope?.let { " WHERE ($it)" }.orEmpty()
    private val seen = HashSet<Long>()

    /** Nothing of this scope was cached: the first download, which is built in one go and shows its first rows at once. */
    private var fresh = false
    private var straight = 0

    suspend fun begin() {
        seen.clear()
        straight = 0
        discard()
        fresh = offMain { sql.query("SELECT EXISTS(SELECT 1 FROM items$where)").use { it.moveToFirst() && it.getInt(0) == 0 } }
        // No primary key: rows are appended in the order they arrive, which is far quicker than keeping them sorted by
        // id as they come (ids are hashes). Repeats never get here ([accept]); [commit] adds the id index it needs.
        sql.execSQL("CREATE TABLE $NEXT ($SCHEMA)")
    }

    /** Adds a stream unless the playlist already listed it; returns false for a repeat. */
    fun accept(id: Long): Boolean = seen.add(id)

    val count get() = seen.size

    suspend fun add(batch: List<ItemEntity>) {
        if (batch.isEmpty()) return
        // The first download's first streams show at once, so there is something to watch while the rest is read.
        if (fresh && straight < PREVIEW) {
            db.items().insertAll(batch)
            straight += batch.size
        }
        db.withTransaction {
            sql.compileStatement("INSERT INTO $NEXT ($COLUMNS) VALUES (${COLUMN_LIST.joinToString { "?" }})").use { insert ->
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
    suspend fun commit(
        /** Streams applied to the cache so far and how many differ, as each chunk lands; or steps of a rebuild. */
        onApply: (applied: Long, total: Long) -> Unit = { _, _ -> },
    ): Int {
        if (seen.isEmpty()) {
            discard()
            return 0
        }
        offMain {
            sql.execSQL("CREATE UNIQUE INDEX $NEXT_ID ON $NEXT (id)")
            // The person's renamed channels and logos go over the provider's, before the cache takes them.
            ChannelEdits.applyTo(sql, NEXT)
        }
        if (fresh) {
            rebuild(onApply)
        } else {
            offMain { keepSortDetails() }
            apply(onApply)
        }
        ShowGroups.rebuild(db)
        // Rewritten films start without what Top rated sorts on: put the posters' ratings back.
        TitleRatingsSql.applyFilms(db)
        Upkeep.optimize(db)
        return seen.size
    }

    /**
     * Streams the new playlist lists without a time or rating keep the ones already known: those the Xtream API gave
     * ([com.tunedplayer.app.data.XtreamDetails]), or the time Tuned first saw them. A stream that is new to the cache
     * and has no time of its own is stamped now, so newest first works on a plain M3U too from its second refresh.
     */
    private fun keepSortDetails() {
        val now = System.currentTimeMillis() / MILLIS
        sql.execSQL(
            "UPDATE $NEXT SET added = COALESCE((SELECT i.added FROM items i WHERE i.id = $NEXT.id), $now) WHERE added = 0 AND kind != 0",
        )
        sql.execSQL(
            "UPDATE $NEXT SET rating = (SELECT i.rating FROM items i WHERE i.id = $NEXT.id) WHERE rating IS NULL AND kind != 0",
        )
        sql.execSQL(
            "UPDATE $NEXT SET year = (SELECT i.year FROM items i WHERE i.id = $NEXT.id) WHERE year IS NULL AND kind != 0",
        )
    }

    /** Changes only the streams that differ, a chunk per transaction. */
    private suspend fun apply(onApply: (applied: Long, total: Long) -> Unit) {
        // Which streams differ, worked out once, inside SQLite.
        sql.execSQL("DROP TABLE IF EXISTS temp.$CHANGED")
        sql.execSQL("CREATE TEMP TABLE $CHANGED (seq INTEGER PRIMARY KEY, id INTEGER NOT NULL)")
        val inScope = scope?.let { " AND ($it)" }.orEmpty()
        sql.execSQL("INSERT INTO $CHANGED (id) SELECT id FROM items WHERE id NOT IN (SELECT id FROM $NEXT)$inScope")
        sql.execSQL("INSERT INTO $CHANGED (id) SELECT n.id FROM $NEXT n JOIN items i ON i.id = n.id WHERE NOT ($SAME)")
        sql.execSQL("INSERT INTO $CHANGED (id) SELECT id FROM $NEXT WHERE id NOT IN (SELECT id FROM items)")
        val total = sql.query("SELECT COUNT(*) FROM $CHANGED").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        // Most of it changed (a reordered or replaced playlist): building the table again beats changing each row.
        if (total >= REBUILD_MIN && total * 2 >= seen.size) {
            sql.execSQL("DROP TABLE IF EXISTS temp.$CHANGED")
            return rebuild(onApply)
        }
        if (total > 0) onApply(0, total)
        var from = 1L
        while (from <= total) {
            val to = from + APPLY_CHUNK - 1
            val chunk = "(SELECT id FROM $CHANGED WHERE seq BETWEEN $from AND $to)"
            db.withTransaction {
                sql.execSQL("DELETE FROM items WHERE id IN $chunk")
                sql.execSQL("INSERT INTO items ($COLUMNS) SELECT $COLUMNS FROM $NEXT WHERE id IN $chunk")
            }
            from = to + 1
            onApply(minOf(to, total), total)
        }
        sql.execSQL("DROP TABLE IF EXISTS temp.$CHANGED")
        discard()
    }

    /**
     * Replaces this scope's cached streams with the side table's in one transaction: the indexes and the search index
     * are dropped first and built again after the copy, each sorted once, rather than updated for every row. They are
     * read back from the schema Room made, so they come back exactly as they were. Lists read the old cache meanwhile.
     */
    private suspend fun rebuild(onApply: (applied: Long, total: Long) -> Unit) {
        val schema =
            offMain {
                sql
                    .query(
                        "SELECT type, name, sql FROM sqlite_master WHERE tbl_name = 'items' AND sql IS NOT NULL AND " +
                            "(type = 'index' OR (type = 'trigger' AND name LIKE 'room_fts_content_sync_items_fts_%'))",
                    ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }
            }
        val indexes = schema.count { it.first == "index" }
        // Steps: the copy, each index, and the search index.
        val total = (indexes + 2).toLong()
        var done = 0L
        onApply(done, total)
        db.withTransaction {
            for ((type, name, _) in schema) sql.execSQL("DROP ${type.uppercase()} IF EXISTS `$name`")
            sql.execSQL("DELETE FROM items$where")
            sql.execSQL("INSERT INTO items ($COLUMNS) SELECT $COLUMNS FROM $NEXT ORDER BY id")
            onApply(++done, total)
            for ((type, _, create) in schema) {
                sql.execSQL(create)
                if (type == "index") onApply(++done, total)
            }
            // The search index reads every name again from the table it covers.
            sql.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
            onApply(++done, total)
        }
        discard()
    }

    /** Throws a half-written playlist away; the cache stays as it was. */
    fun discard() {
        sql.execSQL("DROP TABLE IF EXISTS $NEXT")
        GuideSearchIndex.dropNext(sql)
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
        bindLong(17, item.added)
        bindNumber(18, item.year)
        val rating = item.rating
        if (rating == null) bindNull(19) else bindDouble(19, rating)
        bindString(20, item.sortTitle)
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
        const val MILLIS = 1_000L

        /** Small enough that a search waiting behind one is never kept long. */
        const val APPLY_CHUNK = 2_000

        const val NEXT_ID = "items_next_id"

        /** Streams the first download shows straight away, before the rest is read. */
        const val PREVIEW = 2_000

        /** Changed streams from which a refresh rebuilds the table rather than changing it row by row (when most changed). */
        const val REBUILD_MIN = 10_000

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
                "added",
                "year",
                "rating",
                "sortTitle",
            )
        val COLUMNS = COLUMN_LIST.joinToString()
        const val SCHEMA =
            "id INTEGER NOT NULL, kind INTEGER NOT NULL, name TEXT NOT NULL, searchName TEXT NOT NULL, " +
                "groupName TEXT NOT NULL, region TEXT, adult INTEGER NOT NULL, logo TEXT, url TEXT NOT NULL, tvgId TEXT, " +
                "showKey TEXT, showName TEXT, season INTEGER, episode INTEGER, sortOrder INTEGER NOT NULL, " +
                "number INTEGER NOT NULL DEFAULT 0, added INTEGER NOT NULL DEFAULT 0, year INTEGER, rating REAL, " +
                "sortTitle TEXT NOT NULL DEFAULT ''"

        /** A stream that came back exactly as it was. */
        val SAME = COLUMN_LIST.drop(1).joinToString(" AND ") { "i.$it IS n.$it" }
    }
}

/**
 * Keeps the Shows tab's per-show table ([ShowGroupEntity]) in step with the playlist. A show's picture is its own
 * cover when one is kept ([ShowCoverEntity]), ahead of its episodes' pictures, which can be their screenshots.
 */
object ShowGroups {
    const val COLUMNS = "showKey, groupName, region, adult, showName, logo, episodes, sortOrder, added, year, rating, sortTitle"

    /** The provider's details for a show ([ShowDetailsEntity]) go ahead of what its episodes carry. */
    const val SELECT =
        "SELECT showKey, groupName, region, adult, COALESCE(MIN(showName), showKey), " +
            "COALESCE((SELECT c.cover FROM show_covers c WHERE c.showKey = items.showKey), MAX(logo)), COUNT(*), MIN(sortOrder), " +
            "MAX(COALESCE((SELECT d.added FROM show_details d WHERE d.showKey = items.showKey), 0), MAX(added)), " +
            "COALESCE((SELECT d.year FROM show_details d WHERE d.showKey = items.showKey), MIN(year)), " +
            "COALESCE((SELECT d.rating FROM show_details d WHERE d.showKey = items.showKey), MAX(rating)), " +
            "COALESCE(MIN(NULLIF(sortTitle, '')), '') " +
            "FROM items WHERE kind = 2 AND showKey IS NOT NULL GROUP BY showKey, groupName, region, adult"

    /** Worked out beside the live table, then swapped in with one quick copy, so the Shows tab never waits long. */
    suspend fun rebuild(db: TunedDatabase) = offMain {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP TABLE IF EXISTS temp.show_groups_next")
        sql.execSQL("CREATE TEMP TABLE show_groups_next AS $SELECT")
        db.withTransaction {
            sql.execSQL("DELETE FROM show_groups")
            sql.execSQL("INSERT INTO show_groups ($COLUMNS) SELECT * FROM temp.show_groups_next")
            // The rating each card shows (TMDB's) goes over the provider's, so Top rated sorts on it.
            sql.execSQL(TitleRatingsSql.SHOWS)
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
    suspend fun optimize(db: TunedDatabase) = offMain {
        db.openHelper.writableDatabase
            .query("PRAGMA optimize")
            .use { it.moveToFirst() }
    }
}
