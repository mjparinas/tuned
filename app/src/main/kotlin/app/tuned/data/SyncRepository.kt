package app.tuned.data

import android.util.Xml
import androidx.room.withTransaction
import app.tuned.core.Episodes
import app.tuned.core.M3uParser
import app.tuned.core.PlaylistEntry
import app.tuned.core.Search
import app.tuned.core.StableId
import app.tuned.core.Streams
import app.tuned.core.XmltvParser
import app.tuned.core.XtreamUrls
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.ProgrammeEntity
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/**
 * Downloads the playlist and guide and swaps them into the database. Both stream straight from the network into
 * batched inserts inside one transaction, so memory stays flat and the lists keep showing the old data (WAL)
 * until the new data is complete. A failed refresh leaves the cache exactly as it was.
 */
class SyncRepository(
    private val db: TunedDatabase,
    private val http: OkHttpClient,
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
) {
    private val lock = Mutex()

    data class Result(
        val items: Int,
        val programmes: Int,
    )

    suspend fun refreshAll(): Result = lock.withLock {
        val items = refreshPlaylistLocked()
        val programmes = runCatching { refreshGuideLocked() }.getOrElse { 0 } // a missing guide isn't fatal
        Result(items, programmes)
    }

    suspend fun refreshGuide(): Int = lock.withLock { refreshGuideLocked() }

    private suspend fun refreshPlaylistLocked(): Int {
        val login = credentials.load() ?: throw IOException("Not signed in")
        val url = login.playlistUrl.ifBlank { XtreamUrls.playlist(login.server, login.username, login.password) }
        val count =
            try {
                download(url) { input -> importPlaylist(input) }
            } catch (e: IOException) {
                val message = XtreamUrls.redact(e.message ?: "Couldn't download the playlist", login.username, login.password)
                settings.update { it.copy(lastSyncError = message) }
                throw IOException(message, e)
            }
        settings.update { it.copy(lastPlaylistSync = System.currentTimeMillis(), lastSyncError = "") }
        return count
    }

    private suspend fun refreshGuideLocked(): Int {
        val login = credentials.load() ?: throw IOException("Not signed in")
        val url = login.guideUrl.ifBlank { XtreamUrls.guide(login.server, login.username, login.password) }
        val days = settings.settings.first().guideDays
        val count = download(url) { input -> importGuide(input, days) }
        settings.update { it.copy(lastGuideSync = System.currentTimeMillis()) }
        return count
    }

    private suspend fun <T> download(
        url: String,
        read: suspend (InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        val ua = settings.settings.first().userAgent
        val request =
            Request
                .Builder()
                .url(url)
                .apply { if (ua.isNotBlank()) header("User-Agent", ua) }
                .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
            read(Streams.maybeGunzip(response.body.byteStream()))
        }
    }

    private suspend fun importPlaylist(input: InputStream): Int {
        var count = 0
        db.withTransaction {
            db.items().deleteAll()
            val batch = ArrayList<ItemEntity>(BATCH)
            val seen = HashSet<Long>()
            for (entry in M3uParser.entries(input.bufferedReader())) {
                val row = entry.toRow()
                if (!seen.add(row.id)) continue // the same stream listed twice
                batch += row
                if (batch.size == BATCH) {
                    db.items().insertAll(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) db.items().insertAll(batch)
            count = seen.size
            // Throwing rolls the transaction back, so an empty answer never wipes a good cache.
            if (count == 0) throw IOException("The playlist was empty")
        }
        return count
    }

    private suspend fun importGuide(
        input: InputStream,
        days: Int,
    ): Int {
        val wanted = db.items().liveTvgIds().toHashSet()
        if (wanted.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val from = now - HOUR
        val until = now + days * DAY
        var count = 0
        db.withTransaction {
            db.programmes().deleteAll()
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            val batch = ArrayList<ProgrammeEntity>(BATCH)
            for (p in XmltvParser.programmes(parser) { id, start, stop -> stop > from && start < until && id in wanted }) {
                batch +=
                    ProgrammeEntity(channelId = p.channelId, start = p.start, stop = p.stop, title = p.title, description = p.description)
                count++
                if (batch.size == BATCH) {
                    db.programmes().insertAll(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) db.programmes().insertAll(batch)
        }
        return count
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
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
