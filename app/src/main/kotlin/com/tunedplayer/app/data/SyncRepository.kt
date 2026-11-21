package com.tunedplayer.app.data

import androidx.room.withTransaction
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.core.CatchUp
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.EpisodeInfo
import com.tunedplayer.app.core.Episodes
import com.tunedplayer.app.core.GuideFixes
import com.tunedplayer.app.core.M3uParser
import com.tunedplayer.app.core.PlaylistEntry
import com.tunedplayer.app.core.PlaylistLink
import com.tunedplayer.app.core.Programme
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.StableId
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.core.XtreamAccount
import com.tunedplayer.app.core.XtreamApi
import com.tunedplayer.app.core.XtreamUrls
import com.tunedplayer.app.data.SyncProgress.Step
import com.tunedplayer.app.data.db.CatchUpEntity
import com.tunedplayer.app.data.db.CatchUpItemEntity
import com.tunedplayer.app.data.db.GuideWriter
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.PlaylistWriter
import com.tunedplayer.app.data.db.ProgrammeEntity
import com.tunedplayer.app.data.db.ShowCoverEntity
import com.tunedplayer.app.data.db.ShowDetailsEntity
import com.tunedplayer.app.data.db.ShowGroups
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.data.db.offMain
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Downloads the playlist and guide and swaps them into the database. Each file is first saved to disk by
 * [Downloader], which rides out slow and dropped connections, and is then read from disk in batches, so memory stays
 * flat. No batch holds the database for long: the playlist ([PlaylistWriter]) and the guide ([GuideWriter]) are
 * written beside the old ones and brought in at the end, so browsing and search keep working on the
 * cache throughout a refresh. A failed refresh leaves the guide as it was, and the playlist's streams in place.
 */
