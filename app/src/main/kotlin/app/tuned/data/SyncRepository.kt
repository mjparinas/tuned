package app.tuned.data

import android.util.Xml
import androidx.room.withTransaction
import app.tuned.core.CatchUp
import app.tuned.core.Episodes
import app.tuned.core.M3uParser
import app.tuned.core.PlaylistEntry
import app.tuned.core.Search
import app.tuned.core.StableId
import app.tuned.core.Streams
import app.tuned.core.XmltvParser
import app.tuned.core.XtreamAccount
import app.tuned.core.XtreamUrls
import app.tuned.data.db.CatchUpEntity
import app.tuned.data.db.GuideWriter
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.PlaylistWriter
import app.tuned.data.db.ProgrammeEntity
import app.tuned.data.db.TunedDatabase
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
import java.io.File
import java.io.IOException
import java.io.InputStream

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
        val url = login.playlistUrl.ifBlank { XtreamUrls.playlist(login.server, login.username, login.password) }
        val count =
            try {
                download(url, "playlist", SyncProgress.Step.Playlist, progress) { input ->
                    importPlaylist(input) { rows -> progress(SyncProgress(SyncProgress.Step.SavingPlaylist, rows.toLong())) }
                }
            } catch (e: IOException) {
                val message = XtreamUrls.redact(e.message ?: "Couldn't download the playlist", login.username, login.password)
                settings.update { it.copy(lastSyncError = message) }
                throw IOException(message, e)
            }
        settings.update { it.copy(lastPlaylistSync = System.currentTimeMillis(), lastSyncError = "") }
        // Catch-up is a bonus: a server that doesn't answer leaves the last known channels as they were.
        if (login.playlistUrl.isBlank()) attempt("Sync", "Catch-up refresh failed") { refreshCatchUp(login) }
        return count
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
                .items()
                .liveUrls()
                .filter { CatchUp.streamId(it.url) in streamIds }
                .map { it.id }
        ids.chunked(SQL_VARIABLES).forEach { db.catchUp().markAdult(it) }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun refreshGuideLocked(progress: (SyncProgress) -> Unit): Int {
        val login = credentials.load() ?: throw IOException("Not signed in")
        val url = login.guideUrl.ifBlank { XtreamUrls.guide(login.server, login.username, login.password) }
        val days = settings.settings.first().guideDays
        val count =
            download(url, "guide", SyncProgress.Step.Guide, progress) { input ->
                importGuide(input, days) { rows -> progress(SyncProgress(SyncProgress.Step.SavingGuide, rows.toLong())) }
            }
        settings.update { it.copy(lastGuideSync = System.currentTimeMillis()) }
        return count
    }

    /** Saves [url] to disk (resuming and retrying as needed), then hands the unzipped file to [read] and deletes it. */
    private suspend fun <T> download(
        url: String,
        name: String,
        step: SyncProgress.Step,
        progress: (SyncProgress) -> Unit,
        read: suspend (InputStream) -> T,
    ): T {
        val ua = settings.settings.first().userAgent
        val file = File(dir, name)
        try {
            downloader.download(url, file, ua) { bytes, total -> progress(SyncProgress(step, bytes, total)) }
            return withContext(Dispatchers.IO) { file.inputStream().use { read(Streams.maybeGunzip(it)) } }
        } finally {
            file.delete()
        }
    }

    private suspend fun importPlaylist(
        input: InputStream,
        saved: (Int) -> Unit,
    ): Int {
        // Written beside the cache and applied at the end, so the lists and search keep working on it meanwhile.
        val writer = PlaylistWriter(db)
        writer.begin()
        try {
            val batch = ArrayList<ItemEntity>(BATCH)
            for (entry in M3uParser.entries(input.bufferedReader())) {
                val row = entry.toRow()
                if (!writer.accept(row.id)) continue // the same stream listed twice
                batch += row
                if (batch.size == BATCH) {
                    writer.add(batch.toList())
                    batch.clear()
                    saved(writer.count)
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

    private suspend fun importGuide(
        input: InputStream,
        days: Int,
        saved: (Int) -> Unit,
    ): Int {
        val wanted = db.items().liveTvgIds().toHashSet()
        if (wanted.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val from = now - HOUR
        // Channels with catch-up keep their past programmes for as long as the server keeps the recordings.
        val archive = db.catchUp().all().associate { it.streamId to it.days }
        val pastFrom = HashMap<String, Long>()
        if (archive.isNotEmpty()) {
            for (link in db.items().liveLinks()) {
                val days = CatchUp.streamId(link.url)?.let(archive::get) ?: continue
                pastFrom.merge(link.tvgId, CatchUp.keepFrom(now, days), ::minOf)
            }
        }
        val until = now + days * DAY
        // Written beside the old guide and swapped in at the end, so searching and browsing never wait on it.
        val writer = GuideWriter(db)
        writer.begin()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            val batch = ArrayList<ProgrammeEntity>(BATCH)
            var count = 0
            for (p in XmltvParser.programmes(
                parser,
            ) { id, start, stop -> stop > (pastFrom[id] ?: from) && start < until && id in wanted }) {
                batch +=
                    ProgrammeEntity(channelId = p.channelId, start = p.start, stop = p.stop, title = p.title, description = p.description)
                count++
                if (batch.size == BATCH) {
                    writer.add(batch.toList())
                    batch.clear()
                    saved(count)
                }
            }
            writer.add(batch)
            return writer.commit()
        } finally {
            writer.discard() // a no-op after a commit; after a failure, the old guide stays
        }
    }

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
        const val SQL_VARIABLES = 900
        const val MAX_ACCOUNT_ANSWER = 64_000
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}

private const val TAG_SYNC = "Sync"
