package com.tunedplayer.app.data.db

import com.tunedplayer.app.core.TitleSort

// The browse lists' SQL, shared by the DAOs' paged `@Query` methods (which Room checks when it compiles) and the
// lists [QuickPagingSource] shows first rows of before it has counted them. Each list is its rows without an order,
// and one ORDER BY per sort, so every sort of a list shares one count.

/** Live TV in playlist order ([ItemDao.channels]). */
internal const val LIVE_ALL = "SELECT $CHANNEL_COLUMNS, 0 AS favourite FROM items i $NOW_JOIN WHERE $CHANNEL_FILTER $LIVE_QUICK"
internal const val LIVE_ALL_ORDER = "ORDER BY i.sortOrder"

/** Favourite channels, in their own order ([ItemDao.favouriteChannels]). */
internal const val LIVE_FAVOURITES =
    "SELECT $CHANNEL_COLUMNS, 1 AS favourite FROM items i JOIN favourites f ON f.itemId = i.id AND f.profileId = $ME $NOW_JOIN " +
        "WHERE $CHANNEL_FILTER $LIVE_QUICK"
internal const val LIVE_FAVOURITES_ORDER = "ORDER BY f.position"

/** Channels watched lately ([ItemDao.recentChannels]). */
internal const val LIVE_RECENT =
    "SELECT $CHANNEL_COLUMNS, 0 AS favourite FROM items i JOIN history h ON h.itemId = i.id AND h.profileId = $ME $NOW_JOIN " +
        "WHERE $CHANNEL_FILTER $LIVE_QUICK"
internal const val LIVE_RECENT_ORDER = "ORDER BY h.watchedAt DESC"

/** One of the person's own lists of channels ([ListDao.listChannels]). */
internal const val LIVE_LIST =
    "SELECT $CHANNEL_COLUMNS, 0 AS favourite FROM items i JOIN list_items li ON li.itemId = i.id AND li.listId = :listId $NOW_JOIN " +
        "WHERE $CHANNEL_FILTER $LIVE_QUICK"
internal const val LIVE_LIST_ORDER = "ORDER BY li.position"

/**
 * Counting Live TV needs no columns and no programme on now ([NOW_JOIN] finds at most one per channel), unless the
 * list keeps a kind of programme: 149 ms down to 92 ms on 40,000 channels. Each list's own join stays.
 */
internal const val LIVE_COUNT_WHERE = "WHERE $CHANNEL_FILTER $LIVE_QUICK_ROWS"
internal const val LIVE_ALL_COUNT = "SELECT COUNT(*) FROM items i $LIVE_COUNT_WHERE"
internal const val LIVE_FAVOURITES_COUNT =
    "SELECT COUNT(*) FROM items i JOIN favourites f ON f.itemId = i.id AND f.profileId = $ME $LIVE_COUNT_WHERE"
internal const val LIVE_RECENT_COUNT =
    "SELECT COUNT(*) FROM items i JOIN history h ON h.itemId = i.id AND h.profileId = $ME $LIVE_COUNT_WHERE"
internal const val LIVE_LIST_COUNT =
    "SELECT COUNT(*) FROM items i JOIN list_items li ON li.itemId = i.id AND li.listId = :listId $LIVE_COUNT_WHERE"

/** Movies ([ItemDao.items] and [TitleSortDao]). */
internal const val TITLES = "SELECT * FROM items i $TITLE_FILTER"
internal const val TITLES_PROVIDER = "ORDER BY i.sortOrder"
internal const val TITLES_ADDED = "ORDER BY i.added DESC, i.sortOrder"
internal const val TITLES_YEAR = "ORDER BY i.year DESC, i.sortOrder"
internal const val TITLES_RATING = "ORDER BY i.topRating DESC, i.sortOrder"
internal const val TITLES_TITLE = "ORDER BY i.sortTitle, i.sortOrder"

/** Shows from their episodes, for a search or Favourites ([ItemDao.shows] and [TitleSortDao]). */
internal const val SHOWS = "$SHOW_SUMMARY $SHOW_FILTER"
internal const val SHOWS_PROVIDER = "ORDER BY MIN(sortOrder)"
internal const val SHOWS_ADDED = "ORDER BY $SHOW_ADDED DESC, MIN(sortOrder)"
internal const val SHOWS_YEAR = "ORDER BY $SHOW_YEAR DESC, MIN(sortOrder)"
internal const val SHOWS_RATING = "ORDER BY ($SHOW_RATING IS NULL), $SHOW_RATING DESC, MIN(sortOrder)"
internal const val SHOWS_TITLE = "ORDER BY MIN(NULLIF(i.sortTitle, '')), MIN(sortOrder)"

