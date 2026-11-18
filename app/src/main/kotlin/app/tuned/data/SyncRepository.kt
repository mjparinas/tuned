package app.tuned.data

import androidx.room.withTransaction
import app.tuned.core.CatchUp
import app.tuned.core.ContentKind
import app.tuned.core.EpisodeInfo
import app.tuned.core.Episodes
import app.tuned.core.GuideFixes
import app.tuned.core.M3uParser
import app.tuned.core.PlaylistEntry
import app.tuned.core.PlaylistLink
import app.tuned.core.Search
import app.tuned.core.StableId
import app.tuned.core.Streams
import app.tuned.core.XtreamAccount
import app.tuned.core.XtreamApi
import app.tuned.core.XtreamUrls
import app.tuned.data.db.CatchUpEntity
import app.tuned.data.db.GuideWriter
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.PlaylistWriter
import app.tuned.data.db.ShowGroups
import app.tuned.data.db.TunedDatabase
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

/** Where a refresh is, for the progress bars on sign-in, on the lists and in the notification. */
data class SyncProgress(
    val step: Step,
    /** Bytes downloaded, or rows saved. */
    val done: Long = 0,
    /** Bytes in the file, or -1 when the server doesn't say (and while saving). */
    val total: Long = -1,
) {
    enum class Step { Playlist, SavingPlaylist, Guide, SavingGuide }

    /** 0 to 1 while the size is known, otherwise null (an indeterminate bar). */
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

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
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val dir: File,
) {
    private val lock = Mutex()
    private val downloader = Downloader(http)

    data class Result(
        val items: Int,
        val programmes: Int,
    )

    suspend fun refreshAll(progress: (SyncProgress) -> Unit = {}): Result = lock.withLock {
        val items = refreshPlaylistLocked(progress)
        val programmes = attempt(TAG_SYNC, "Guide refresh failed") { refreshGuideLocked(progress) }.getOrElse { 0 } // not fatal
        Result(items, programmes)
    }

    suspend fun refreshGuide(progress: (SyncProgress) -> Unit = {}): Int = lock.withLock { refreshGuideLocked(progress) }

    private suspend fun refreshPlaylistLocked(progress: (SyncProgress) -> Unit): Int {
        val login = credentials.load() ?: throw IOException("Not signed in")
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
        settings.update { it.copy(lastPlaylistSync = System.currentTimeMillis(), lastSyncError = "") }
        // Catch-up is a bonus: a server that doesn't answer leaves the last known channels as they were.
        if (login.playlistUrl.isBlank()) attempt("Sync", "Catch-up refresh failed") { refreshCatchUp(login) }
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
        val xtream = login.playlistUrl.isBlank()
        val url = login.playlistUrl.ifBlank { XtreamUrls.playlist(login.server, login.username, login.password) }
        val saved = { rows: Int -> progress(SyncProgress(SyncProgress.Step.SavingPlaylist, rows.toLong())) }
        val api = XtreamImport(http, login, userAgent())
        val m3u =
            runCatchingIo {
                download(url, "playlist", SyncProgress.Step.Playlist, progress) { input ->
                    importPlaylist(saved) { add ->
                        var shows = 0
                        var order = 0
                        val reader = input.bufferedReader()
                        rememberHeaderGuide(reader)
                        for (entry in M3uParser.entries(reader)) {
                            if (entry.kind == ContentKind.SERIES) shows++
                            order = entry.order + 1
                            add(entry)
                        }
                        // Some providers leave series out of the M3U; their API still lists them.
                        if (xtream && shows == 0) attempt(TAG_SYNC, "No shows from the API either") { addApiShows(api, order, add) }
                    }
                }
            }
        val failure = m3u.exceptionOrNull() ?: return m3u.getOrThrow()
        if (!xtream) throw failure
        AppLog.w(TAG_SYNC, "M3U download failed; reading the Xtream API instead", failure)
        val fromApi =
            runCatchingIo {
                importPlaylist(saved) { add ->
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
        db.withTransaction {
            db.catchUp().deleteAll()
            rows.chunked(BATCH).forEach { db.catchUp().insertAll(it) }
        }
        markAdult(adult)
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
        val login = credentials.load() ?: throw IOException("Not signed in")
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
        val saved = { rows: Int -> progress(SyncProgress(SyncProgress.Step.SavingGuide, rows.toLong())) }
        val count =
            try {
                if (!import.begin()) return 0
                if (url != null) download(url, "guide", SyncProgress.Step.Guide, progress) { input -> import.read(input, saved) }
                // Extra guide links only fill gaps; one that fails leaves the rest of the guide as it is.
                for (extra in login.extraGuides) {
                    attempt(TAG_SYNC, "An extra guide failed") {
                        download(extra, "guide", SyncProgress.Step.Guide, progress) { input -> import.read(input, saved) }
                    }
                }
                import.commit()
            } finally {
                import.discard() // a no-op after a commit; after a failure, the old guide stays
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

    /** Saves [url] to disk (resuming and retrying as needed), then hands the unzipped file to [read] and deletes it. */
    private suspend fun <T> download(
        url: String,
        name: String,
        step: SyncProgress.Step,
        progress: (SyncProgress) -> Unit,
        read: suspend (InputStream) -> T,
    ): T {
        if (url.startsWith(Credentials.FILE_SCHEME)) {
            // A playlist picked from a file was copied into the app when it was added: read it where it is.
            val local = File(URI(url))
            if (!local.exists()) throw IOException("The playlist file is missing. Add it again in Settings › Playlists.")
            return withContext(Dispatchers.IO) { local.inputStream().use { read(Streams.maybeGunzip(it)) } }
        }
        val ua = settings.settings.first().userAgent
        val file = File(dir, name)
        try {
            downloader.download(url, file, ua) { bytes, total -> progress(SyncProgress(step, bytes, total)) }
            return withContext(Dispatchers.IO) { file.inputStream().use { read(Streams.maybeGunzip(it)) } }
        } finally {
            file.delete()
        }
    }

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
        saved: (Int) -> Unit,
        fill: suspend (add: suspend (PlaylistEntry) -> Unit) -> Unit,
    ): Int {
        val writer = PlaylistWriter(db)
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
                        saved(writer.count)
                    }
                }
            }
            writer.add(batch)
            // An empty answer changes nothing, so it never wipes a good cache.
            if (writer.commit() == 0) throw IOException("The playlist was empty")
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
        api.shows(first) { show, order ->
            val kept = fetched[Episodes.showKey(show.name)]
            if (kept.isNullOrEmpty()) add(XtreamApi.seriesStandIn(show, order)) else kept.forEach { add(it.toEntry()) }
        }
    }

    /**
     * Fetches the episodes of a show read from the Xtream API the first time it is opened, and swaps them in for its
     * stand-in row. A show from the M3U has its episodes already, and this does nothing.
     */
    suspend fun loadShow(showKey: String) {
        val standIn = db.items().episodesNow(showKey).firstOrNull { XtreamApi.standInId(it.url) != null } ?: return
        val login = credentials.load() ?: return
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
        db.shows().replaceStandIn(standIn.id, episodes)
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
        )
    }

    private companion object {
        const val BATCH = 1_000
        const val HEADER_PEEK = 8_192
        const val SQL_VARIABLES = 900
        const val MAX_ACCOUNT_ANSWER = 64_000
    }
}

private const val TAG_SYNC = "Sync"
