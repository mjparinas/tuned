package app.tuned.data.db

import androidx.room.withTransaction

/**
 * Live channels' numbers ([ItemEntity.number]): 1 up in playlist order, given by each playlist refresh. A cache from
 * before numbers existed gets them here once, in the background, a chunk per transaction so the lists never wait long.
 */
object ChannelNumbers {
    suspend fun fillIfMissing(db: TunedDatabase) {
        if (!db.items().hasUnnumbered()) return
        val ids = db.items().liveIdsInOrder()
        val sql = db.openHelper.writableDatabase
        ids.withIndex().chunked(CHUNK).forEach { chunk ->
            db.withTransaction {
                sql.compileStatement("UPDATE items SET number = ? WHERE id = ?").use { update ->
                    for ((index, id) in chunk) {
                        update.bindLong(1, index + 1L)
                        update.bindLong(2, id)
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
            }
        }
    }

    private const val CHUNK = 2_000
}
