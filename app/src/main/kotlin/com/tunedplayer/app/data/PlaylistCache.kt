package com.tunedplayer.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import com.tunedplayer.app.data.db.TunedDatabase
import java.io.File
import java.security.MessageDigest

/**
 * Switching back to a playlist used lately shows its channels, films, shows and guide at once, instead of an empty app
 * while everything downloads again (Melvin, 2026-11-20: "do 4, 6, 7, 8"; docs/decisions/onboarding.md).
 *
 * When the playlist in use changes, its cache (the tables [PlaylistSession.useLogin] empties) is copied to a file of
 * its own in [dir], one SQLite file per playlist, named by a hash of the playlist (never its address or login). Back on
 * that playlist, the copy fills the emptied tables a chunk per transaction, so the lists fill as it goes, and the usual
 * refresh then brings it up to date in the background. Only the newest [KEEP] copies are kept. A copy written by an
 * app with another database version is thrown away rather than read, since its columns may not match.
 *
 * The playlist's [PlaylistMemo] fingerprint travels with the copy, so a refresh that finds the very same file skips
 * saving it again. Signing out deletes the folder ([AppReset]); removing a playlist deletes its copy.
 */
class PlaylistCache(
    private val db: TunedDatabase,
    private val dir: File,
    private val memo: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** When the copied cache was last refreshed, so Settings and the refresh schedule carry on from there. */
    data class Synced(
        val playlist: Long,
        val guide: Long,
    )

    /** Copies the cache of [login], the playlist in use, to its own file, replacing an older copy. */
    fun save(
        login: Credentials,
        synced: Synced,
    ) {
        dir.mkdirs()
        val target = file(login)
        val tmp = File(dir, "${target.name}.tmp")
        deleteDb(tmp)
        val live = db.openHelper.readableDatabase
        SQLiteDatabase.openOrCreateDatabase(tmp, null).use { out ->
            out.beginTransaction()
            try {
                out.execSQL("CREATE TABLE $META (name TEXT PRIMARY KEY, value TEXT)")
                for (table in TABLES) copyOut(live, out, table)
                putMeta(out, VERSION, live.version.toString())
                putMeta(out, PLAYLIST_SYNC, synced.playlist.toString())
                putMeta(out, GUIDE_SYNC, synced.guide.toString())
                if (memo.exists()) putMeta(out, FINGERPRINT, memo.readText())
                out.setTransactionSuccessful()
            } finally {
                out.endTransaction()
            }
        }
        deleteDb(target)
        if (!tmp.renameTo(target)) deleteDb(tmp)
        prune()
    }

    /**
     * Fills the emptied cache from [login]'s copy, if there is one this app can read; null otherwise, and the playlist
     * downloads as it would without a copy.
     */
    fun restore(login: Credentials): Synced? {
        val source = file(login)
        if (!source.exists()) return null
        val live = db.openHelper.writableDatabase
        val synced =
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { snap ->
                if (meta(snap, VERSION) != live.version.toString() || TABLES.any { columns(snap, it) != columns(live, it) }) {
                    null
                } else {
                    for (table in TABLES) copyIn(snap, live, table)
                    val fingerprint = meta(snap, FINGERPRINT)
                    if (fingerprint != null) {
                        memo.parentFile?.mkdirs()
                        memo.writeText(fingerprint)
                    } else {
                        memo.delete()
                    }
                    Synced(meta(snap, PLAYLIST_SYNC)?.toLongOrNull() ?: 0, meta(snap, GUIDE_SYNC)?.toLongOrNull() ?: 0)
                }
            }
        // Used just now: the newest copy, last to be pruned. One that couldn't be read is gone.
        if (synced == null) deleteDb(source) else source.setLastModified(now())
        return synced
    }

    /** Whether [login] has a copy to switch back to. */
    fun has(login: Credentials): Boolean = file(login).exists()

    /** A removed playlist's copy. */
    fun forget(login: Credentials) = deleteDb(file(login))

    /** Every copy (signing out, or the setting turned off). */
    fun clear() {
        dir.deleteRecursively()
    }

    private fun file(login: Credentials) = File(dir, "${key(login)}.db")

    private fun prune() {
        dir
            .listFiles { f -> f.name.endsWith(".db") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach(::deleteDb)
    }

    private fun copyOut(
        live: SupportSQLiteDatabase,
        out: SQLiteDatabase,
        table: String,
    ) {
        val cols = columns(live, table)
        out.execSQL("CREATE TABLE `$table` (${cols.joinToString { "`$it`" }})")
        out.compileStatement(insert(table, cols, replace = false)).use { statement ->
            chunks(table, cols) { sql -> live.query(sql) }.forEach { row ->
                statement.bindRow(row)
                statement.executeInsert()
            }
        }
    }

    private fun copyIn(
        snap: SQLiteDatabase,
        live: SupportSQLiteDatabase,
        table: String,
    ) {
        val cols = columns(live, table)
        val rows = chunks(table, cols) { sql -> snap.rawQuery(sql, null) }
        // A chunk per transaction: Room tells the lists after each one, and nothing waits on the database for long.
        rows.chunked(CHUNK).forEach { chunk ->
            db.runInTransaction(
                Runnable {
                    live.compileStatement(insert(table, cols, replace = true)).use { statement ->
                        for (row in chunk) {
                            statement.bindRow(row)
                            statement.executeInsert()
                        }
                    }
                },
            )
        }
    }

    /**
     * A table's rows a chunk at a time, in rowid order, read only as they are used. Programmes that have ended are left
     * out: a copy can be days old, and the guide only shows what is on now or later.
     */
    private fun chunks(
        table: String,
        cols: List<String>,
        query: (String) -> Cursor,
    ): Sequence<Array<Any?>> = sequence {
        val filter = if (table == PROGRAMMES) " AND `stop` > ${now()}" else ""
        val select = cols.joinToString { "`$it`" }
        var after = Long.MIN_VALUE
        while (true) {
            val chunk = mutableListOf<Array<Any?>>()
            // The last rowid is written into the query rather than bound: a bound value would arrive as text.
            query("SELECT rowid, $select FROM `$table` WHERE rowid > $after$filter ORDER BY rowid LIMIT $CHUNK").use { c ->
                while (c.moveToNext()) {
                    after = c.getLong(0)
                    chunk += Array(cols.size) { c.value(it + 1) }
                }
            }
            yieldAll(chunk)
            if (chunk.size < CHUNK) break
        }
    }

    private fun SQLiteProgram.bindRow(row: Array<Any?>) {
        clearBindings()
        row.forEachIndexed { i, value ->
            when (value) {
                null -> bindNull(i + 1)
                is Long -> bindLong(i + 1, value)
                is Double -> bindDouble(i + 1, value)
                is ByteArray -> bindBlob(i + 1, value)
                else -> bindString(i + 1, value.toString())
            }
        }
    }

    private fun SupportSQLiteProgram.bindRow(row: Array<Any?>) {
        clearBindings()
        row.forEachIndexed { i, value ->
            when (value) {
                null -> bindNull(i + 1)
                is Long -> bindLong(i + 1, value)
                is Double -> bindDouble(i + 1, value)
                is ByteArray -> bindBlob(i + 1, value)
                else -> bindString(i + 1, value.toString())
            }
        }
    }

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i)
        else -> getString(i)
    }

    private fun insert(
        table: String,
        cols: List<String>,
        replace: Boolean,
    ) = "INSERT${if (replace) " OR REPLACE" else ""} INTO `$table` (${cols.joinToString { "`$it`" }}) " +
        "VALUES (${cols.joinToString { "?" }})"

    private fun columns(
        live: SupportSQLiteDatabase,
        table: String,
    ): List<String> = live.query("PRAGMA table_info(`$table`)").use { c -> names(c) }

    private fun columns(
        snap: SQLiteDatabase,
        table: String,
    ): List<String> = snap.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> names(c) }

    private fun names(c: Cursor): List<String> {
        val name = c.getColumnIndexOrThrow("name")
        return buildList { while (c.moveToNext()) add(c.getString(name)) }
    }

    private fun putMeta(
        out: SQLiteDatabase,
        name: String,
        value: String,
    ) = out.execSQL("INSERT OR REPLACE INTO $META (name, value) VALUES (?, ?)", arrayOf(name, value))

    private fun meta(
        snap: SQLiteDatabase,
        name: String,
    ): String? = snap.rawQuery("SELECT value FROM $META WHERE name = ?", arrayOf(name)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    private fun deleteDb(file: File) {
        file.delete()
        File(file.path + "-journal").delete()
    }

    companion object {
        /** What switching playlists empties, and so what a copy holds. */
        val TABLES = listOf("items", "show_groups", "show_covers", PROGRAMMES, "catchup", "channel_scan")

        /** The folder under the app's files that aren't backed up. */
        const val DIR = "playlist-copies"

        /** Copies kept besides the playlist in use. */
        const val KEEP = 3

        /** A playlist as a file name: a hash of what tells playlists apart, never the address or the login itself. */
        fun key(login: Credentials): String = MessageDigest
            .getInstance("SHA-256")
            .digest("${login.server}\n${login.username}\n${login.playlistUrl}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(KEY_LENGTH)

        private const val PROGRAMMES = "programmes"
        private const val META = "tuned_meta"
        private const val VERSION = "db_version"
        private const val PLAYLIST_SYNC = "last_playlist_sync"
        private const val GUIDE_SYNC = "last_guide_sync"
        private const val FINGERPRINT = "fingerprint"
        private const val CHUNK = 2_000
        private const val KEY_LENGTH = 32
    }
}
