package com.tunedplayer.app.work

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.data.BrowseFilterStore
import com.tunedplayer.app.data.MetadataRepository
import com.tunedplayer.app.data.SettingsRepository
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.diagnostics.Lighter
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield

/**
 * Speculative warm of the next browse screens (Melvin, 2026-11-23: hide IO latency the way social apps do while the
 * person is still picking a profile or sitting on Live TV). Room first pages and Coil disk for logos/posters already
 * known; bounded TMDB for the same first page after Quiet start. Never opens a stream
 * (docs/decisions/speed.md › Speculative warm).
 */
class BrowseWarm(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deps: Deps,
) {
    /** Lazily resolved pieces so [AppContainer] can wire this without a long constructor list. */
    class Deps(
        val db: () -> TunedDatabase,
        val settings: SettingsRepository,
        val browseFilters: () -> BrowseFilterStore,
        val metadata: () -> MetadataRepository,
        val quietStart: QuietStart,
        val watching: () -> Boolean,
        val online: () -> Boolean,
        val touchHttp: () -> Unit,
    )

    private var focusJob: Job? = null
    private var pickJob: Job? = null
    private var homeJob: Job? = null
    private val artSlots = Semaphore(ART_PARALLEL)
    private val lightArtSlots = Semaphore(ART_PARALLEL_LIGHTER)

    /** Build the database (and HTTP, when asked) while the person is still typing a playlist login. */
    fun touchClients(includeHttp: Boolean = true) {
        scope.launchSafely(TAG) {
            deps.db()
            if (includeHttp) deps.touchHttp()
        }
    }

    /**
     * Profile focused or PIN about to unlock: open the DB, touch that profile's saved filters, and warm Live TV's
     * first page (Room only).
     */
    fun warmFocus(profileId: Long) {
        focusJob?.cancel()
        focusJob =
            scope.launchSafely(TAG) {
                if (!enabled()) return@launchSafely
                attempt(TAG, "Couldn't warm Live TV for the focused profile") {
                    val db = deps.db()
                    deps.browseFilters().get(profileId)
                    val live = db.browseWarm().warmArt(ContentKind.LIVE.code, PAGE)
                    db.browseWarm().warmGroups(ContentKind.LIVE.code, CHIP_LIMIT)
                    enqueueArt(live.mapNotNull { it.logo })
                }
            }
    }

    /** Profile picked: warm Movies and Shows first pages and their chips, then enqueue known artwork. */
    fun warmPick(profileId: Long) {
        pickJob?.cancel()
        pickJob =
            scope.launchSafely(TAG) {
                if (!enabled()) return@launchSafely
                attempt(TAG, "Couldn't warm Movies and Shows after the profile pick") {
                    val db = deps.db()
                    deps.browseFilters().get(profileId)
                    coroutineScope {
                        val warm = db.browseWarm()
                        val movies = async { warm.warmArt(ContentKind.MOVIE.code, PAGE) }
                        val shows = async { warm.warmShowArt(PAGE) }
                        val movieChips = async { warm.warmGroups(ContentKind.MOVIE.code, CHIP_LIMIT) }
                        val showChips = async { warm.warmShowGroups(CHIP_LIMIT) }
                        movieChips.await()
                        showChips.await()
                        val filmRows = movies.await()
                        val showRows = shows.await()
                        val logos = filmRows.mapNotNull { it.logo } + showRows.mapNotNull { it.logo }
                        val keys =
                            filmRows.map { "movie|${it.name}" } + showRows.map { "tv|${it.showName}" }
                        val posters =
                            keys.chunked(POSTER_BATCH).flatMap { batch ->
                                attempt(TAG, "Couldn't read cached posters") { db.metadata().posters(batch) }
                                    .getOrDefault(emptyList())
                            }
                        enqueueArt(logos + posters)
                        if (!Lighter.on.value && deps.online()) {
                            deps.quietStart.await()
                            if (deps.watching() || !enabled()) return@coroutineScope
                            fillTmdb(
                                films = filmRows.map { it.name },
                                shows = showRows.map { it.showName },
                            )
                        }
                    }
                }
            }
    }

    /**
     * Home has drawn: keep warming sibling tabs' Room pages and (after Quiet start) fill missing TMDB for the first
     * Movies/Shows pages when Lighter is off.
     */
    fun warmHome() {
        homeJob?.cancel()
        homeJob =
            scope.launchSafely(TAG) {
                if (!enabled()) return@launchSafely
                attempt(TAG, "Couldn't warm Home's sibling tabs") {
                    val db = deps.db()
                    coroutineScope {
                        val warm = db.browseWarm()
                        val movies = async { warm.warmArt(ContentKind.MOVIE.code, PAGE) }
                        val shows = async { warm.warmShowArt(PAGE) }
                        val live = async { warm.warmArt(ContentKind.LIVE.code, PAGE) }
                        async { warm.warmGroups(ContentKind.MOVIE.code, CHIP_LIMIT) }.await()
                        async { warm.warmShowGroups(CHIP_LIMIT) }.await()
                        async { warm.warmGroups(ContentKind.LIVE.code, CHIP_LIMIT) }.await()
                        val filmRows = movies.await()
                        val showRows = shows.await()
                        val liveRows = live.await()
                        val logos =
                            filmRows.mapNotNull { it.logo } + showRows.mapNotNull { it.logo } + liveRows.mapNotNull { it.logo }
                        val keys =
                            filmRows.map { "movie|${it.name}" } + showRows.map { "tv|${it.showName}" }
                        val posters =
                            keys.chunked(POSTER_BATCH).flatMap { batch ->
                                attempt(TAG, "Couldn't read cached posters") { db.metadata().posters(batch) }
                                    .getOrDefault(emptyList())
                            }
                        enqueueArt(logos + posters)
                        deps.quietStart.await()
                        if (deps.watching() || Lighter.on.value || !deps.online() || !enabled()) return@coroutineScope
                        fillTmdb(
                            films = filmRows.map { it.name },
                            shows = showRows.map { it.showName },
                        )
                    }
                }
            }
    }

    /** Enqueue URLs into Coil's disk cache only (same idea as [com.tunedplayer.app.data.SavedArtwork]). */
    fun enqueueArt(urls: Collection<String>) {
        val distinct =
            urls
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(PAGE)
                .toList()
        if (distinct.isEmpty()) return
        scope.launchSafely(TAG) {
            if (!enabled() || deps.watching()) return@launchSafely
            val slots = if (Lighter.on.value) lightArtSlots else artSlots
            val loader = SingletonImageLoader.get(context)
            distinct.forEach { url ->
                if (deps.watching()) return@launchSafely
                slots.withPermit {
                    attempt(TAG, "Couldn't warm image $url") {
                        loader.execute(
                            ImageRequest
                                .Builder(context)
                                .data(url)
                                .size(Size(THUMB_PX, THUMB_PX))
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .build(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun fillTmdb(
        films: List<String>,
        shows: List<String>,
    ) {
        val repo = deps.metadata()
        val filmNeed =
            films
                .filter { name ->
                    attempt(TAG, "Couldn't check film metadata") { !repo.answered(name, tv = false) }.getOrDefault(false)
                }.take(TMDB_CAP)
        val showNeed =
            shows
                .filter { name ->
                    attempt(TAG, "Couldn't check show metadata") { !repo.answered(name, tv = true) }.getOrDefault(false)
                }.take(TMDB_CAP)
        if (filmNeed.isEmpty() && showNeed.isEmpty()) return
        val gate = Semaphore(TMDB_PARALLEL)
        coroutineScope {
            (filmNeed.map { it to false } + showNeed.map { it to true })
                .map { (name, tv) ->
                    async {
                        gate.withPermit {
                            if (deps.watching() || !enabled() || !deps.online()) return@withPermit
                            yield()
                            attempt(TAG, "Couldn't warm TMDB for $name") { repo.summary(name, tv) }
                        }
                    }
                }.awaitAll()
        }
    }

    private suspend fun enabled(): Boolean = deps.settings.settings
        .first()
        .warmNextScreens

    companion object {
        private const val TAG = "BrowseWarm"
        const val PAGE = 120
        private const val CHIP_LIMIT = 80
        private const val POSTER_BATCH = 40
        private const val TMDB_CAP = 40
        private const val TMDB_PARALLEL = 2
        private const val ART_PARALLEL = 3
        private const val ART_PARALLEL_LIGHTER = 1
        private const val THUMB_PX = 64
    }
}
