package com.tunedplayer.app.data.db

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
        ShowGroupEntity::class,
        TmdbSeasonEntity::class,
        ProfileEntity::class,
        CurrentProfileEntity::class,
        GuideFixEntity::class,
        GuideChannelEntity::class,
        ListEntity::class,
        ListItemEntity::class,
        ChannelEditEntity::class,
        ShowCoverEntity::class,
        CatchUpItemEntity::class,
        ShowDetailsEntity::class,
    ],
    version = 22,
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

    abstract fun shows(): ShowDao

    abstract fun profiles(): ProfileDao

    abstract fun guideFixes(): GuideFixDao

    abstract fun sync(): SyncDao

    abstract fun channelNumbers(): ChannelNumberDao

    abstract fun liveFilters(): LiveFilterDao

    abstract fun lists(): ListDao

    abstract fun channelEdits(): ChannelEditDao

    abstract fun titleSorts(): TitleSortDao

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
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
                MIGRATION_20_21,
                MIGRATION_21_22,
            ).addCallback(FirstProfile)
            // Going back to an older release (installing an older APK) can't keep a newer database: start it afresh
            // rather than crash on every launch. Upgrades always migrate, above.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

        /** A new install starts with one profile, in use. */
        private object FirstProfile : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = addFirstProfile(db)
        }

        internal fun addFirstProfile(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO `profiles` (`id`, `name`, `colour`, `kids`, `pinHash`, `createdAt`) " +
                    "VALUES (${Profiles.FIRST}, '', 0, 0, NULL, ${System.currentTimeMillis()})",
            )
            db.execSQL("INSERT OR IGNORE INTO `current_profile` (`slot`, `profileId`) VALUES (0, ${Profiles.FIRST})")
        }

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

        /** Adds the per-show table the Shows tab reads, filled from the cached playlist. */
        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `show_groups` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`showKey` TEXT NOT NULL, `groupName` TEXT NOT NULL, `region` TEXT, `adult` INTEGER NOT NULL, " +
                            "`showName` TEXT NOT NULL, `logo` TEXT, `episodes` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_show_groups_showKey` ON `show_groups` (`showKey`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_show_groups_sortOrder` ON `show_groups` (`sortOrder`)")
                    // The table as version 11 had it, from the items as version 10 had them: later columns and tables
                    // ([ShowGroups]) don't exist yet at this step. Later migrations and the next refresh fill the rest.
                    db.execSQL(
                        "INSERT INTO show_groups (showKey, groupName, region, adult, showName, logo, episodes, sortOrder) " +
                            "SELECT showKey, groupName, region, adult, COALESCE(MIN(showName), showKey), MAX(logo), COUNT(*), " +
                            "MIN(sortOrder) FROM items WHERE kind = 2 AND showKey IS NOT NULL GROUP BY showKey, groupName, region, adult",
                    )
                }
            }

        /** Adds TMDB's seasons, for episode screenshots and names. */
        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `tmdb_seasons` (`cacheKey` TEXT NOT NULL, `found` INTEGER NOT NULL, " +
                            "`episodes` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))",
                    )
                }
            }

        /** Adds guide fixes: a channel's own guide entry or time offset, and the guide's channel list to pick from. */
        val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `guide_fixes` (`itemId` INTEGER NOT NULL, `guideId` TEXT, " +
                            "`offsetMinutes` INTEGER NOT NULL, `playlistTvgId` TEXT, PRIMARY KEY(`itemId`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `guide_channels` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    )
                }
            }

        /** Adds channel numbers. They are filled in the background ([ChannelNumbers.fillIfMissing]) and by each refresh. */
        val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `items` ADD COLUMN `number` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_number` ON `items` (`kind`, `number`)")
                }
            }

        /** Adds plain M3U catch-up: the channel's link template beside its days. */
        val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `catchup` ADD COLUMN `source` TEXT")
                }
            }

        /** Adds profile avatars; every profile keeps its letter until one is picked. */
        val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `profiles` ADD COLUMN `avatar` TEXT")
                }
            }

        /** Adds the person's own lists beside Favourites ([ListEntity]); favourites stay where they are. */
        val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `lists` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, `profileId` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_lists_profileId_position` ON `lists` (`profileId`, `position`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `list_items` (`listId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`listId`, `itemId`))",
                    )
                }
            }

        /** Adds renamed channels and their own logos ([ChannelEditEntity]). */
        val MIGRATION_18_19 =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `channel_edits` (`itemId` INTEGER NOT NULL, `name` TEXT, `logo` TEXT, " +
                            "`originalName` TEXT NOT NULL, `originalLogo` TEXT, PRIMARY KEY(`itemId`))",
                    )
                }
            }

        /** Adds opened Xtream shows' own covers ([ShowCoverEntity]); the next refresh fills it in. */
        val MIGRATION_19_20 =
            object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `show_covers` (`showKey` TEXT NOT NULL, `cover` TEXT NOT NULL, PRIMARY KEY(`showKey`))",
                    )
                }
            }

        /**
         * Live TV's newer filters: what kind each programme is (On now), and the channels that keep recordings by item
         * (Catch-up), filled by the next refresh.
         */
        val MIGRATION_20_21 =
            object : Migration(20, 21) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `programmes` ADD COLUMN `genre` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `catchup_items` (`itemId` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
                }
            }

        /**
         * Adds what Movies and Shows sort on ([ItemEntity.added], year, rating and title) and the shows' own details
         * ([ShowDetailsEntity]). Titles and years are filled in the background from the names ([SortFields]); times
         * and ratings come with the next refresh, which a new version always reads in full.
         */
        val MIGRATION_21_22 =
            object : Migration(21, 22) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (table in listOf("items", "show_groups")) {
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `added` INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `year` INTEGER")
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `rating` REAL")
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `sortTitle` TEXT NOT NULL DEFAULT ''")
                    }
                    for (column in listOf("added", "year", "rating")) {
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_items_kind_${column}_sortOrder` " +
                                "ON `items` (`kind` ASC, `$column` DESC, `sortOrder` ASC)",
                        )
                    }
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_items_kind_sortTitle_sortOrder` ON `items` (`kind`, `sortTitle`, `sortOrder`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `show_details` (`showKey` TEXT NOT NULL, `added` INTEGER NOT NULL, " +
                            "`year` INTEGER, `rating` REAL, PRIMARY KEY(`showKey`))",
                    )
                }
            }

        private const val FTS_INSERT = "INSERT INTO `items_fts`(`docid`, `searchName`) VALUES (NEW.`rowid`, NEW.`searchName`); END"

        private val ftsTriggers =
            listOf(
                "BEFORE_UPDATE" to "BEFORE UPDATE ON `items` BEGIN DELETE FROM `items_fts` WHERE `docid`=OLD.`rowid`; END",
                "BEFORE_DELETE" to "BEFORE DELETE ON `items` BEGIN DELETE FROM `items_fts` WHERE `docid`=OLD.`rowid`; END",
                "AFTER_UPDATE" to "AFTER UPDATE ON `items` BEGIN $FTS_INSERT",
                "AFTER_INSERT" to "AFTER INSERT ON `items` BEGIN $FTS_INSERT",
            )

        /**
         * Adds profiles. Favourites, history, watch later and seen episodes gain a profile, and everything already
         * there goes to the first one, so nothing is lost.
         */
        val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                            "`colour` INTEGER NOT NULL, `kids` INTEGER NOT NULL, `pinHash` TEXT, `createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `current_profile` (`slot` INTEGER NOT NULL, `profileId` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`slot`))",
                    )
                    addFirstProfile(db)
                    rebuild(
                        db,
                        "favourites",
                        "`itemId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, " +
                            "`profileId` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`profileId`, `itemId`)",
                        "`itemId`, `addedAt`, `position`",
                    )
                    rebuild(
                        db,
                        "history",
                        "`itemId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                            "`watchedAt` INTEGER NOT NULL, `profileId` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`profileId`, `itemId`)",
                        "`itemId`, `positionMs`, `durationMs`, `watchedAt`",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_watchedAt` ON `history` (`watchedAt`)")
                    rebuild(
                        db,
                        "watch_later",
                        "`key` TEXT NOT NULL, `itemId` INTEGER, `showKey` TEXT, `title` TEXT NOT NULL, `image` TEXT, " +
                            "`addedAt` INTEGER NOT NULL, `profileId` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`profileId`, `key`)",
                        "`key`, `itemId`, `showKey`, `title`, `image`, `addedAt`",
                    )
                    rebuild(
                        db,
                        "seen_episodes",
                        "`itemId` INTEGER NOT NULL, `showKey` TEXT NOT NULL, `profileId` INTEGER NOT NULL DEFAULT 1, " +
                            "PRIMARY KEY(`profileId`, `itemId`)",
                        "`itemId`, `showKey`",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_seen_episodes_showKey` ON `seen_episodes` (`showKey`)")
                }
            }

        /** SQLite can't change a primary key in place: copy [table] into the new shape, then swap it in. */
        private fun rebuild(
            db: SupportSQLiteDatabase,
            table: String,
            columns: String,
            copied: String,
        ) {
            db.execSQL("CREATE TABLE `${table}_new` ($columns)")
            db.execSQL("INSERT INTO `${table}_new` ($copied, `profileId`) SELECT $copied, ${Profiles.FIRST} FROM `$table`")
            db.execSQL("DROP TABLE `$table`")
            db.execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
        }
    }
}
