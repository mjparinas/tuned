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
        val (sql, order) =
            when (source) {
                LiveSource.All -> LIVE_ALL to LIVE_ALL_ORDER
                LiveSource.Favourites -> LIVE_FAVOURITES to LIVE_FAVOURITES_ORDER
                LiveSource.Recent -> LIVE_RECENT to LIVE_RECENT_ORDER
                is LiveSource.OwnList -> LIVE_LIST to LIVE_LIST_ORDER
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

    fun showsTab(
        order: TitleSort.Order,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
    ): QuickQuery<ShowSummary> {
        val orderBy =
            when (order) {
                TitleSort.Order.PROVIDER -> SHOWS_TAB_PROVIDER
                TitleSort.Order.ADDED -> SHOWS_TAB_ADDED
                TitleSort.Order.YEAR -> SHOWS_TAB_YEAR
                TitleSort.Order.RATING -> SHOWS_TAB_RATING
                TitleSort.Order.TITLE -> SHOWS_TAB_TITLE
            }
        val args = mapOf("hidden" to hidden, "adult" to adult, "group" to group)
        return QuickQuery(
            name = "showsTab",
            sql = SHOWS_TAB,
            orderBy = orderBy,
            args = args,
            tables = SHOW_TABLES,
        ) { dao, q -> dao.shows(q) }
    }
}
