package com.tunedplayer.app.data

import androidx.room.withTransaction
import com.tunedplayer.app.core.CatchUp
import com.tunedplayer.app.core.Episodes
import com.tunedplayer.app.core.XtreamApi
import com.tunedplayer.app.data.db.ShowDetailsEntity
import com.tunedplayer.app.data.db.ShowGroups
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When each film and show was added, its year and its rating, from the Xtream API, for the Movies and Shows sorts
 * (Melvin, 2026-11-20: "sort by newest first or sort by the rating"). An Xtream server's M3U file doesn't carry them,
 * so after a refresh that read the file, its film and show lists are read once more through the API, a stream at a
 * time, and only the films whose details changed are written. These are lists, not streams: nothing plays or opens a
 * stream connection. A refresh that came from the API already has them.
 */
class XtreamDetails(
    private val db: TunedDatabase,
    private val api: XtreamImport,
) {
    private val sql get() = db.openHelper.writableDatabase

    suspend fun films() {
        val ids = filmIds()
        if (ids.isEmpty()) return
        val batch = ArrayList<Pending>(CHUNK)
        api.filmDetails { film ->
            val id = ids[film.id] ?: return@filmDetails
            if (film.added == 0L && film.year == null && film.rating == null) return@filmDetails
            batch += Pending(id, film)
            if (batch.size >= CHUNK) flush(batch)
        }
        flush(batch)
    }

    suspend fun shows() {
        val details = ArrayList<ShowDetailsEntity>()
        api.shows(0) { show, _ -> details += ShowDetailsEntity(Episodes.showKey(show.name), show.added, show.year, show.rating) }
        if (details.isEmpty()) return
        db.shows().replaceDetails(details.distinctBy { it.showKey })
        ShowGroups.rebuild(db)
    }

    /** The cache's films by their Xtream stream id, the number their link ends in. */
    private suspend fun filmIds(): Map<String, Long> = withContext(Dispatchers.IO) {
        val ids = HashMap<String, Long>()
        sql.query("SELECT id, url FROM items WHERE kind = 1").use { c ->
            while (c.moveToNext()) CatchUp.streamId(c.getString(1))?.let { ids[it] = c.getLong(0) }
        }
        ids
    }

    private suspend fun flush(batch: MutableList<Pending>) {
        if (batch.isEmpty()) return
        db.withTransaction {
            // A time or rating the server leaves out keeps the one already known; a year only fills a missing one,
            // because the name's year is as good. Rows that wouldn't change aren't written at all.
            sql
                .compileStatement(
                    "UPDATE items SET added = CASE WHEN ?1 > 0 THEN ?1 ELSE added END, rating = COALESCE(?2, rating), " +
                        "year = COALESCE(year, ?3) WHERE id = ?4 AND ((?1 > 0 AND added != ?1) " +
                        "OR (?2 IS NOT NULL AND rating IS NOT ?2) OR (year IS NULL AND ?3 IS NOT NULL))",
                ).use { update ->
                    for (p in batch) {
                        update.bindLong(1, p.film.added)
                        val rating = p.film.rating
                        if (rating == null) update.bindNull(2) else update.bindDouble(2, rating)
                        val year = p.film.year
                        if (year == null) update.bindNull(3) else update.bindLong(3, year.toLong())
                        update.bindLong(4, p.id)
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
        }
        batch.clear()
    }

    private class Pending(
        val id: Long,
        val film: XtreamApi.Stream,
    )

    private companion object {
        const val CHUNK = 2_000
    }
}
