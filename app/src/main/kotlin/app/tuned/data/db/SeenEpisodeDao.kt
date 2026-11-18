package app.tuned.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SeenEpisodeDao {
    @Query("SELECT profileId FROM current_profile")
    suspend fun currentProfile(): Long?

    @Query("SELECT itemId FROM seen_episodes WHERE showKey = :showKey AND profileId = $ME")
    suspend fun seen(showKey: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<SeenEpisodeEntity>)

    @Transaction
    suspend fun add(rows: List<SeenEpisodeEntity>) {
        val me = currentProfile() ?: Profiles.FIRST
        insert(rows.map { it.copy(profileId = me) })
    }
}
