package com.tunedplayer.app.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ChannelQuality
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.HiddenGroups
import com.tunedplayer.app.core.PinnedGroups
import com.tunedplayer.app.core.ProgrammeGenre
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.data.db.ChannelNumbers
import com.tunedplayer.app.data.db.ChannelWithNow
import com.tunedplayer.app.data.db.ContinueRow
import com.tunedplayer.app.data.db.GroupCount
import com.tunedplayer.app.data.db.GuideSearchIndex
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.ListEntity
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.data.db.RegionCount
import com.tunedplayer.app.data.db.ShowSummary
import com.tunedplayer.app.data.db.SortFields
import com.tunedplayer.app.data.db.sortedItems
import com.tunedplayer.app.data.db.sortedShows
import com.tunedplayer.app.data.db.sortedShowsTab
import com.tunedplayer.app.data.hiddenKeys
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.settings.PinActions
import com.tunedplayer.app.ui.settings.PinResult
import com.tunedplayer.app.ui.settings.matches
import com.tunedplayer.app.ui.settings.toPinResult
import com.tunedplayer.app.work.TvHome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a list shows: a category, favourites only, or everything. */
sealed interface Filter {
    data object All : Filter

    data object Favourites : Filter

    /** Live TV: the channels watched lately, most recent first. */
    data object Recent : Filter

    data class Group(
        val name: String,
    ) : Filter

    /** One of the person's own lists beside Favourites ([com.tunedplayer.app.data.OwnLists]). */
    data class OwnList(
        val id: Long,
    ) : Filter
}

private data class Query(
    val hidden: List<String>,
    val adult: Boolean,
    val filter: Filter,
    val search: String,
    val now: Long,
)

