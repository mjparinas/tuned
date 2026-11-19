package app.tuned.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Who watches: the profiles, and which one is in use (one row in `current_profile`). */
@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun allNow(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Query("SELECT p.* FROM profiles p JOIN current_profile c ON c.profileId = p.id")
    fun current(): Flow<ProfileEntity?>

    @Query("SELECT p.* FROM profiles p JOIN current_profile c ON c.profileId = p.id")
    suspend fun currentNow(): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Upsert
    suspend fun setCurrent(row: CurrentProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("DELETE FROM favourites WHERE profileId = :id")
    suspend fun deleteFavourites(id: Long)

    @Query("DELETE FROM history WHERE profileId = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM watch_later WHERE profileId = :id")
    suspend fun deleteWatchLater(id: Long)

    @Query("DELETE FROM seen_episodes WHERE profileId = :id")
    suspend fun deleteSeen(id: Long)

    @Query("DELETE FROM list_items WHERE listId IN (SELECT id FROM lists WHERE profileId = :id)")
    suspend fun deleteListItems(id: Long)

    @Query("DELETE FROM lists WHERE profileId = :id")
    suspend fun deleteLists(id: Long)

    /** Removes a profile and everything it kept. The caller has moved off it first. */
    @Transaction
    suspend fun delete(id: Long) {
        deleteFavourites(id)
        deleteHistory(id)
        deleteWatchLater(id)
        deleteSeen(id)
        deleteListItems(id)
        deleteLists(id)
        deleteRow(id)
    }
}
