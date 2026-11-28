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
import com.tunedplayer.app.core.KidsContent
import com.tunedplayer.app.core.PinnedGroups
import com.tunedplayer.app.core.ProgrammeGenre
import com.tunedplayer.app.core.Regions
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.FavouriteShows
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.data.TitleRatings
import com.tunedplayer.app.data.WatchLaterFold
import com.tunedplayer.app.data.db.ChannelNumbers
import com.tunedplayer.app.data.db.ChannelWithNow
import com.tunedplayer.app.data.db.ContinueRow
import com.tunedplayer.app.data.db.GroupCount
import com.tunedplayer.app.data.db.GuideSearchIndex
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.ListCounts
import com.tunedplayer.app.data.db.ListEntity
import com.tunedplayer.app.data.db.LiveSource
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.data.db.QuickLists
import com.tunedplayer.app.data.db.QuickPagingSource
import com.tunedplayer.app.data.db.RegionCount
import com.tunedplayer.app.data.db.ShowSummary
import com.tunedplayer.app.data.db.SortFields
import com.tunedplayer.app.data.hiddenCountries
import com.tunedplayer.app.data.hiddenKeys
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.settings.PinActions
import com.tunedplayer.app.ui.settings.PinResult
import com.tunedplayer.app.ui.settings.matches
import com.tunedplayer.app.ui.settings.toPinResult
import com.tunedplayer.app.work.TvHome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** What the Shows tab reads: the filters, the sort, the shows favourited whole, and whether to skip grouping. */
private data class ShowsQuery(
    val q: Query,
    val order: TitleSort.Order,
    val favs: List<String>,
    val flat: Boolean,
)

/** The browse lists' last lengths ([ListCounts]), in the cache folder. */
private const val LIST_COUNTS_FILE = "list-counts"