/** One per section. Lists are paged straight from Room, so 50,000 movies scroll as fast as 50. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowseViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    private val items = container.db.items()

    init {
        // The account's status (connections in use, expiry), so the player and Settings never wait for it.
        viewModelScope.launchSafely("BrowseViewModel") { container.account.refreshIfOld() }
        // Sports, PPV and event channels are never hidden as dead: bring back any hidden before that rule, or renamed since.
        viewModelScope.launchSafely("BrowseViewModel") { container.channelScan.releaseEventChannels() }
        // A cache from before channel numbers: number it once, in the background.
        viewModelScope.launchSafely("BrowseViewModel") { ChannelNumbers.fillIfMissing(container.db) }
        // A cache from before the Movies and Shows sorts: work out its titles and years once, in the background.
        viewModelScope.launchSafely("BrowseViewModel") { SortFields.fillIfMissing(container.db) }
        // A guide cached before whole-guide search: index its titles once, in the background.
        viewModelScope.launchSafely("BrowseViewModel") { GuideSearchIndex.fillIfMissing(container.db) }
        // The Android TV home screen rows (Continue watching, favourites); nothing on phones.
        TvHome.schedule(app)
    }

    private val filters = mutableMapOf<ContentKind, MutableStateFlow<Filter>>()
    private val searches = mutableMapOf<ContentKind, MutableStateFlow<String>>()
    private val groupFlows = mutableMapOf<ContentKind, StateFlow<List<GroupCount>>>()

    fun filter(kind: ContentKind): MutableStateFlow<Filter> = filters.getOrPut(kind) { MutableStateFlow(Filter.All) }

    fun search(kind: ContentKind): MutableStateFlow<String> = searches.getOrPut(kind) { MutableStateFlow("") }

    private val settings: StateFlow<AppSettings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The pinned groups, as [PinnedGroups] stores them: first in each tab's chip row. Declared before [channels], whose
     * Live TV chips read it while the ViewModel is built; declared after, it was still null then and Live TV crashed.
     */
    val pins: StateFlow<String> =
        settings
            .map { it?.pinnedGroups.orEmpty() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The profile in use, for the profile button. */
    val profile: StateFlow<ProfileEntity?> = container.profiles.current.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The guide's "now" moves on; re-read it every five minutes so "on now" stays true. */
    private val clock: Flow<Long> =
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(5 * 60_000L)
            }
        }

    private fun query(kind: ContentKind): Flow<Query> = combine(
        settings.map { it?.hiddenKeys() to (it?.showAdult ?: false) }.distinctUntilChanged(),
        filter(kind),
        search(kind).debounce(200).map { Search.normalize(it) }.distinctUntilChanged(),
        if (kind == ContentKind.LIVE) clock else flow { emit(0L) },
    ) { (hidden, adult), filter, search, now -> Query(hidden.orEmpty(), adult, filter, search, now) }

    private val config = PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = true, initialLoadSize = 120)

    /** Live TV's quick filters (Sports, PPV, a country or a language), on top of the chip chosen. */
    val liveQuick = MutableStateFlow(LiveQuick())

    init {
        // Locking adult categories again (in Settings, or a kids profile) takes the Adult filter off with them.
        viewModelScope.launchSafely("BrowseViewModel") {
            settings.collect { s ->
                if (s?.showAdult != true && liveQuick.value.topic?.isAdult == true) liveQuick.value = liveQuick.value.copy(topic = null)
            }
        }
    }

    /**
     * Whether the Adult quick filter shows, and whether it needs the parental PIN first. Never on a kids profile, and
     * only when the playlist has adult channels. Read only while the sheet is open.
     */
    val liveAdult: StateFlow<AdultGate> =
        combine(settings, container.settings.hasAdultPin, container.db.liveFilters().hasAdultChannels()) { s, hasPin, any ->
            when {
                s == null || s.kidsProfile || !any -> AdultGate.Hidden
                s.showAdult -> AdultGate.Unlocked
                hasPin -> AdultGate.Locked
                else -> AdultGate.NoPin
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdultGate.Hidden)

    /** The parental PIN, as Settings asks for it, so the Adult filter unlocks adult categories the same way. */
    val adultPin: PinActions =
        object : PinActions {
            override suspend fun setPin(pin: String) = container.settings.setAdultPin(pin)

            override suspend fun unlock(pin: String): PinResult {
                val throttle = container.profiles.throttle
                if (throttle.waiting()) return PinResult.TooMany
                return throttle.record(container.settings.unlockAdult(pin)).toPinResult()
            }

            override fun matchesLogin(secret: String): Boolean = container.credentials.matches(secret)
        }

    /**
     * Which of the quick filters by quality, programme on now, guide, catch-up and watching would find anything. Read
     * once each time the sheet opens, off the screen thread; a failure offers none of them rather than stopping the sheet.
     */
    suspend fun liveOffers(): LiveOffers = attempt("BrowseViewModel", "Couldn't read which filters apply") {
        val dao = container.db.liveFilters()
        val now = System.currentTimeMillis()
        LiveOffers(
            qualities = ChannelQuality.entries.filterTo(mutableSetOf()) { dao.hasNamed(it.fullText) },
            onNow = dao.genresOnNow(now).mapNotNullTo(mutableSetOf()) { ProgrammeGenre.fromCode(it) },
            guide = dao.hasGuide(now),
            catchUp = dao.hasCatchUp(),
            watched = dao.hasWatchedChannels(),
        )
    }.getOrNull() ?: LiveOffers()

    /** The countries the quick filters can offer: those among the live channels on show. Read only while the sheet is open. */
    val liveRegions: StateFlow<List<RegionCount>> =
        settings
            .map { it?.hiddenKeys().orEmpty() to (it?.showAdult ?: false) }
            .distinctUntilChanged()
            .flatMapLatest { (hidden, adult) -> container.db.liveFilters().liveRegions(hidden, adult) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: Flow<PagingData<ChannelWithNow>> =
        combine(
            query(ContentKind.LIVE),
            liveQuick,
            groups(ContentKind.LIVE).map { list -> list.map { it.groupName } }.distinctUntilChanged(),
        ) { q, quick, groups -> q to LiveQuickArgs(quick.topicQuery, quick.keys(groups), quick.qualityQuery, quick.genreCode) }
            // The categories only change the keys while Sports or PPV is chosen; otherwise the list isn't reloaded.
            .distinctUntilChanged()
            .flatMapLatest { (q, f) ->
                Pager(config) {
                    val group = (q.filter as? Filter.Group)?.name
                    val match = Search.match(q.search)
                    when (q.filter) {
                        Filter.Favourites -> {
                            items.favouriteChannels(q.now, q.hidden, q.adult, group, q.search, match, f.topic, f.keys, f.quality, f.genre)
                        }

                        is Filter.OwnList -> {
                            container.db.lists().listChannels(
                                q.filter.id,
                                q.now,
                                q.hidden,
                                q.adult,
                                group,
                                q.search,
                                match,
                                f.topic,
                                f.keys,
                                f.quality,
                                f.genre,
                            )
                        }

                        Filter.Recent -> {
                            items.recentChannels(q.now, q.hidden, q.adult, group, q.search, match, f.topic, f.keys, f.quality, f.genre)
                        }

                        else -> {
                            items.channels(q.now, q.hidden, q.adult, group, q.search, match, f.topic, f.keys, f.quality, f.genre)
                        }
                    }
                }.flow
            }.cachedIn(viewModelScope)

    /** The order each tab's titles are listed in (Movies and Shows; Live TV keeps the playlist's), remembered in Settings. */
    fun sort(kind: ContentKind): StateFlow<TitleSort.Order> = sortFlows.getOrPut(kind) {
        settings
            .map { s ->
                when (kind) {
                    ContentKind.MOVIE -> s?.movieSort
                    ContentKind.SERIES -> s?.showSort
                    ContentKind.LIVE -> null
                } ?: TitleSort.Order.PROVIDER
            }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, TitleSort.Order.PROVIDER)
    }

    private val sortFlows = mutableMapOf<ContentKind, StateFlow<TitleSort.Order>>()

    fun setSort(
        kind: ContentKind,
        order: TitleSort.Order,
    ) {
        viewModelScope.launchSafely("BrowseViewModel") {
            container.settings.update {
                when (kind) {
                    ContentKind.MOVIE -> it.copy(movieSort = order)
                    ContentKind.SERIES -> it.copy(showSort = order)
                    ContentKind.LIVE -> it
                }
            }
        }
    }

    val movies: Flow<PagingData<ItemEntity>> =
        combine(query(ContentKind.MOVIE), sort(ContentKind.MOVIE)) { q, order -> q to order }
            .flatMapLatest { (q, order) ->
                Pager(config) {
                    container.db.sortedItems(
                        order,
                        ContentKind.MOVIE.code,
                        q.hidden,
                        q.adult,
                        (q.filter as? Filter.Group)?.name,
                        q.filter == Filter.Favourites,
                        q.search,
                        Search.match(q.search),
                        (q.filter as? Filter.OwnList)?.id ?: 0,
                    )
                }.flow
            }.cachedIn(viewModelScope)

    val shows: Flow<PagingData<ShowSummary>> =
        combine(query(ContentKind.SERIES), sort(ContentKind.SERIES)) { q, order -> q to order }
            .flatMapLatest { (q, order) ->
                Pager(config) {
                    val group = (q.filter as? Filter.Group)?.name
                    // Browsing reads the per-show table; a search or Favourites narrows the episodes themselves.
                    if (q.search.isEmpty() && q.filter != Filter.Favourites) {
                        container.db.sortedShowsTab(order, q.hidden, q.adult, group)
                    } else {
                        container.db.sortedShows(
                            order,
                            q.hidden,
                            q.adult,
                            group,
                            q.filter == Filter.Favourites,
                            q.search,
                            Search.match(q.search),
                        )
                    }
                }.flow
            }.cachedIn(viewModelScope)

    private val continueFlows = mutableMapOf<ContentKind, StateFlow<List<ContinueRow>>>()

    /** Continue Watching for Movies (kind MOVIE) or Shows (episodes, kind SERIES). */
    fun continueWatching(kind: ContentKind): StateFlow<List<ContinueRow>> = continueFlows.getOrPut(kind) {
        settings
            .map { it?.hiddenKeys().orEmpty() to (it?.showAdult ?: false) }
            .distinctUntilChanged()
            .flatMapLatest { (hidden, adult) -> items.continueWatching(kind.code, hidden, adult) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    private val episodeNames = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val askedNames = mutableSetOf<Long>()

    /** TMDB's names for the episodes in Continue Watching ("Kamp Krusty"), by item id, as they become known. */
    val continueNames: StateFlow<Map<Long, String>> = episodeNames

    /** Looks up one episode's TMDB name: from the device when known, else one cached TMDB request per season. */
    fun loadEpisodeName(row: ContinueRow) {
        val show = row.showName ?: return
        val season = row.season ?: return
        val episode = row.episode ?: return
        if (!askedNames.add(row.id)) return
        viewModelScope.launchSafely("BrowseViewModel") {
            val info = container.metadata.cachedEpisode(show, season, episode) ?: container.metadata.episode(show, season, episode)
            info?.name?.let { name -> episodeNames.value += row.id to name }
        }
    }

    fun groups(kind: ContentKind): StateFlow<List<GroupCount>> = groupFlows.getOrPut(kind) {
        settings
            .map { it?.hiddenKeys().orEmpty() to (it?.showAdult ?: false) }
            .distinctUntilChanged()
            .flatMapLatest { (hidden, adult) ->
                if (kind ==
                    ContentKind.SERIES
                ) {
                    container.db.shows().showGroupChips(hidden, adult)
                } else {
                    items.groups(kind.code, hidden, adult)
                }
            }.combine(pins) { groups, pinned -> PinnedGroups.order(groups, pinned, kind.code) { it.groupName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** Pins a group to the front of its tab's chips, or unpins it (Melvin, 2026-11-19: "your own quick chips"). */
    fun togglePin(
        kind: ContentKind,
        group: String,
    ) {
        viewModelScope.launchSafely("BrowseViewModel") {
            container.settings.update { it.copy(pinnedGroups = PinnedGroups.toggle(it.pinnedGroups, kind.code, group)) }
        }
    }

    val refreshing = container.scheduler.refreshing.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What a running refresh is doing (bytes downloaded, rows saved), for the progress bar. */
    val progress: StateFlow<SyncProgress?> =
        container.scheduler.progress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Only refreshes the person started show above the lists; scheduled ones run unseen. */
    val requestedProgress: StateFlow<SyncProgress?> =
        container.scheduler.requestedProgress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val requestedRefreshing = container.scheduler.requestedRefreshing.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Nothing cached yet (first run, or the first download is still going). */
    val empty: StateFlow<Boolean?> =
        items
            .countFlow()
            .map { it == 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Shown changed the moment a heart is tapped ([com.tunedplayer.app.data.Picks]). */
    val favouriteIds: StateFlow<Set<Long>> = container.picks.favourites.value

    val lastError = settings.map { it?.lastSyncError.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun refresh() = container.scheduler.refreshNow()

    /** A first download is queued or running; null until known, which counts as yes so the spinner shows first. */
    val requestedPending: StateFlow<Boolean?> =
        container.scheduler.requestedPending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The first-run screen's Retry: starts over even when a stuck download is still queued. */
    fun retryFirst() {
        viewModelScope.launchSafely("BrowseViewModel") {
            container.settings.update { it.copy(lastSyncError = "") }
            container.scheduler.retryFirst()
        }
    }

    /** The poster badge's rating: from the device when seen before, otherwise one TMDB search (only with a key). */
    suspend fun rating(
        name: String,
        tv: Boolean,
    ): Double? = container.metadata.summary(name, tv)?.rating

    fun toggleFavourite(
        itemId: Long,
        favourite: Boolean,
    ) {
        container.picks.setFavourite(itemId, !favourite)
    }

    private val listFlows = mutableMapOf<ContentKind, StateFlow<List<ListEntity>>>()

    /** The person's own lists with something of [kind] in them, for the chips (Live TV and Movies). */
    fun lists(kind: ContentKind): StateFlow<List<ListEntity>> = listFlows.getOrPut(kind) {
        container.lists.withKind(kind).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    fun renameList(
        id: Long,
        name: String,
    ) {
        viewModelScope.launchSafely("BrowseViewModel") { container.lists.rename(id, name) }
    }

    /** Deletes a list; a tab showing it goes back to everything. */
    fun deleteList(id: Long) {
        filters.values.forEach { f -> if (f.value == Filter.OwnList(id)) f.value = Filter.All }
        viewModelScope.launchSafely("BrowseViewModel") { container.lists.delete(id) }
    }

    /** Hides a group from its section everywhere; Settings › Hidden groups brings it back. */
    fun hideGroup(
        kind: ContentKind,
        group: String,
    ) {
        filter(kind).value = Filter.All
        viewModelScope.launchSafely("BrowseViewModel") {
            container.settings.update { it.copy(hiddenGroups = it.hiddenGroups + HiddenGroups.key(kind.code, group)) }
        }
    }

    /** Undo for [hideGroup]: the group comes back and is chosen again, as it was. */
    fun unhideGroup(
        kind: ContentKind,
        group: String,
    ) {
        viewModelScope.launchSafely("BrowseViewModel") {
            container.settings.update { it.copy(hiddenGroups = it.hiddenGroups - HiddenGroups.key(kind.code, group)) }
            filter(kind).value = Filter.Group(group)
        }
    }

    /** Live TV as the grid guide or the list; remembered, and also in Settings. */
    fun setLiveGrid(on: Boolean) {
        viewModelScope.launchSafely("BrowseViewModel") { container.settings.update { it.copy(liveGrid = on) } }
    }

    fun rememberTab(index: Int) {
        viewModelScope.launchSafely("BrowseViewModel") { container.settings.update { it.copy(lastTab = index) } }
    }
}
