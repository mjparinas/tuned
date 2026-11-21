package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.flow.Flow

/**
 * A live channel the person renamed or gave another logo (Melvin, 2026-11-19: "do the rename if people want it").
 * [name] and [logo] are theirs (null keeps the provider's); [originalName] and [originalLogo] are the provider's,
 * kept so "Use the original" can put them back and backup copies still match by the real name. For the whole
 * playlist rather than one profile, since a channel's name is the same whoever watches. Keyed by stable id, so a
 * refresh keeps it.
 */
@Entity(tableName = "channel_edits")
data class ChannelEditEntity(
    @PrimaryKey val itemId: Long,
    val name: String?,
    val logo: String?,
    val originalName: String,
    val originalLogo: String?,
) {
    /** What search reads: the person's name and the provider's, so either finds the channel. */
    val searchName: String get() = Search.normalize(if (name != null) "$name $originalName" else originalName)
}

@Dao
interface ChannelEditDao {
    @Query("SELECT * FROM channel_edits")
    suspend fun all(): List<ChannelEditEntity>

    @Query("SELECT * FROM channel_edits WHERE itemId = :itemId")
    suspend fun get(itemId: Long): ChannelEditEntity?

    @Query("SELECT * FROM channel_edits WHERE itemId = :itemId")
    fun watch(itemId: Long): Flow<ChannelEditEntity?>

    @Upsert
    suspend fun upsert(edit: ChannelEditEntity)

    @Query("DELETE FROM channel_edits WHERE itemId = :itemId")
    suspend fun delete(itemId: Long)

    /** Shows a name and logo in every list, the guide, search and the player at once. */
    @Query("UPDATE items SET name = :name, logo = :logo, searchName = :searchName WHERE id = :itemId")
    suspend fun show(
        itemId: Long,
        name: String,
        logo: String?,
        searchName: String,
    )
}

/** Lays the person's renames and logos over a fresh copy of the playlist as each refresh saves it ([PlaylistWriter]). */
object ChannelEdits {
    /**
     * Applies every edit to [table], which holds streams exactly as the provider sent them, first noting the
     * provider's current name and logo as the originals (a provider can rename a channel too).
     */
    fun applyTo(
        sql: SupportSQLiteDatabase,
        table: String,
    ) {
        val edits =
            sql.query("SELECT itemId, name, logo FROM channel_edits").use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getStringOrNull(1), c.getStringOrNull(2))) }
            }
        for ((id, name, logo) in edits) {
            val fresh =
                sql.query("SELECT name, logo FROM $table WHERE id = ?", arrayOf<Any>(id)).use { c ->
                    if (c.moveToFirst()) c.getString(0) to c.getStringOrNull(1) else null
                } ?: continue
            val edit = ChannelEditEntity(id, name, logo, fresh.first, fresh.second)
            sql.execSQL(
                "UPDATE channel_edits SET originalName = ?, originalLogo = ? WHERE itemId = ?",
                arrayOf<Any?>(edit.originalName, edit.originalLogo, id),
            )
            sql.execSQL(
                "UPDATE $table SET name = ?, logo = ?, searchName = ? WHERE id = ?",
                arrayOf<Any?>(name ?: edit.originalName, logo ?: edit.originalLogo, edit.searchName, id),
            )
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