/** The Shows tab when nothing narrows it, from the per-show table ([ShowDao.showsTab]). */
internal const val SHOWS_TAB_PROVIDER = "ORDER BY MIN(s.sortOrder)"
internal const val SHOWS_TAB_ADDED = "ORDER BY MAX(s.added) DESC, MIN(s.sortOrder)"
internal const val SHOWS_TAB_YEAR = "ORDER BY MAX(s.year) DESC, MIN(s.sortOrder)"
internal const val SHOWS_TAB_RATING = "ORDER BY (MAX(s.rating) IS NULL), MAX(s.rating) DESC, MIN(s.sortOrder)"
internal const val SHOWS_TAB_TITLE = "ORDER BY MIN(NULLIF(s.sortTitle, '')), MIN(s.sortOrder)"
internal const val SHOWS_TAB_COUNT = "SELECT COUNT(DISTINCT s.showKey) FROM show_groups s $SHOWS_TAB_WHERE"

/** [SHOWS_TAB_FLAT]'s sorts: the same orders as above on one row per show, which the indexes on show_groups serve. */
internal const val SHOWS_FLAT_PROVIDER = "ORDER BY s.sortOrder"
internal const val SHOWS_FLAT_ADDED = "ORDER BY s.added DESC, s.sortOrder"
internal const val SHOWS_FLAT_YEAR = "ORDER BY s.year DESC, s.sortOrder"

// SQLite puts NULL last in a DESC order, and '' (sortTitle is never NULL) first, as the grouped sorts do; written
// plainly, the indexes serve them (20 ms to 0.3 ms for the first page of 15,000 shows).
internal const val SHOWS_FLAT_RATING = "ORDER BY s.rating DESC, s.sortOrder"
internal const val SHOWS_FLAT_TITLE = "ORDER BY s.sortTitle, s.sortOrder"
internal const val SHOWS_FLAT_COUNT = "SELECT COUNT(*) FROM show_groups s $SHOWS_TAB_WHERE"

/** Which list of Live TV: everything, Favourites, Recent, or one of the person's own lists. */
sealed interface LiveSource {
    data object All : LiveSource

    data object Favourites : LiveSource

    data object Recent : LiveSource

    data class OwnList(
        val id: Long,
    ) : LiveSource
}

/**
 * The tables Live TV reads (what's on now, the quick filters), so a change to one shows on screen. Favourites and
 * own lists only where the list reads them, so a heart tapped on Live TV never starts the list over.
 */
private val LIVE_TABLES = arrayOf("items", "programmes", "channel_scan", "catchup_items", "history", "current_profile")

/** The tables Movies reads. */
private val TITLE_TABLES = arrayOf("items", "channel_scan", "favourites", "list_items", "current_profile")

/** The tables Shows reads, from the episodes or the per-show table. */
private val SHOW_TABLES = arrayOf("items", "channel_scan", "favourites", "current_profile", "show_groups", "seen_episodes")

/** Every table a browse list reads ([ListCounts] watches them all). */
internal val QUICK_TABLES = (LIVE_TABLES + TITLE_TABLES + SHOW_TABLES).distinct().toTypedArray()

/** The browse lists for [QuickPagingSource], each with the filters its DAO method takes. */
@Suppress("LongParameterList") // Every Live TV filter is a parameter of the one query, so the list stays one read.
object QuickLists {
    fun channels(
        source: LiveSource,
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
        topic: String,
        quick: List<String>,
        quality: String,
        genre: Int,
    ): QuickQuery<ChannelWithNow> {
        val (sql, order, count) =
            when (source) {
                LiveSource.All -> Triple(LIVE_ALL, LIVE_ALL_ORDER, LIVE_ALL_COUNT)
                LiveSource.Favourites -> Triple(LIVE_FAVOURITES, LIVE_FAVOURITES_ORDER, LIVE_FAVOURITES_COUNT)
                LiveSource.Recent -> Triple(LIVE_RECENT, LIVE_RECENT_ORDER, LIVE_RECENT_COUNT)
                is LiveSource.OwnList -> Triple(LIVE_LIST, LIVE_LIST_ORDER, LIVE_LIST_COUNT)
            }
        val tables =
            when (source) {
                LiveSource.Favourites -> LIVE_TABLES + "favourites"
                is LiveSource.OwnList -> LIVE_TABLES + "list_items"
                else -> LIVE_TABLES
            }
        val args =
            mapOf(
                "listId" to ((source as? LiveSource.OwnList)?.id ?: 0L),
                "now" to now,
                "hidden" to hidden,
                "adult" to adult,
                "group" to group,
                "query" to query,
                "match" to match,
                "topic" to topic,
                "quick" to quick,
                "quality" to quality,
                "genre" to genre,
            )
        return QuickQuery(
            name = "live:$source",
            sql = sql,
            orderBy = order,
            args = args,
            tables = tables,
            clock = "now",
            // A kind of programme on now is read through the guide's join, so only then does the count need it.
            countSql = if (genre == 0) count else null,
        ) { dao, q -> dao.channels(q) }
    }

