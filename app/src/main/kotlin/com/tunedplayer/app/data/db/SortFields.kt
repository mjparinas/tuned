package com.tunedplayer.app.data.db

import androidx.room.withTransaction
import com.tunedplayer.app.core.TitleSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The title and year Movies and Shows sort on ([ItemEntity.sortTitle], [ItemEntity.year]), worked out from the names.
 * Each refresh sets them; a cache from before the sorts gets them here once, in the background, a chunk per
 * transaction so the lists never wait long. Times added and ratings come from the provider with the next refresh.
 */
object SortFields {
    suspend fun fillIfMissing(db: TunedDatabase) = offMain {
        val sql = db.openHelper.writableDatabase
        val missing =
            sql.query("SELECT id, COALESCE(showName, name), year FROM items WHERE kind != 0 AND sortTitle = ''").use { c ->
                val rows = ArrayList<Row>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) rows += Row(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getInt(2))
                rows
            }
        if (missing.isEmpty()) return@offMain
        missing.chunked(CHUNK).forEach { chunk ->
            val filled =
                withContext(Dispatchers.Default) {
                    chunk.map { Filled(it.id, TitleSort.title(it.name), it.year ?: TitleSort.yearInName(it.name)) }
                }
            db.withTransaction {
                sql.compileStatement("UPDATE items SET sortTitle = ?, year = ? WHERE id = ?").use { update ->
                    for (f in filled) {
                        update.bindString(1, f.title)
                        if (f.year == null) update.bindNull(2) else update.bindLong(2, f.year.toLong())
                        update.bindLong(3, f.id)
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
            }
        }
        ShowGroups.rebuild(db)
    }

    private class Row(
        val id: Long,
        val name: String,
        val year: Int?,
    )

    private class Filled(
        val id: Long,
        val title: String,
        val year: Int?,
    )

    private const val CHUNK = 2_000
}
