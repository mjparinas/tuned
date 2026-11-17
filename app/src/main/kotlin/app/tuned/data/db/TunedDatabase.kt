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
        DownloadEntity::class,
        WatchLaterEntity::class,
        SeenEpisodeEntity::class,
        ChannelScanEntity::class,
        ItemFts::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class TunedDatabase : RoomDatabase() {
    abstract fun items(): ItemDao

    abstract fun programmes(): ProgrammeDao

    abstract fun library(): LibraryDao

    abstract fun catchUp(): CatchUpDao

    abstract fun metadata(): MetadataDao

    abstract fun downloads(): DownloadDao

    abstract fun watchLater(): WatchLaterDao

    abstract fun seenEpisodes(): SeenEpisodeDao

    abstract fun channelScan(): ChannelScanDao

    companion object {
        fun create(context: Context): TunedDatabase = Room
            .databaseBuilder(context, TunedDatabase::class.java, "tuned.db")
            // WAL: the lists keep reading the old playlist while a refresh writes the new one.
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            // Favourites, progress and channel health are the person's own, so every version change keeps them.
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            ).build()

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

        /** Adds offline downloads. */
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `downloads` (`itemId` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                            "`path` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `total` INTEGER NOT NULL, `state` INTEGER NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                    )
                }
            }

        /** Adds the watch later list. */
        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `watch_later` (`key` TEXT NOT NULL, `itemId` INTEGER, `showKey` TEXT, " +
                            "`title` TEXT NOT NULL, `image` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                    )
                }
            }

        /** Adds the episodes each opened show had, so later ones show as new. */
        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `seen_episodes` (`itemId` INTEGER NOT NULL, `showKey` TEXT NOT NULL, " +
                            "PRIMARY KEY(`itemId`))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_seen_episodes_showKey` ON `seen_episodes` (`showKey`)")
                }
            }

        /** Adds what channel scans found. */
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `channel_scan` (`itemId` INTEGER NOT NULL, `failures` INTEGER NOT NULL, " +
                            "`firstFailAt` INTEGER NOT NULL, `lastFailAt` INTEGER NOT NULL, `lastOkAt` INTEGER NOT NULL, " +
                            "`reason` TEXT, `kept` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                    )
                }
            }

        /**
         * Search's full-text index over names (the same table and triggers Room creates for [ItemFts]), filled from the
         * cached playlist, and an index on programme start times for "on now" guide searches.
         */
        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `items_fts` USING FTS4(`searchName` TEXT NOT NULL, content=`items`)")
                    for ((name, sql) in ftsTriggers) db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_items_fts_$name $sql")
                    db.execSQL("INSERT INTO `items_fts`(`items_fts`) VALUES('rebuild')")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_programmes_start` ON `programmes` (`start`)")
                }
            }

        private val ftsTriggers =
            listOf(
                "BEFORE_UPDATE" to "BEFORE UPDATE ON `items` BEGIN DELETE FROM `items_fts` WHERE `docid`=OLD.`rowid`; END",
                "BEFORE_DELETE" to "BEFORE DELETE ON `items` BEGIN DELETE FROM `items_fts` WHERE `docid`=OLD.`rowid`; END",
                "AFTER_UPDATE" to
                    "AFTER UPDATE ON `items` BEGIN INSERT INTO `items_fts`(`docid`, `searchName`) VALUES (NEW.`rowid`, NEW.`searchName`); END",
                "AFTER_INSERT" to
                    "AFTER INSERT ON `items` BEGIN INSERT INTO `items_fts`(`docid`, `searchName`) VALUES (NEW.`rowid`, NEW.`searchName`); END",
            )
    }
}
