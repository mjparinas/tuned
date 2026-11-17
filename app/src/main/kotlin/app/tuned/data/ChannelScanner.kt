package app.tuned.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tuned.BuildConfig
import app.tuned.core.ChannelScan
import app.tuned.core.ChannelScan.Failure
import app.tuned.core.Placeholders
import app.tuned.core.ScanEngine
import app.tuned.data.db.ChannelScanEntity
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

private val Context.scanStore by preferencesDataStore(name = "channel_scan")

/**
 * The channel scan on the device (see [ChannelScan] and [ScanEngine] for the rules). Each look at a channel opens its
 * stream, reads the first few kilobytes and closes it, one channel at a time. Nothing is looked at while the player is
 * open or a download runs ([busy]), and a look in progress is cut short the moment either starts, so Tuned never
 * holds two streams at once.
 */
class ChannelScanner(
    private val context: Context,
    private val db: TunedDatabase,
    private val http: OkHttpClient,
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val busy: StateFlow<Boolean>,
) {
    /** The last scan's outcome, for the report. */
    data class Summary(
        val finishedAt: Long,
        val outcome: ScanEngine.Outcome,
        val checked: Int,
        val working: Int,
        val failed: Int,
        val unsure: Int,
    )

    val summary: Flow<Summary?> =
        context.scanStore.data.map { p ->
            val at = p[K.at] ?: return@map null
            Summary(
                finishedAt = at,
                outcome = ScanEngine.Outcome.entries.firstOrNull { it.name == p[K.outcome] } ?: ScanEngine.Outcome.DONE,
                checked = p[K.checked] ?: 0,
                working = p[K.working] ?: 0,
                failed = p[K.failed] ?: 0,
                unsure = p[K.unsure] ?: 0,
            )
        }

    private val check by lazy { ServiceCheck(context, client, credentials) }

    private val client by lazy {
        http
            .newBuilder()
            .connectTimeout(ChannelScan.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(ChannelScan.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(ChannelScan.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun scan(progress: (done: Int, total: Int) -> Unit): ScanEngine.Result {
        val s = settings.settings.first()
        // Hidden-by-scan channels are scanned too (to bring them back); empty event slots never play, so they aren't.
        val hidden = (s.hiddenRegions + s.hiddenGroups).toList() + listOfNotNull(Placeholders.HIDDEN_KEY.takeIf { s.hidePlaceholders })
        val channels = db.channelScan().channels(hidden, s.showAdult)
        val agent = s.userAgent.ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" }
        val result = ScanEngine(DeviceIo(agent, progress)).run(channels, db.channelScan().knownGood())
        context.scanStore.edit { p ->
            p[K.at] = System.currentTimeMillis()
            p[K.outcome] = result.outcome.name
            p[K.checked] = result.checked
            p[K.working] = result.working
            p[K.failed] = result.failed
            p[K.unsure] = result.unsure
        }
        return result
    }

    /**
     * The player found that [itemId] won't play while the internet and the service work ([ChannelScan.blameOnPlay]):
     * hide it now, unless the person chose to keep it. A scan that finds it working brings it back.
     */
    suspend fun hideAfterPlayFailure(itemId: Long) {
        val record = ChannelScan.afterPlayFailure(db.channelScan().get(itemId)?.toRecord(), System.currentTimeMillis())
        db.channelScan().save(record.toEntity(itemId))
    }

    /** Is it the channel, or the internet or the service? See [ServiceCheck]. */
    suspend fun channelToBlame(anotherChannelPlayed: Boolean): Boolean {
        if (!anotherChannelPlayed || !check.internetUp()) return false
        val agent = settings.settings.first().userAgent.ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" }
        return ChannelScan.blameOnPlay(true, check.server(agent), anotherChannelPlayed = true)
    }

    /** "Show again" in the report: the channel comes back and scans never hide it again. */
    suspend fun keep(itemId: Long) {
        val record = ChannelScan.keep(db.channelScan().get(itemId)?.toRecord())
        db.channelScan().save(record.toEntity(itemId))
    }

    private inner class DeviceIo(
        private val agent: String,
        private val onProgress: (Int, Int) -> Unit,
    ) : ScanEngine.Io {
        override fun now(): Long = System.currentTimeMillis()

        override suspend fun probe(id: Long): Failure? {
            val url = db.items().get(id)?.url ?: return Failure.HTTP
            while (true) {
                busy.first { !it }
                val look = interruptible { calls -> look(url, calls) }
                if (look != null) return look.failure
                // The player opened or a download started mid-look: wait, then look again.
            }
        }

        override suspend fun internetUp(): Boolean = check.internetUp()

        override suspend fun server(): ScanEngine.Server {
            busy.first { !it }
            return check.server(agent)
        }

        override suspend fun pause(ms: Long) = delay(ms)

        override suspend fun recordOk(id: Long) {
            val record = ChannelScan.afterOk(db.channelScan().get(id)?.toRecord(), now())
            db.channelScan().save(record.toEntity(id))
        }

        override suspend fun recordFailure(
            id: Long,
            failure: Failure,
        ) {
            val record = ChannelScan.afterFailure(db.channelScan().get(id)?.toRecord(), failure, now())
            db.channelScan().save(record.toEntity(id))
        }

        override fun progress(
            done: Int,
            total: Int,
        ) = onProgress(done, total)

        /** Runs [block] with a call that is cancelled if the player opens; null when that happened. */
        private suspend fun <T> interruptible(block: (Call.Factory) -> T): T? = coroutineScope {
            var cut = false
            val calls = mutableListOf<Call>()
            val factory = Call.Factory { req -> client.newCall(req).also { synchronized(calls) { calls += it } } }
            val watcher =
                launch {
                    busy.first { it }
                    cut = true
                    synchronized(calls) { calls.forEach(Call::cancel) }
                }
            val result = withContext(Dispatchers.IO) { block(factory) }
            watcher.cancel()
            if (cut) null else result
        }

        private fun request(url: String) = Request
            .Builder()
            .url(url)
            .header("User-Agent", agent)
            .build()

        private inner class Look(
            val failure: Failure?,
        )

        /** Reads the start of a stream; for an HLS playlist, the start of what it points at (two levels at most). */
        private fun look(
            url: String,
            calls: Call.Factory,
            depth: Int = 0,
        ): Look {
            var code: Int? = null
            var bytes = ByteArray(0)
            var timedOut = false
            var failed = false
            try {
                calls.newCall(request(url)).execute().use { r ->
                    code = r.code
                    if (r.isSuccessful) bytes = read(r.body.byteStream())
                }
            } catch (_: InterruptedIOException) {
                timedOut = true
            } catch (_: IOException) {
                failed = true
            }
            val failure = ChannelScan.classify(code, bytes, timedOut, failed)
            if (failure == null && depth < MAX_DEPTH && ChannelScan.isPlaylist(bytes)) {
                val next = ChannelScan.firstUri(bytes.decodeToString(), url) ?: return Look(Failure.NO_DATA)
                return look(next, calls, depth + 1)
            }
            return Look(failure)
        }

        private fun read(input: java.io.InputStream): ByteArray {
            val buffer = ByteArray(ChannelScan.SAMPLE_BYTES)
            var total = 0
            try {
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
            } catch (_: InterruptedIOException) {
                // The time ran out part-way: what arrived still counts.
            }
            return buffer.copyOf(total)
        }
    }

    private fun ChannelScanEntity.toRecord() = ChannelScan.Record(
        failures = failures,
        firstFailAt = firstFailAt,
        lastFailAt = lastFailAt,
        lastOkAt = lastOkAt,
        reason = reason?.let { r -> Failure.entries.firstOrNull { it.name == r } },
        kept = kept,
    )

    private fun ChannelScan.Record.toEntity(itemId: Long) = ChannelScanEntity(
        itemId = itemId,
        failures = failures,
        firstFailAt = firstFailAt,
        lastFailAt = lastFailAt,
        lastOkAt = lastOkAt,
        reason = reason?.name,
        kept = kept,
        hidden = hidden,
    )

    private object K {
        val at = longPreferencesKey("finished_at")
        val outcome = stringPreferencesKey("outcome")
        val checked = intPreferencesKey("checked")
        val working = intPreferencesKey("working")
        val failed = intPreferencesKey("failed")
        val unsure = intPreferencesKey("unsure")
    }

    private companion object {
        const val MAX_DEPTH = 2
    }
}
