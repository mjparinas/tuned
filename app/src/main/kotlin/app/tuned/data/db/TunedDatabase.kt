package app.tuned.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ItemEntity::class, ProgrammeEntity::class, FavouriteEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TunedDatabase : RoomDatabase() {
    abstract fun items(): ItemDao

    abstract fun programmes(): ProgrammeDao

    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): TunedDatabase = Room
            .databaseBuilder(context, TunedDatabase::class.java, "tuned.db")
            // WAL: the lists keep reading the old playlist while a refresh writes the new one.
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            // The playlist and guide are a cache of the server: rebuilding them is cheaper than a migration.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
