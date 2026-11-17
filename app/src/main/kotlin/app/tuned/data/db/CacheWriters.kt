package app.tuned.data.db

import androidx.room.withTransaction

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
 * Writes a downloaded playlist into the live table a batch at a time, without emptying it first. Every stream keeps
 * its stable id, so a batch replaces rows in place; the lists keep showing (and searching) the cache throughout, and
 * nothing waits on one long transaction. Streams the new playlist no longer has are removed at the end.
 */
class PlaylistWriter(
    private val db: TunedDatabase,
) {
    private var stale: MutableSet<Long> = HashSet()
    private val seen = HashSet<Long>()

    suspend fun begin() {
        stale = db.items().allIds().toHashSet()
        seen.clear()
    }

    /** Adds a stream unless the playlist already listed it; returns false for a repeat. */
    fun accept(id: Long): Boolean {
        if (!seen.add(id)) return false
        stale.remove(id)
        return true
    }

    val count get() = seen.size

    suspend fun add(batch: List<ItemEntity>) {
        if (batch.isNotEmpty()) db.items().insertAll(batch)
    }

    /** Removes what the playlist dropped. An empty playlist changes nothing, so it can't wipe a good cache. */
    suspend fun commit(): Int {
        if (seen.isEmpty()) return 0
        stale.chunked(DELETE_CHUNK).forEach { db.items().deleteIds(it) }
        stale = HashSet()
        return seen.size
    }

    private companion object {
        /** Under SQLite's limit on parameters in one statement. */
        const val DELETE_CHUNK = 500
    }
}
