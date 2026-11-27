package com.tunedplayer.app.data.db

import androidx.paging.PagingSource
import com.tunedplayer.app.core.TitleSort

// Movies and Shows in the order chosen on their tab ([TitleSort.Order]). Each order is its own query, so the provider's
// order and every sort read down an index instead of sorting the whole list for each page.

@Suppress("LongParameterList") // the filters of [ItemDao.items]
fun TunedDatabase.sortedItems(
    order: TitleSort.Order,
    kind: Int,
    hidden: List<String>,
    adult: Boolean,
    group: String?,
    favouritesOnly: Boolean,
    query: String,
    match: String,
    listId: Long,
): PagingSource<Int, ItemEntity> {
    val by = titleSorts()
    return when (order) {
        TitleSort.Order.PROVIDER -> items().items(kind, hidden, adult, group, favouritesOnly, query, match, listId)
        TitleSort.Order.ADDED -> by.itemsByAdded(kind, hidden, adult, group, favouritesOnly, query, match, listId)
        TitleSort.Order.YEAR -> by.itemsByYear(kind, hidden, adult, group, favouritesOnly, query, match, listId)
        TitleSort.Order.RATING -> by.itemsByRating(kind, hidden, adult, group, favouritesOnly, query, match, listId)
        TitleSort.Order.TITLE -> by.itemsByTitle(kind, hidden, adult, group, favouritesOnly, query, match, listId)
    }
}

@Suppress("LongParameterList") // the filters of [ItemDao.shows]
fun TunedDatabase.sortedShows(
    order: TitleSort.Order,
    hidden: List<String>,
    adult: Boolean,
    group: String?,
    favouritesOnly: Boolean,
    query: String,
    match: String,
    /** The shows favourited whole ([com.tunedplayer.app.data.FavouriteShows]), with [favouritesOnly]. */
    favouriteShows: List<String> = emptyList(),
): PagingSource<Int, ShowSummary> {
    val by = titleSorts()
    return when (order) {
        TitleSort.Order.PROVIDER -> items().shows(hidden, adult, group, favouritesOnly, favouriteShows, query, match)
        TitleSort.Order.ADDED -> by.showsByAdded(hidden, adult, group, favouritesOnly, favouriteShows, query, match)
        TitleSort.Order.YEAR -> by.showsByYear(hidden, adult, group, favouritesOnly, favouriteShows, query, match)
        TitleSort.Order.RATING -> by.showsByRating(hidden, adult, group, favouritesOnly, favouriteShows, query, match)
        TitleSort.Order.TITLE -> by.showsByTitle(hidden, adult, group, favouritesOnly, favouriteShows, query, match)
    }
}

/** The Shows tab when nothing narrows it ([ShowDao.showsTab]), in [order]. */
fun TunedDatabase.sortedShowsTab(
    order: TitleSort.Order,
    hidden: List<String>,
    adult: Boolean,
    group: String?,
): PagingSource<Int, ShowSummary> {
    val shows = shows()
    return when (order) {
        TitleSort.Order.PROVIDER -> shows.showsTab(hidden, adult, group)
        TitleSort.Order.ADDED -> shows.showsTabByAdded(hidden, adult, group)
        TitleSort.Order.YEAR -> shows.showsTabByYear(hidden, adult, group)
        TitleSort.Order.RATING -> shows.showsTabByRating(hidden, adult, group)
        TitleSort.Order.TITLE -> shows.showsTabByTitle(hidden, adult, group)
    }
}