class SyncRepository(
    private val db: TunedDatabase,
    private val http: OkHttpClient,
    /** The login in use ([CredentialStore.load]). */
    private val currentLogin: () -> Credentials?,
    private val settings: SettingsRepository,
    private val dir: File,
    /** The signed-in Stalker portal, shared with the player so its token is reused. */
    private val stalker: StalkerLinks = StalkerLinks(http, currentLogin),
) {
    private val lock = Mutex()
    private val downloader = Downloader(http)
    private val reads = ReadProgress()
    private val memo = PlaylistMemo(File(dir, "playlist.fingerprint"), BuildConfig.VERSION_CODE.toString())

    /** Set by a refresh that read an Xtream server's M3U, which lacks what the sorts need ([XtreamDetails]). */
    private var filmDetailsDue = false
    private var showDetailsDue = false

    data class Result(
        val items: Int,
        val programmes: Int,
    )

    suspend fun refreshAll(progress: (SyncProgress) -> Unit = {}): Result = offMain {
        lock.withLock {
            val plan = Plan(progress)
            plan.steps = SyncProgress.planFor(currentLogin(), headerGuide.exists())
            plan.start()
            val items = refreshPlaylistLocked(plan::report)
            // A plain playlist's header names its guide, or doesn't: the steps now say which.
            plan.steps = SyncProgress.planFor(currentLogin(), headerGuide.exists())
            val programmes = attempt(TAG_SYNC, "Guide refresh failed") { refreshGuideLocked(plan::report) }.getOrElse { 0 } // not fatal
            // A portal's films and shows come last: they take many small calls, and Live TV and the guide are ready first.
            if (currentLogin()?.kind == Credentials.Kind.Stalker) {
                attempt(TAG_SYNC, "The portal's films and shows failed") { refreshPortalLibrary(plan::report) }
            }
            Result(items, programmes)
        }
    }

    suspend fun refreshGuide(progress: (SyncProgress) -> Unit = {}): Int = offMain {
        lock.withLock {
            val plan = Plan(progress)
            plan.steps = SyncProgress.GUIDE
            plan.start()
            refreshGuideLocked(plan::report)
        }
    }

    /** Stamps each report with the refresh's steps, so the screens can number them. */
    private class Plan(
        private val sink: (SyncProgress) -> Unit,
    ) {
        @Volatile var steps: List<SyncProgress.Step> = emptyList()

        /** The first step, before anything is downloaded, so the screens show the steps at once. */
        fun start() = steps.firstOrNull()?.let { report(SyncProgress(it)) }

        /** A report that fails to reach the screens is logged; the refresh itself carries on. */
        fun report(progress: SyncProgress) {
            attempt(TAG_SYNC, "Couldn't report progress") { sink(progress.copy(steps = steps)) }
        }
    }

    /**
     * Fills an empty cache from the copy kept in the Tuned account ([AccountChannels]) when the provider couldn't be
     * read. The playlist's memo is forgotten, so the next refresh saves the provider's own file over it. Returns how
     * many channels it saved; 0 when the cache already had channels (the provider got there first).
     */
    suspend fun fillFromCopy(
        channels: List<PlaylistEntry>,
        guide: List<Programme>,
    ): Int = offMain {
        lock.withLock {
            if (db.items().any()) return@withLock 0
            memo.forget()
            val count = importPlaylist(progress = {}) { add -> channels.forEach { add(it) } }
            if (guide.isNotEmpty()) {
                val writer = GuideWriter(db)
                try {
                    writer.begin()
                    for (batch in guide.chunked(BATCH)) {
                        writer.add(batch.map { ProgrammeEntity(0, it.channelId, it.start, it.stop, it.title, null) })
                    }
                    writer.commit()
                } finally {
                    writer.discard()
                }
            }
            count
        }
    }

    private suspend fun refreshPlaylistLocked(progress: (SyncProgress) -> Unit): Int {
        val login = currentLogin() ?: throw IOException("Not signed in")
        val count =
            try {
                importFromServer(login, progress)
            } catch (e: IOException) {
                val message = XtreamUrls.redact(e.message ?: "Couldn't download the playlist", login.username, login.password)
                settings.update { it.copy(lastSyncError = message) }
                throw IOException(message, e)
            }
        // Channels with their own guide fix keep it across the new playlist.
        db.guideFixes().reapply()
        // A country found for the first time follows the rest: hidden when most are.
        settleNewRegions(db, settings)
        settings.update { it.copy(lastPlaylistSync = System.currentTimeMillis(), lastSyncError = "") }
        if (login.kind == Credentials.Kind.Xtream) progress(SyncProgress(SyncProgress.Step.Details))
        // Catch-up is a bonus: a server that doesn't answer leaves the last known channels as they were.
        if (login.playlistUrl.isBlank()) attempt("Sync", "Catch-up refresh failed") { refreshCatchUp(login) }
        // Times added, years and ratings for the Movies and Shows sorts, which an Xtream M3U leaves out. A bonus too.
        if (filmDetailsDue || showDetailsDue) {
            val details = XtreamDetails(db, XtreamImport(http, login, userAgent()))
            if (filmDetailsDue) attempt(TAG_SYNC, "Film details failed") { details.films() }
            if (showDetailsDue) attempt(TAG_SYNC, "Show details failed") { details.shows() }
        }
        return count
    }

    /**
     * The M3U playlist, read into the cache. On an Xtream login, a server that won't send the M3U is read through its
     * API instead, and one whose M3U has no shows gets them from the API (core/XtreamApi). The M3U's own failure is the
     * one reported when the API fails too.
     */
    private suspend fun importFromServer(
        login: Credentials,
        progress: (SyncProgress) -> Unit,
    ): Int {
        filmDetailsDue = false
        showDetailsDue = false
        if (login.kind == Credentials.Kind.Stalker) return importPortal(login, progress)
        val xtream = login.playlistUrl.isBlank()
        val url = login.playlistUrl.ifBlank { XtreamUrls.playlist(login.server, login.username, login.password) }
        val api = XtreamImport(http, login, userAgent())
        // A plain playlist's catch-up comes from its own attributes, gathered as it's read (Xtream asks the API after).
        val catchUp = ArrayList<CatchUpEntity>()
        // False when the file hadn't changed and wasn't read: the catch-up already saved from it stays.
        var readCatchUp = false
        val m3u =
            runCatchingIo {
                download(url, "playlist", Step.DownloadPlaylist, Step.ReadPlaylist, progress) { input, fingerprint ->
                    val print = fingerprint()
                    // The same file as last time changes nothing: keep the cache and skip the minutes of saving.
                    if (memo.unchanged(print, db.items().any())) {
                        AppLog.i(TAG_SYNC) { "The playlist hasn't changed since the last refresh; keeping the cache" }
                        return@download db.items().count()
                    }
                    var fromOneFile = true
                    memo.forget()
                    importPlaylist(progress) { add ->
                        catchUp.clear() // a download that starts over reads the playlist again
                        readCatchUp = true
                        var shows = 0
                        var order = 0
                        val reader = input.bufferedReader()
                        rememberHeaderGuide(reader)
                        for (entry in M3uParser.entries(reader)) {
                            if (entry.kind == ContentKind.SERIES) shows++
                            order = entry.order + 1
                            if (!xtream) entry.catchUp?.let { catchUp += CatchUpEntity(CatchUp.key(entry.url), it.days, it.encode()) }
                            add(entry)
                        }
                        // Some providers leave series out of the M3U; their API still lists them, and those can change
                        // while the file doesn't, so such a cache is always saved afresh.
                        if (xtream && shows == 0) {
                            fromOneFile = false
                            attempt(TAG_SYNC, "No shows from the API either") { addApiShows(api, order, add) }
                        }
                        markDetailsDue(xtream, shows)
                    }.also { if (fromOneFile) memo.remember(print) }
                }
            }
        val failure =
            m3u.exceptionOrNull() ?: return m3u.getOrThrow().also {
                if (!xtream && readCatchUp) replaceCatchUp(catchUp)
                // An unchanged file keeps its catch-up; the filter's copy is made again in case it predates it.
                if (!xtream && !readCatchUp) linkCatchUp()
            }
        if (!xtream) throw failure
        AppLog.w(TAG_SYNC, "M3U download failed; reading the Xtream API instead", failure)
        memo.forget() // the cache is about to come from the API, not from a file
        val fromApi =
            runCatchingIo {
                importPlaylist(progress) { add ->
                    var order = 0
                    api.channelsAndFilms { entry ->
                        order = entry.order + 1
                        add(entry)
                    }
                    addApiShows(api, order, add)
                }
            }
        fromApi.exceptionOrNull()?.let { failure.addSuppressed(it) }
        return fromApi.getOrElse { throw failure }
    }

    /** After an Xtream M3U was read, its films' (and any shows') times, years and ratings come from the API ([XtreamDetails]). */
    private fun markDetailsDue(
        xtream: Boolean,
        shows: Int,
    ) {
        filmDetailsDue = xtream
        showDetailsDue = xtream && shows > 0
    }

    /** A Stalker portal's live channels, read from its API in one answer (`core/Stalker`). */
    private suspend fun importPortal(
        login: Credentials,
        progress: (SyncProgress) -> Unit,
    ): Int {
        memo.forget() // a portal has no file to compare
        progress(SyncProgress(SyncProgress.Step.DownloadPlaylist))
        val channels = withContext(Dispatchers.IO) { stalker.portal(login).channels() }
        // Only live channels change here; the films and shows from the last library refresh stay as they are.
        return importPlaylist(progress, scope = "kind = 0") { add ->
            channels.forEachIndexed {
                order,
                channel,
                ->
                add(Stalker.live(channel, order))
            }
        }
    }

    /**
     * A portal's films and shows (Melvin, 2026-11-20: "add films and shows for mac portals"). Portals list them about
     * fourteen at a time, so a big library is hundreds of small calls: they run after Live TV and the guide are in,
     * and the films and shows from the last refresh stay on the lists until the new ones are all in. Films come from
     * the `vod` section; shows from `series` and from `vod` items marked as series. A show is one stand-in row until
     * it is opened ([loadShow]); one opened before keeps its episodes.
     */
    private suspend fun refreshPortalLibrary(progress: (SyncProgress) -> Unit) {
        val login = currentLogin()?.takeIf { it.kind == Credentials.Kind.Stalker } ?: return
        val portal = stalker.portal(login)
        val fetched = db.shows().fetchedEpisodes().groupBy { it.showKey }
        var order = db.items().count()
        var total = 0

        suspend fun section(type: String): List<Pair<String, List<Stalker.VodItem>>> {
            val categories =
                attempt(TAG_SYNC, "No $type categories") {
                    withContext(Dispatchers.IO) { portal.categories(type) }
                }.getOrDefault(emptyList())
            return categories.map { (id, title) ->
                currentCoroutineContext().ensureActive()
                val items =
                    attempt(TAG_SYNC, "A $type category failed") {
                        withContext(Dispatchers.IO) { buildList { portal.library(type, id, MAX_LIBRARY - total) { add(it) } } }
                    }.getOrDefault(emptyList())
                total += items.size
                title to items
            }
        }
        val library = SyncProgress.Step.Library
        progress(SyncProgress(library))
        val films = section(Stalker.VOD)
        val shows = section(Stalker.SERIES)
        importPlaylist(progress, scope = "kind != 0", read = library, save = library) { add ->
            suspend fun show(
                type: String,
                category: String,
                item: Stalker.VodItem,
            ) {
                val kept = fetched[Episodes.showKey(item.name)]
                if (kept.isNullOrEmpty()) add(Stalker.showStandIn(type, item, category, order++)) else kept.forEach { add(it.toEntry()) }
            }
            for ((category, items) in films) {
                for (item in items) if (item.isSeries) show(Stalker.VOD, category, item) else add(Stalker.movie(item, category, order++))
            }
            for ((category, items) in shows) items.forEach { show(Stalker.SERIES, category, it) }
        }
    }

    /**
     * Like [runCatching] for I/O only: anything else (a bug, a cancellation) still throws. `kotlin.Result` in full,
     * because this class has its own Result (a refresh's counts).
     */
    private inline fun <T> runCatchingIo(block: () -> T): kotlin.Result<T> = try {
        kotlin.Result.success(block())
    } catch (e: IOException) {
        kotlin.Result.failure(e)
    }

    /** Which live channels keep recordings (Xtream only), and the server's time zone that catch-up links use. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun refreshCatchUp(login: Credentials) = withContext(Dispatchers.IO) {
        val ua =
            settings.settings
                .first()
                .userAgent
                .ifBlank { null }

        fun get(url: String) = Request
            .Builder()
            .url(url)
            .apply { ua?.let { header("User-Agent", it) } }
            .build()
        val zone =
            http.newCall(get(XtreamUrls.account(login.server, login.username, login.password))).execute().use { r ->
                if (r.isSuccessful) XtreamAccount.timezone(r.body.string().take(MAX_ACCOUNT_ANSWER)) else null
            }
        if (zone != null) settings.update { it.copy(serverTimezone = zone) }
        val rows = ArrayList<CatchUpEntity>()
        val adult = HashSet<String>()
        http.newCall(get(XtreamUrls.liveStreams(login.server, login.username, login.password))).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            // Streamed one channel at a time: the list of every live channel can run to megabytes.
            for (o in Json.decodeToSequence(r.body.byteStream(), JsonObject.serializer())) {
                val id = o.text("stream_id") ?: continue
                val days = CatchUp.days(o.text("tv_archive"), o.text("tv_archive_duration"))
                if (days > 0) rows += CatchUpEntity(id, days)
                if (o.text("is_adult") == "1") adult += id
            }
        }
        replaceCatchUp(rows)
        markAdult(adult)
    }

    private suspend fun replaceCatchUp(rows: List<CatchUpEntity>) {
        db.withTransaction {
            db.catchUp().deleteRows()
            rows.chunked(BATCH).forEach { db.catchUp().insertAll(it) }
        }
        linkCatchUp()
    }

    /**
     * Saves the channels that keep recordings as item ids too, for Live TV's Catch-up filter: the catch-up table is
     * keyed by stream id or link, which a list can't join on. Item ids are stable across refreshes.
     */
    private suspend fun linkCatchUp() {
        val keys = db.catchUp().all().mapTo(HashSet()) { it.streamId }
        val items =
            if (keys.isEmpty()) {
                emptyList()
            } else {
                db
                    .catchUp()
                    .liveUrls()
                    .filter { CatchUp.key(it.url) in keys }
                    .map { CatchUpItemEntity(it.id) }
            }
        db.withTransaction {
            db.catchUp().deleteItems()
            items.chunked(BATCH).forEach { db.catchUp().insertItems(it) }
        }
    }

    /**
     * Channels the server flags `is_adult` are adult even when their category doesn't say so (other providers file
     * them under names like "Night" or "Premium"). Runs after each playlist import, which resets the flag from the names.
     */
    private suspend fun markAdult(streamIds: Set<String>) {
        if (streamIds.isEmpty()) return
        val ids =
            db
                .catchUp()
                .liveUrls()
                .filter { CatchUp.streamId(it.url) in streamIds }
                .map { it.id }
        ids.chunked(SQL_VARIABLES).forEach { db.catchUp().markAdult(it) }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun refreshGuideLocked(progress: (SyncProgress) -> Unit): Int {
        val login = currentLogin() ?: throw IOException("Not signed in")
        // A portal's own guide, unless a guide link was given for it.
        if (login.kind == Credentials.Kind.Stalker && login.guideUrl.isBlank()) return refreshPortalGuide(login, progress)
        // A plain playlist's guide is the one its header names; without one only the extra guide links are read.
        val main =
            login.guideUrl.ifBlank {
                if (login.kind == Credentials.Kind.Xtream) {
                    XtreamUrls.guide(login.server, login.username, login.password)
                } else {
                    headerGuide.takeIf { it.exists() }?.readText().orEmpty()
                }
            }
        val url = main.takeIf { it.isNotBlank() }
        if (url == null && login.extraGuides.isEmpty()) return 0
        val s = settings.settings.first()
        val import = GuideImport(db, s.guideDays, s.guideOffsetMinutes)
        val saved = { rows: Int -> progress(reads.at(SyncProgress.Step.ReadGuide, rows)) }
        val count =
            try {
                if (!import.begin()) return 0
                if (url != null) guideFile(url, progress) { input -> import.read(input, saved) }
                // Extra guide links only fill gaps; one that fails leaves the rest of the guide as it is.
                for (extra in login.extraGuides) {
                    attempt(TAG_SYNC, "An extra guide failed") { guideFile(extra, progress) { input -> import.read(input, saved) } }
                }
                progress(SyncProgress(SyncProgress.Step.SaveGuide))
                import.commit()
            } finally {
                import.discard() // a no-op after a commit; after a failure, the old guide stays
            }
        settings.update { it.copy(lastGuideSync = System.currentTimeMillis()) }
        return count
    }

    /**
     * A portal's guide from its API, read as it arrives and filed under each channel's guide id (`core/Stalker`). Extra
     * guide links then fill the channels it lacks, as for any playlist.
     */
    private suspend fun refreshPortalGuide(
        login: Credentials,
        progress: (SyncProgress) -> Unit,
    ): Int {
        val s = settings.settings.first()
        val import = GuideImport(db, s.guideDays, s.guideOffsetMinutes)
        val saved = { rows: Int -> progress(reads.at(SyncProgress.Step.ReadGuide, rows)) }
        // The portal files programmes under its own channel ids; the channels are stored under their guide ids.
        val guideIds =
            db
                .items()
                .liveLinks()
                .mapNotNull { link -> Stalker.parse(link.url)?.let { it.id to link.tvgId } }
                .toMap()
        val hours = (s.guideDays * HOURS_A_DAY).coerceIn(HOURS_A_DAY, MAX_PORTAL_GUIDE_HOURS)
        val portal = stalker.portal(login)
        val count =
            try {
                if (!import.begin()) return 0
                progress(SyncProgress(SyncProgress.Step.DownloadGuide))
                val programmes =
                    channelFlow {
                        withContext(Dispatchers.IO) { portal.guide(hours) { p -> trySendBlocking(p) } }
                    }.buffer(BATCH).mapNotNull { p -> guideIds[p.channelId]?.let { p.copy(channelId = it) } }
                import.read(programmes, saved)
                for (extra in login.extraGuides) {
                    attempt(TAG_SYNC, "An extra guide failed") { guideFile(extra, progress) { input -> import.read(input, saved) } }
                }
                progress(SyncProgress(SyncProgress.Step.SaveGuide))
                import.commit()
            } finally {
                import.discard()
            }
        settings.update { it.copy(lastGuideSync = System.currentTimeMillis()) }
        return count
    }

    /**
     * Moves the whole guide by a new offset (Settings › Guide) at once, under the refresh lock so a refresh running
     * meanwhile can't apply it twice.
     */
    suspend fun setGuideOffset(minutes: Int) = lock.withLock {
        val old = settings.settings.first().guideOffsetMinutes
        if (old == minutes) return@withLock
        db.guideFixes().shiftAll((minutes - old) * GuideFixes.MINUTE)
        settings.update { it.copy(guideOffsetMinutes = minutes) }
    }

    /** Runs [block] while no refresh is writing the guide, for [GuideFixRepository]. */
    suspend fun <T> whileIdle(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * Saves [url] to disk (resuming and retrying as needed), then hands the unzipped file to [read] and deletes it.
     * [read] can also ask for the file's [PlaylistMemo] fingerprint, which reads the file once more.
     */
    private suspend fun <T> download(
        url: String,
        name: String,
        step: SyncProgress.Step,
        readStep: SyncProgress.Step,
        progress: (SyncProgress) -> Unit,
        read: suspend (input: InputStream, fingerprint: suspend () -> String) -> T,
    ): T {
        if (url.startsWith(Credentials.FILE_SCHEME)) {
            // A playlist picked from a file was copied into the app when it was added: read it where it is.
            val local = File(URI(url))
            if (!local.exists()) throw IOException("The playlist file is missing. Add it again in Settings › Playlists.")
            return reads.read(local, readStep, progress) { read(it) { fingerprintOf(local) } }
        }
        val ua = settings.settings.first().userAgent
        val file = File(dir, name)
        try {
            // Until the server answers, the step says it is waiting rather than showing nothing.
            progress(SyncProgress(step))
            downloader.download(url, file, ua) { bytes, total -> progress(SyncProgress(step, bytes, total)) }
            return reads.read(file, readStep, progress) { read(it) { fingerprintOf(file) } }
        } finally {
            file.delete()
        }
    }

    /** A guide file, downloaded and then read. */
    private suspend fun <T> guideFile(
        url: String,
        progress: (SyncProgress) -> Unit,
        read: suspend (InputStream) -> T,
    ): T = download(url, "guide", SyncProgress.Step.DownloadGuide, SyncProgress.Step.ReadGuide, progress) { input, _ -> read(input) }

    private suspend fun fingerprintOf(file: File) = withContext(Dispatchers.IO) { file.inputStream().use(memo::fingerprint) }

    /** Where the guide named in the last playlist's `#EXTM3U` line is kept, for plain playlists without a guide link. */
    private val headerGuide get() = File(dir, "header-guide")

    /** Peeks at the first line for `x-tvg-url` and puts the reader back, so the parser still sees every line. */
    private fun rememberHeaderGuide(reader: BufferedReader) {
        reader.mark(HEADER_PEEK)
        val buffer = CharArray(HEADER_PEEK)
        val read = reader.read(buffer).coerceAtLeast(0)
        reader.reset()
        val first = String(buffer, 0, read).substringBefore('\n')
        val guide = PlaylistLink.headerGuide(first)
        dir.mkdirs()
        if (guide == null) headerGuide.delete() else headerGuide.writeText(guide)
    }

    /**
     * Writes the streams [fill] adds beside the cache and applies them at the end, so the lists and search keep working
     * on it meanwhile. A stream listed twice is kept once.
     */
    private suspend fun importPlaylist(
        progress: (SyncProgress) -> Unit,
        /** Which cached streams this import replaces (SQL on `items`); the rest stay as they are. Null: all of them. */
        scope: String? = null,
        /** The step reported while streams are read, and while they are saved into the cache. */
        read: SyncProgress.Step = SyncProgress.Step.ReadPlaylist,
        save: SyncProgress.Step = SyncProgress.Step.SavePlaylist,
        fill: suspend (add: suspend (PlaylistEntry) -> Unit) -> Unit,
    ): Int {
        val writer = PlaylistWriter(db, scope)
        writer.begin()
        try {
            val batch = ArrayList<ItemEntity>(BATCH)
            var live = 0
            fill { entry ->
                val parsed = entry.toRow()
                if (writer.accept(parsed.id)) {
                    // Live channels are numbered 1 up in playlist order, for typing a number on the remote.
                    batch += if (parsed.kind == ContentKind.LIVE.code) parsed.copy(number = ++live) else parsed
                    if (batch.size == BATCH) {
                        writer.add(batch.toList())
                        batch.clear()
                        progress(reads.at(read, writer.count))
                    }
                }
            }
            writer.add(batch)
            progress(reads.at(read, writer.count))
            progress(SyncProgress(save))
            // An empty answer changes nothing, so it never wipes a good cache.
            val saved = writer.commit { applied, total -> progress(SyncProgress(save, applied, total)) }
            if (saved == 0) throw IOException("The playlist was empty")
            return writer.count
        } finally {
            writer.discard() // a no-op after a commit; after a failure, the cache stays as it was
        }
    }

    /**
     * Shows from the Xtream API. A show whose episodes were fetched before (it was opened) keeps them; any other is one
     * stand-in row until it is opened ([loadShow]).
     */
    private suspend fun addApiShows(
        api: XtreamImport,
        first: Int,
        add: suspend (PlaylistEntry) -> Unit,
    ) {
        val fetched = db.shows().fetchedEpisodes().groupBy { it.showKey }
        val covers = mutableListOf<ShowCoverEntity>()
        val details = ArrayList<ShowDetailsEntity>()
        api.shows(first) { show, order ->
            val key = Episodes.showKey(show.name)
            details += ShowDetailsEntity(key, show.added, show.year, show.rating)
            val kept = fetched[key]
            if (kept.isNullOrEmpty()) {
                add(XtreamApi.seriesStandIn(show, order))
            } else {
                kept.forEach { add(it.toEntry()) }
                // An opened show's episodes carry their own screenshots, so its cover is kept beside them.
                show.icon?.takeIf { it.isNotBlank() }?.let { covers += ShowCoverEntity(key, it) }
            }
        }
        if (covers.isNotEmpty()) db.shows().saveCovers(covers)
        // What the Shows tab sorts on (newest, year, rating), before the shows are grouped.
        db.shows().replaceDetails(details)
    }

    /**
     * Fetches the episodes of a show read from the Xtream API the first time it is opened, and swaps them in for its
     * stand-in row. A show from the M3U has its episodes already, and this does nothing.
     */
    suspend fun loadShow(showKey: String) {
        val rows = db.items().episodesNow(showKey)
        rows.firstOrNull { Stalker.standIn(it.url) != null }?.let { return loadPortalShow(showKey, it) }
        val standIn = rows.firstOrNull { XtreamApi.standInId(it.url) != null } ?: return
        val login = currentLogin() ?: return
        val api = XtreamImport(http, login, userAgent())
        val episodes =
            api
                .episodes(
                    XtreamApi.standInId(standIn.url) ?: return,
                    standIn.showName ?: standIn.name,
                    standIn.groupName,
                    standIn.logo,
                    standIn.sortOrder,
                ).map { it.toRow() }
        if (episodes.isEmpty()) return
        // The stand-in carried the show's cover; its episodes carry their own screenshots instead.
        standIn.logo?.takeIf { it.isNotBlank() }?.let { db.shows().saveCovers(listOf(ShowCoverEntity(showKey, it))) }
        db.shows().replaceStandIn(standIn.id, episodes)
        ShowGroups.rebuild(db)
    }

    /** A portal show's seasons, fetched when it's first opened and swapped in for its stand-in row. */
    private suspend fun loadPortalShow(
        showKey: String,
        standIn: ItemEntity,
    ) {
        val show = Stalker.standIn(standIn.url) ?: return
        val login = currentLogin()?.takeIf { it.kind == Credentials.Kind.Stalker } ?: return
        val seasons = withContext(Dispatchers.IO) { stalker.portal(login).seasons(show.type, show.id) }
        val name = standIn.showName ?: standIn.name
        var order = standIn.sortOrder
        val episodes =
            if (seasons.isNotEmpty()) {
                seasons.flatMapIndexed { index, season ->
                    val number = Stalker.seasonNumber(season.name, index)
                    season.episodes.map { ep ->
                        Stalker.episode(show.type, show.id, name, standIn.groupName, standIn.logo, season.cmd, number, ep, order++)
                    }
                }
            } else {
                // An old-style portal lists a show's episodes on the show itself, all in one season.
                val cmd = show.cmd ?: return
                show.episodes.map { ep -> Stalker.episode(show.type, show.id, name, standIn.groupName, standIn.logo, cmd, 1, ep, order++) }
            }
        if (episodes.isEmpty()) return
        standIn.logo?.takeIf { it.isNotBlank() }?.let { db.shows().saveCovers(listOf(ShowCoverEntity(showKey, it))) }
        db.shows().replaceStandIn(standIn.id, episodes.map { it.toRow() })
        ShowGroups.rebuild(db)
    }

    private suspend fun userAgent(): String? = settings.settings
        .first()
        .userAgent
        .ifBlank { null }

    /** A stored episode back as a playlist entry, kept as it is by the next refresh. */
    private fun ItemEntity.toEntry() = PlaylistEntry(
        name = name,
        url = url,
        kind = ContentKind.fromCode(kind),
        group = groupName,
        tvgId = tvgId,
        logo = logo,
        region = region,
        adult = adult,
        episode = EpisodeInfo(showName ?: name, season ?: 1, episode ?: 1),
        order = sortOrder,
        added = added,
        year = year,
        rating = rating,
    )

    private fun PlaylistEntry.toRow(): ItemEntity {
        val ep = episode
        return ItemEntity(
            id = StableId.of(this),
            kind = kind.code,
            name = name,
            searchName = Search.normalize(name),
            groupName = group,
            region = region,
            adult = adult,
            logo = logo,
            url = url,
            tvgId = tvgId,
            showKey = ep?.let { Episodes.showKey(it.showName) },
            showName = ep?.showName,
            season = ep?.season,
            episode = ep?.episode,
            sortOrder = order,
            added = added,
            // What Movies and Shows sort on; live channels don't need it, and a big playlist is mostly live.
            year = if (kind == ContentKind.LIVE) null else year ?: TitleSort.yearInName(ep?.showName ?: name),
            rating = rating,
            sortTitle = if (kind == ContentKind.LIVE) "" else TitleSort.title(ep?.showName ?: name),
        )
    }

    private companion object {
        const val BATCH = 1_000
        const val HEADER_PEEK = 8_192
        const val SQL_VARIABLES = 900
        const val MAX_ACCOUNT_ANSWER = 64_000
        const val HOURS_A_DAY = 24

        /** A week: portals answer longer periods slowly, if at all. */
        const val MAX_PORTAL_GUIDE_HOURS = 168

        /** Films and shows read from one portal at most, so a runaway library can't fill the device. */
        const val MAX_LIBRARY = 200_000
    }
}

private const val TAG_SYNC = "Sync"