    fun titles(
        order: TitleSort.Order,
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
        listId: Long,
    ): QuickQuery<ItemEntity> {
        val orderBy =
            when (order) {
                TitleSort.Order.PROVIDER -> TITLES_PROVIDER
                TitleSort.Order.ADDED -> TITLES_ADDED
                TitleSort.Order.YEAR -> TITLES_YEAR
                TitleSort.Order.RATING -> TITLES_RATING
                TitleSort.Order.TITLE -> TITLES_TITLE
            }
        val args =
            mapOf(
                "kind" to kind,
                "hidden" to hidden,
                "adult" to adult,
                "group" to group,
                "favouritesOnly" to favouritesOnly,
                "listId" to listId,
                "query" to query,
                "match" to match,
            )
        return QuickQuery(name = "titles", sql = TITLES, orderBy = orderBy, args = args, tables = TITLE_TABLES) { dao, q -> dao.items(q) }
    }

    fun shows(
        order: TitleSort.Order,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
        favouriteShows: List<String>,
    ): QuickQuery<ShowSummary> {
        val orderBy =
            when (order) {
                TitleSort.Order.PROVIDER -> SHOWS_PROVIDER
                TitleSort.Order.ADDED -> SHOWS_ADDED
                TitleSort.Order.YEAR -> SHOWS_YEAR
                TitleSort.Order.RATING -> SHOWS_RATING
                TitleSort.Order.TITLE -> SHOWS_TITLE
            }
        val args =
            mapOf(
                "hidden" to hidden,
                "adult" to adult,
                "group" to group,
                "favouritesOnly" to favouritesOnly,
                "favouriteShows" to favouriteShows,
                "query" to query,
                "match" to match,
            )
        return QuickQuery(name = "shows", sql = SHOWS, orderBy = orderBy, args = args, tables = SHOW_TABLES) { dao, q -> dao.shows(q) }
    }

    /** [oneRowEach]: every show has one row in show_groups ([ShowDao.oneRowEach]), so the list needs no grouping. */
    fun showsTab(
        order: TitleSort.Order,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        oneRowEach: Boolean = false,
    ): QuickQuery<ShowSummary> {
        val orderBy =
            if (oneRowEach) {
                when (order) {
                    TitleSort.Order.PROVIDER -> SHOWS_FLAT_PROVIDER
                    TitleSort.Order.ADDED -> SHOWS_FLAT_ADDED
                    TitleSort.Order.YEAR -> SHOWS_FLAT_YEAR
                    TitleSort.Order.RATING -> SHOWS_FLAT_RATING
                    TitleSort.Order.TITLE -> SHOWS_FLAT_TITLE
                }
            } else {
                when (order) {
                    TitleSort.Order.PROVIDER -> SHOWS_TAB_PROVIDER
                    TitleSort.Order.ADDED -> SHOWS_TAB_ADDED
                    TitleSort.Order.YEAR -> SHOWS_TAB_YEAR
                    TitleSort.Order.RATING -> SHOWS_TAB_RATING
                    TitleSort.Order.TITLE -> SHOWS_TAB_TITLE
                }
            }
        val args = mapOf("hidden" to hidden, "adult" to adult, "group" to group)
        return QuickQuery(
            // Both forms are the same list, so they share a count.
            name = "showsTab",
            sql = if (oneRowEach) SHOWS_TAB_FLAT else SHOWS_TAB,
            orderBy = orderBy,
            args = args,
            tables = SHOW_TABLES,
            countSql = if (oneRowEach) SHOWS_FLAT_COUNT else SHOWS_TAB_COUNT,
        ) { dao, q -> dao.shows(q) }
    }
}
