package com.tunedplayer.app.data.db

import androidx.paging.PagingSource
import com.tunedplayer.app.core.TitleSort

// Movies and Shows in the order chosen on their tab ([TitleSort.Order]). Each order is its own query, so the provider's
// order and every sort read down an index instead of sorting the whole list for each page.

@Suppress("LongParameterList") // the filters of [ItemDao.items]
fun ItemDao.items(
    order: TitleSort.Order,
    kind: Int,
    hidden: List<String>,
    adult: Boolean,
    group: String?,
    favouritesOnly: Boolean,
    query: String,
    match: String,
    listId: Long,
): PagingSource<Int, ItemEntity> = when (order) {
    TitleSort.Order.PROVIDER -> items(kind, hidden, adult, group, favouritesOnly, query, match, listId)
    TitleSort.Order.ADDED -> itemsByAdded(kind, hidden, adult, group, favouritesOnly, query, match, listId)
    TitleSort.Order.YEAR -> itemsByYear(kind, hidden, adult, group, favouritesOnly, query, match, listId)
    TitleSort.Order.RATING -> itemsByRating(kind, hidden, adult, group, favouritesOnly, query, match, listId)
    TitleSort.Order.TITLE -> itemsByTitle(kind, hidden, adult, group, favouritesOnly, query, match, listId)
}

@Suppress("LongParameterList") // the filters of [ItemDao.shows]
fun ItemDao.shows(
    order: TitleSort.Order,
    hidden: List<String>,
    adult: Boolean,
    group: String?,
    favouritesOnly: Boolean,
    query: String,
    match: String,
): PagingSource<Int, ShowSummary> = when (order) {
    TitleSort.Order.PROVIDER -> shows(hidden, adult, group, favouritesOnly, query, match)
    TitleSort.Order.ADDED -> showsByAdded(hidden, adult, group, favouritesOnly, query, match)
    TitleSort.Order.YEAR -> showsByYear(hidden, adult, group, favouritesOnly, query, match)
    TitleSort.Order.RATING -> showsByRating(hidden, adult, group, favouritesOnly, query, match)
    TitleSort.Order.TITLE -> showsByTitle(hidden, adult, group, favouritesOnly, query, match)
}

fun ShowDao.showsTab(
    order: TitleSort.Order,
    hidden: List<String>,
    adult: Boolean,
    group: String?,
): PagingSource<Int, ShowSummary> = when (order) {
    TitleSort.Order.PROVIDER -> showsTab(hidden, adult, group)
    TitleSort.Order.ADDED -> showsTabByAdded(hidden, adult, group)
    TitleSort.Order.YEAR -> showsTabByYear(hidden, adult, group)
    TitleSort.Order.RATING -> showsTabByRating(hidden, adult, group)
    TitleSort.Order.TITLE -> showsTabByTitle(hidden, adult, group)
}