/** What [BrowseViewModel.liveRegions] reads from Settings, so the Flow only restarts when one of them changes. */
private data class LiveRegionsQuery(
    val visible: List<String>,
    val groups: List<String>,
    val kids: List<String>,
    val keys: List<String>,
    val adult: Boolean,
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
        // Catching up an old cache, once, in the background, after the first screen is up (Quiet start). Each helper
        // runs its SQL off the screen thread itself (data/db/OffMain.kt).
        viewModelScope.launchSafely("BrowseViewModel") {
            container.quietStart.await()
            // Watch later saved before it became part of Favourites (2026-11-27): move it there.
            attempt("BrowseViewModel", "Watch later into Favourites failed") { WatchLaterFold.fold(container.db) }
            // A cache from before channel numbers: number it.
            ChannelNumbers.fillIfMissing(container.db)
            // A cache from before the Movies and Shows sorts: work out its titles and years.
            SortFields.fillIfMissing(container.db)
            // A sort chosen before its times and ratings were read: read them now rather than at the next refresh.
            val s = container.settings.settings.first()
            if (s.movieSort != TitleSort.Order.PROVIDER || s.showSort != TitleSort.Order.PROVIDER) container.sortDetails.fillIfMissing()
        }
        // A guide cached before whole-guide search: index its titles.
        viewModelScope.launchSafely("BrowseViewModel") {
            container.quietStart.await()
            GuideSearchIndex.fillIfMissing(container.db)
        }
        // The Android TV home screen rows (Continue watching, favourites); nothing on phones.
        TvHome.schedule(app)
    }

    private val filters = mutableMapOf<ContentKind, MutableStateFlow<Filter>>()

    /**
     * The profile's saved filters are in place ([SavedFilters]). The lists wait for them, briefly, so they don't load
     * everything and then load again; declared before the lists, which read it while the ViewModel is built.
     */
    private val restored = MutableStateFlow(false)

    /** Whether the saved filters are in place yet; for tests. */
    internal val filtersRestored: StateFlow<Boolean> get() = restored
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

    private fun query(kind: ContentKind): Flow<Query> = flow {
        withTimeoutOrNull(RESTORE_WAIT_MS) { restored.first { it } }
        emitAll(
            combine(
                settings.map { it?.hiddenKeys() to (it?.showAdult ?: false) }.distinctUntilChanged(),
                filter(kind),
                search(kind).debounce(200).map { Search.normalize(it) }.distinctUntilChanged(),
                if (kind == ContentKind.LIVE) clock else flow { emit(0L) },
            ) { (hidden, adult), filter, search, now -> Query(hidden.orEmpty(), adult, filter, search, now) },
        )
    }

    private val config = PagingConfig(pageSize = 60, prefetchDistance = 60, enablePlaceholders = true, initialLoadSize = 120)

    /**
     * How long each list is, counted in the background so the first rows never wait on it ([QuickPagingSource]). The
     * last lengths are kept in the cache folder, which signing out empties (data/AppReset).
     */
    private val counts = ListCounts(container.db, viewModelScope, app.cacheDir.resolve(LIST_COUNTS_FILE))

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
     * Which of the quick filters by quality, programme on now, guide, catch-up and watching would find anything. Kept
     * warm while Live TV is on screen (the Filters chip collects it), so the sheet does not wait on the database; the
     * probes run side by side. A failure offers none of them rather than stopping the sheet. Re-reads with the guide's
     * "now" clock so On now and Has guide stay true.
     */
    val liveOffers: StateFlow<LiveOffers> =
        clock
            .mapLatest { now -> readLiveOffers(now) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveOffers())

    private suspend fun readLiveOffers(now: Long): LiveOffers = attempt("BrowseViewModel", "Couldn't read which filters apply") {
        val dao = container.db.liveFilters()
        coroutineScope {
            val qualityHits =
                ChannelQuality.entries.map { q ->
                    async { if (dao.hasNamed(q.fullText)) q else null }
                }
            val onNow = async { dao.genresOnNow(now).mapNotNullTo(mutableSetOf()) { ProgrammeGenre.fromCode(it) } }
            val guide = async { dao.hasGuide(now) }
            val catchUp = async { dao.hasCatchUp() }
            val watched = async { dao.hasWatchedChannels() }
            LiveOffers(
                qualities = qualityHits.mapNotNull { it.await() }.toSet(),
                onNow = onNow.await(),
                guide = guide.await(),
                catchUp = catchUp.await(),
                watched = watched.await(),
            )
        }
    }.getOrNull() ?: LiveOffers()

    /**
     * The countries the quick filters can offer: those among the live channels on show. Kept warm while Live TV is on
     * screen. Looks up only the countries still on in Settings (US and Canada, say) through the region index, so a big
     * playlist with most countries hidden does not take tens of seconds.
     */
    val liveRegions: StateFlow<List<RegionCount>> =
        settings
            .map { s ->
                val hidden = s?.hiddenCountries().orEmpty()
                val visible = (s?.seenRegions.orEmpty() - hidden).filter { it != Regions.NONE }.sorted()
                val groups =
                    s
                        ?.hiddenGroups
                        .orEmpty()
                        .sorted()
                        .toList()
                val kids = listOfNotNull(KidsContent.HIDDEN_KEY.takeIf { s?.kidsProfile == true })
                val keys = s?.hiddenKeys().orEmpty()
                LiveRegionsQuery(visible, groups, kids, keys, s?.showAdult ?: false)
            }.distinctUntilChanged()
            .flatMapLatest { q ->
                val dao = container.db.liveFilters()
                if (q.visible.isNotEmpty()) {
                    dao.liveRegions(q.visible, q.groups.ifEmpty { listOf("") }, q.kids, q.adult)
                } else {
                    dao.liveRegionsFallback(q.keys, q.adult)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: Flow<PagingData<ChannelWithNow>> =
        combine(
            query(ContentKind.LIVE),
            liveQuick,
            groups(ContentKind.LIVE).map { list -> list.map { it.groupName } }.distinctUntilChanged(),
        ) { q, quick, groups -> q to LiveQuickArgs(quick.topicQuery, quick.keys(groups), quick.qualityQuery, quick.genreCode) }
            // The categories only change the keys while Sports or PPV is chosen; otherwise the list isn't reloaded.
            .distinctUntilChanged()
            .flatMapLatest { (q, f) ->
                val source =
                    when (q.filter) {
                        Filter.Favourites -> LiveSource.Favourites
                        is Filter.OwnList -> LiveSource.OwnList(q.filter.id)
                        Filter.Recent -> LiveSource.Recent
                        else -> LiveSource.All
                    }
                val list =
                    QuickLists.channels(
                        source,
                        q.now,
                        q.hidden,
                        q.adult,
                        (q.filter as? Filter.Group)?.name,
                        q.search,
                        Search.match(q.search),
                        f.topic,
                        f.keys,
                        f.quality,
                        f.genre,
                    )
                Pager(config) { QuickPagingSource(container.db, counts, list) }.flow
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
            if (order != TitleSort.Order.PROVIDER) container.sortDetails.fillIfMissing()
        }
    }

    /** Ratings being looked up for Top rated ([TitleRatings]), for the line above the grid; null when none are. */
    val ratingProgress: StateFlow<TitleRatings.Progress?> get() = container.titleRatings.progress

    init {
        // Top rated sorts on the rating each poster shows, TMDB's: while it's chosen, the titles TMDB hasn't been asked
        // about yet are looked up in the background, after the first screen is up, and the list re-sorts as they land.
        viewModelScope.launchSafely("BrowseViewModel") {
            container.quietStart.await()
            combine(sort(ContentKind.MOVIE), sort(ContentKind.SERIES)) { films, shows ->
                (films == TitleSort.Order.RATING) to (shows == TitleSort.Order.RATING)
            }.distinctUntilChanged()
                .collectLatest { (films, shows) ->
                    if (films) attempt("BrowseViewModel", "Film ratings failed") { container.titleRatings.fill(tv = false) }
                    if (shows) attempt("BrowseViewModel", "Show ratings failed") { container.titleRatings.fill(tv = true) }
                }
        }
    }

    val movies: Flow<PagingData<ItemEntity>> =
        combine(query(ContentKind.MOVIE), sort(ContentKind.MOVIE)) { q, order -> q to order }
            .flatMapLatest { (q, order) ->
                val list =
                    QuickLists.titles(
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
                Pager(config) { QuickPagingSource(container.db, counts, list) }.flow
            }.cachedIn(viewModelScope)

    /**
     * The shows favourited whole ([FavouriteShows]), while the Shows tab shows Favourites; empty otherwise, so browsing
     * never waits on it and a heart tapped elsewhere doesn't start the list over.
     */
    private val favouriteShows: Flow<List<String>> =
        combine(
            filter(ContentKind.SERIES).map { it == Filter.Favourites }.distinctUntilChanged(),
            container.picks.favourites.value,
        ) { on, ids -> if (on) ids else emptySet() }
            .distinctUntilChanged()
            .mapLatest { ids ->
                attempt("BrowseViewModel", "Favourite shows failed") {
                    FavouriteShows.keys(container.db, ids)
                }.getOrDefault(emptyList())
            }.distinctUntilChanged()

    /** Every show has one row in the per-show table, so the Shows tab skips grouping (most playlists). */
    private val oneRowEach: Flow<Boolean> =
        container.db
            .shows()
            .oneRowEach()
            .catch { e ->
                AppLog.w("BrowseViewModel", "Couldn't check the per-show table", e)
                emit(false)
            }.distinctUntilChanged()

    val shows: Flow<PagingData<ShowSummary>> =
        combine(
            query(ContentKind.SERIES),
            sort(ContentKind.SERIES),
            favouriteShows,
            oneRowEach,
        ) { q, order, favs, flat -> ShowsQuery(q, order, favs, flat) }
            .flatMapLatest { shows ->
                val (q, order, favs) = shows
                val flat = shows.flat
                val group = (q.filter as? Filter.Group)?.name
                // Browsing reads the per-show table; a search or Favourites narrows the episodes themselves.
                val list =
                    if (q.search.isEmpty() && q.filter != Filter.Favourites) {
                        QuickLists.showsTab(order, q.hidden, q.adult, group, flat)
                    } else {
                        QuickLists.shows(
                            order,
                            q.hidden,
                            q.adult,
                            group,
                            q.filter == Filter.Favourites,
                            q.search,
                            Search.match(q.search),
                            favs,
                        )
                    }
                Pager(config) { QuickPagingSource(container.db, counts, list) }.flow
            }.cachedIn(viewModelScope)

    private val continueFlows = mutableMapOf<ContentKind, StateFlow<List<ContinueRow>>>()

    /** Continue Watching for Movies (kind MOVIE) or Shows (episodes, kind SERIES). */
    fun continueWatching(kind: ContentKind): StateFlow<List<ContinueRow>> = continueFlows.getOrPut(kind) {
        settings
            .map { it?.hiddenKeys().orEmpty() to (it?.showAdult ?: false) }
            .distinctUntilChanged()
            .flatMapLatest { (hidden, adult) -> items.continueWatching(kind.code, hidden, adult) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
            // Eagerly once Home warms siblings; WhileSubscribed alone dropped inactive tabs after 5s.
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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

    /** Home has drawn: the work held for a quiet start can begin ([com.tunedplayer.app.work.QuietStart]). */
    fun homeShown() {
        container.quietStart.homeShown()
        // Speculative warm of Movies/Shows (and Live) while the person is still on the first tab.
        container.browseWarm.warmHome()
        warmSiblingCollectors()
    }

    /**
     * Touch group chips, own lists, Continue watching and Live filter probes for every tab while Home is up, so a tab
     * switch does not wait on the first Room answer (docs/decisions/speed.md › Speculative warm).
     */
    private fun warmSiblingCollectors() {
        viewModelScope.launchSafely("BrowseViewModel") {
            if (!container.settings.settings
                    .first()
                    .warmNextScreens
            ) {
                return@launchSafely
            }
            ContentKind.entries.forEach { kind ->
                groups(kind)
                if (kind != ContentKind.LIVE) continueWatching(kind)
                if (kind != ContentKind.SERIES) lists(kind)
            }
            // WhileSubscribed probes: keep a collector so Filters opens at once even before the Live chip is open.
            launchSafely("BrowseViewModel") { liveOffers.collect { } }
            launchSafely("BrowseViewModel") { liveRegions.collect { } }
        }
    }

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

    /** A whole show's heart, on its poster's long press ([FavouriteShows]). */
    fun toggleShowFavourite(
        showKey: String,
        favourite: Boolean,
    ) {
        container.picks.setShowFavourite(showKey, !favourite)
    }

    private val listFlows = mutableMapOf<ContentKind, StateFlow<List<ListEntity>>>()

    /** The person's own lists with something of [kind] in them, for the chips (Live TV and Movies). */
    fun lists(kind: ContentKind): StateFlow<List<ListEntity>> = listFlows.getOrPut(kind) {
        container.lists.withKind(kind).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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

    init {
        // Each profile's filters stay chosen until changed, even after the app is closed (Melvin, 2026-11-21). Read
        // when the profile is known and again when it changes; every change after that is written for that profile.
        viewModelScope.launchSafely("BrowseViewModel", onError = { restored.value = true }) {
            settings
                .map { it?.profileId }
                .distinctUntilChanged()
                .collectLatest { id ->
                    if (id == null) return@collectLatest
                    restore(id)
                    chosen().drop(1).collect { saved ->
                        attempt("BrowseViewModel", "Couldn't save the filters") {
                            container.browseFilters.set(id, SavedFilters.encode(saved))
                        }
                    }
                }
        }
    }

    /** Everything chosen on the tabs, as it changes. */
    private fun chosen(): Flow<SavedFilters> = combine(
        filter(ContentKind.LIVE),
        filter(ContentKind.MOVIE),
        filter(ContentKind.SERIES),
        liveQuick,
    ) { live, movies, shows, quick ->
        SavedFilters(mapOf(ContentKind.LIVE to live, ContentKind.MOVIE to movies, ContentKind.SERIES to shows), quick)
    }.distinctUntilChanged()

    /** Puts [profileId]'s saved filters in place; nothing saved, or unreadable, is nothing chosen. */
    private suspend fun restore(profileId: Long) {
        val text = attempt("BrowseViewModel", "Couldn't read the saved filters") { container.browseFilters.get(profileId) }.getOrNull()
        val saved = SavedFilters.decode(text)
        ContentKind.entries.forEach { filter(it).value = saved.chip(it) }
        liveQuick.value = saved.live
        restored.value = true
        ContentKind.entries.forEach { kind -> forgetIfGone(kind, saved.chip(kind)) }
    }

    /**
     * A saved category or list that is gone (hidden, removed, or another playlist since) goes back to everything, so a
     * tab never opens empty on a filter nobody can see. Checked once the tab's categories have loaded.
     */
    private fun forgetIfGone(
        kind: ContentKind,
        chosen: Filter,
    ) {
        if (chosen !is Filter.Group && chosen !is Filter.OwnList) return
        viewModelScope.launchSafely("BrowseViewModel") {
            val there =
                when (chosen) {
                    is Filter.Group -> {
                        val names = withTimeoutOrNull(GONE_WAIT_MS) { groups(kind).first { it.isNotEmpty() } } ?: return@launchSafely
                        names.any { it.groupName == chosen.name }
                    }

                    is Filter.OwnList -> {
                        container.lists
                            .withKind(kind)
                            .first()
                            .any { it.id == chosen.id }
                    }
                }
            if (!there && filter(kind).value == chosen) filter(kind).value = Filter.All
        }
    }

    private companion object {
        /** How long the lists wait for the saved filters before loading without them; the read takes a few ms. */
        const val RESTORE_WAIT_MS = 500L

        /** How long a saved category waits for the tab's categories before it is trusted as it is. */
        const val GONE_WAIT_MS = 15_000L
    }
}
