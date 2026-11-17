package app.tuned.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ItemEntity::class,
        ProgrammeEntity::class,
        FavouriteEntity::class,
        HistoryEntity::class,
        ChannelHealthEntity::class,
        CatchUpEntity::class,
        MetadataEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class TunedDatabase : RoomDatabase() {
    abstract fun items(): ItemDao

    abstract fun programmes(): ProgrammeDao

    abstract fun library(): LibraryDao

    abstract fun catchUp(): CatchUpDao

    abstract fun metadata(): MetadataDao

    companion object {
        fun create(context: Context): TunedDatabase = Room
            .databaseBuilder(context, TunedDatabase::class.java, "tuned.db")
            // WAL: the lists keep reading the old playlist while a refresh writes the new one.
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            // Favourites, progress and channel health are the person's own, so every version change keeps them.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

        /** Adds auto-tune's channel health. */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `channel_health` (`itemId` INTEGER NOT NULL, `okAt` INTEGER NOT NULL, " +
                            "`deadAt` INTEGER NOT NULL, `height` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                    )
                }
            }

        /** Lets favourites be put in any order; existing ones keep the order they were added in. */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `favourites` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE `favourites` SET `position` = `addedAt`")
                }
            }

        /** Adds catch-up: which live channels keep recordings, and for how many days. */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `catchup` (`streamId` TEXT NOT NULL, `days` INTEGER NOT NULL, PRIMARY KEY(`streamId`))",
                    )
                }
            }

        /** Adds the TMDB cache, so details and ratings load from the device. */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `metadata` (`cacheKey` TEXT NOT NULL, `found` INTEGER NOT NULL, " +
                            "`full` INTEGER NOT NULL, `tmdbId` INTEGER NOT NULL, `title` TEXT NOT NULL, `year` TEXT, " +
                            "`overview` TEXT, `rating` REAL, `poster` TEXT, `backdrop` TEXT, `genres` TEXT NOT NULL, " +
                            "`runtime` TEXT, `cast` TEXT NOT NULL, `trailer` TEXT, `fetchedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`cacheKey`))",
                    )
                }
            }
    }
}
