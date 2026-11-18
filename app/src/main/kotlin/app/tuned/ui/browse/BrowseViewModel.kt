package app.tuned.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tuned.container
import app.tuned.core.ContentKind
import app.tuned.core.HiddenGroups
import app.tuned.core.Search
import app.tuned.data.AppSettings
import app.tuned.data.SyncProgress
import app.tuned.data.db.ChannelNumbers
import app.tuned.data.db.ChannelWithNow
import app.tuned.data.db.ContinueRow
import app.tuned.data.db.GroupCount
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.ProfileEntity
import app.tuned.data.db.ShowSummary
import app.tuned.data.hiddenKeys
import app.tuned.diagnostics.launchSafely
import app.tuned.work.TvHome
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
        // The Android TV home screen rows (Continue watching, favourites); nothing on phones.
        TvHome.schedule(app)
    }

    private val filters = mutableMapOf<ContentKind, MutableStateFlow<Filter>>()
    private val searches = mutableMapOf<ContentKind, MutableStateFlow<String>>()

    fun filter(kind: ContentKind): MutableStateFlow<Filter> = filters.getOrPut(kind) { MutableStateFlow(Filter.All) }

    fun search(kind: ContentKind): MutableStateFlow<String> = searches.getOrPut(kind) { MutableStateFlow("") }

    private val settings: StateFlow<AppSettings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    val channels: Flow<PagingData<ChannelWithNow>> =
        query(ContentKind.LIVE)
            .flatMapLatest { q ->
                Pager(config) {
                    val group = (q.filter as? Filter.Group)?.name
                    val match = Search.match(q.search)
                    when (q.filter) {
                        Filter.Favourites -> items.favouriteChannels(q.now, q.hidden, q.adult, group, q.search, match)
                        Filter.Recent -> items.recentChannels(q.now, q.hidden, q.adult, group, q.search, match)
                        else -> items.channels(q.now, q.hidden, q.adult, group, q.search, match)
                    }
                }.flow
            }.cachedIn(viewModelScope)

    val movies: Flow<PagingData<ItemEntity>> =
        query(ContentKind.MOVIE)
            .flatMapLatest { q ->
                Pager(config) {
                    items.items(
                        ContentKind.MOVIE.code,
                        q.hidden,
                        q.adult,
                        (q.filter as? Filter.Group)?.name,
                        q.filter == Filter.Favourites,
                        q.search,
                        Search.match(q.search),
                    )
                }.flow
            }.cachedIn(viewModelScope)

    val shows: Flow<PagingData<ShowSummary>> =
        query(ContentKind.SERIES)
            .flatMapLatest { q ->
                Pager(config) {
                    val group = (q.filter as? Filter.Group)?.name
                    // Browsing reads the per-show table; a search or Favourites narrows the episodes themselves.
                    if (q.search.isEmpty() && q.filter != Filter.Favourites) {
                        container.db.shows().showsTab(q.hidden, q.adult, group)
                    } else {
                        items.shows(q.hidden, q.adult, group, q.filter == Filter.Favourites, q.search, Search.match(q.search))
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

    private val groupFlows = mutableMapOf<ContentKind, StateFlow<List<GroupCount>>>()

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
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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

    /** Shown changed the moment a heart is tapped ([app.tuned.data.Picks]). */
    val favouriteIds: StateFlow<Set<Long>> = container.picks.favourites.value

    val lastError = settings.map { it?.lastSyncError.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun refresh() = container.scheduler.refreshNow()

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
