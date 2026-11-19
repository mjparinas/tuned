package app.tuned.diagnostics

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import app.tuned.BuildConfig
import app.tuned.core.UsageRules
import app.tuned.data.SettingsRepository
import app.tuned.data.UsageChoice
import app.tuned.work.TvHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Anonymous usage stats (Melvin, 2026-11-19: "Yes, like Liftsmith"; docs/decisions/admin.md). Events from the
 * allow-list ([UsageRules.EVENTS]) wait in a small file on the device and go to `POST /api/collect` in batches, at most
 * every [app.tuned.core.RemoteFlags.usageBatchMinutes] (an hour unless the admin changes it), when the app leaves the
 * screen. An event carries only its name, the time and a few words from the allow-list (a section, a kind, yes or no);
 * never a channel, title, playlist, server, login or anything typed.
 *
 * On by default outside the EU, EEA, UK and Switzerland ([UsageRules.onByDefault]), switched in Settings › Updates and advanced › Privacy.
 * Switching off deletes what hasn't been sent and the install id; so does signing out ([app.tuned.data.AppReset]).
 * Every failure is logged and dropped: nothing here may slow or break the app.
 */
class UsageStats(
    private val context: Context,
    private val http: () -> OkHttpClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val base: () -> String,
    private val batchMinutes: () -> Int,
) {
    private val dir = context.noBackupFilesDir.resolve(DIR)
    private val lock = Mutex()
    private val queue = ArrayDeque<Event>()
    private var loaded = false

    /** Whether this device sends them: the person's choice, or its region while left on Automatic. */
    fun decide(choice: UsageChoice): Boolean = when (choice) {
        UsageChoice.ON -> true
        UsageChoice.OFF -> false
        UsageChoice.AUTO -> onHere(context)
    }

    suspend fun isOn(): Boolean = decide(settings.settings.first().usageStats)

    /** Queues [name] with [props] (strings, numbers or yes and no, from the server's allow-list). */
    fun track(
        name: String,
        vararg props: Pair<String, Any>,
    ) = enqueue(name, props.toMap())

    private fun enqueue(
        name: String,
        props: Map<String, Any>,
    ) {
        if (name !in UsageRules.EVENTS) {
            AppLog.w(TAG, "Not on the allow-list: $name")
            return
        }
        val event = Event(name, System.currentTimeMillis(), JsonObject(props.mapValues { (_, v) -> primitive(v) }))
        scope.launchSafely(TAG) {
            if (!isOn()) return@launchSafely
            lock.withLock {
                withContext(Dispatchers.IO) {
                    load()
                    queue.addLast(event)
                    while (queue.size > UsageRules.MAX_QUEUED) queue.removeFirst()
                    save()
                }
            }
        }
    }

    /** Like [track], but only the first time for this install (the first play, say). */
    fun trackOnce(
        name: String,
        vararg props: Pair<String, Any>,
    ) {
        scope.launchSafely(TAG) {
            if (!isOn()) return@launchSafely
            val fresh =
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        dir.mkdirs()
                        dir.resolve("once-$name").createNewFile()
                    }
                }
            if (fresh) track(name, *props)
        }
    }

    /** At each start: what happened since the last one (an update, a crash or a freeze), then the open itself. */
    fun opened(cold: Boolean) {
        if (cold) {
            scope.launchSafely(TAG) {
                if (!isOn()) return@launchSafely
                withContext(Dispatchers.IO) { sinceLastStart() }.forEach { (name, props) -> enqueue(name, props) }
            }
        }
        track("app_open", "cold" to cold)
    }

    /** Uploads one batch when it's due ([force] sends what's waiting now). Keeps the queue if the upload fails. */
    suspend fun flush(force: Boolean = false) {
        if (!isOn()) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                load()
                val now = System.currentTimeMillis()
                val due = (force && queue.isNotEmpty()) || UsageRules.dueToSend(queue.size, lastSent(), now, batchMinutes())
                val site = base().trim().trimEnd('/')
                if (!due || site.isEmpty()) return@withContext
                val batch = queue.take(UsageRules.BATCH)
                val body = json.encodeToString(Batch(installId(), device(context), batch, test = BuildConfig.DEBUG))
                val sent =
                    attempt(TAG, "Couldn't send usage stats") {
                        val request =
                            Request
                                .Builder()
                                .url("$site/api/collect")
                                .post(body.toRequestBody(JSON))
                                .build()
                        http().newCall(request).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                if (sent) {
                    repeat(batch.size) { queue.removeFirst() }
                    save()
                    dir.resolve(SENT).writeText(now.toString())
                }
            }
        }
    }

    /** The install id while the stats are on, else null (so the flags and reports can leave it out). */
    suspend fun idIfOn(): String? = if (isOn()) lock.withLock { withContext(Dispatchers.IO) { installId() } } else null

    /** Deletes the queue and the install id: switching off and signing out. The next install id is new. */
    suspend fun forget() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                queue.clear()
                loaded = true
                dir.deleteRecursively()
            }
        }
    }

    /** Forgets everything whenever the stats end up off, whether switched off or left on Automatic in a region they start off in. */
    fun watch() {
        scope.launchSafely(TAG) {
            settings.settings
                .map { decide(it.usageStats) }
                .distinctUntilChanged()
                .collect { on -> if (!on) forget() }
        }
    }

    private fun installId(): String {
        val file = dir.resolve(INSTALL)
        val saved = attempt(TAG, "Couldn't read the install id") { if (file.exists()) file.readText().trim() else null }.getOrNull()
        if (saved != null && UsageRules.isInstallId(saved)) return saved
        val id = UsageRules.newInstallId()
        dir.mkdirs()
        file.writeText(id)
        return id
    }

    private fun lastSent(): Long = attempt(TAG, "Couldn't read when stats were sent") {
        dir
            .resolve(SENT)
            .takeIf(File::exists)
            ?.readText()
            ?.trim()
            ?.toLongOrNull()
    }.getOrNull() ?: 0L

    private fun load() {
        if (loaded) return
        loaded = true
        val file = dir.resolve(QUEUE)
        if (!file.exists()) return
        attempt(TAG, "Couldn't read the usage queue") { queue.addAll(json.decodeFromString<List<Event>>(file.readText())) }
    }

    private fun save() {
        dir.mkdirs()
        dir.resolve(QUEUE).writeText(json.encodeToString(queue.toList()))
    }

    /** An update since the last start (from the version it came from), and crashes and freezes saved since then. */
    private fun sinceLastStart(): List<Pair<String, Map<String, Any>>> {
        val out = mutableListOf<Pair<String, Map<String, Any>>>()
        val versionFile = dir.resolve(VERSION)
        val previous = attempt(TAG, "Couldn't read the last version") { versionFile.takeIf(File::exists)?.readText()?.trim() }.getOrNull()
        if (!previous.isNullOrEmpty() && previous != BuildConfig.VERSION_NAME) out += "update_installed" to mapOf("from" to previous)
        val seenFile = dir.resolve(CRASHES_SEEN)
        val seen =
            attempt(
                TAG,
                "Couldn't read the crashes seen",
            ) {
                seenFile
                    .takeIf(File::exists)
                    ?.readText()
                    ?.trim()
                    ?.toLongOrNull()
            }.getOrNull()
        val crashes = CrashReporter.savedSince(context, seen ?: System.currentTimeMillis())
        crashes.forEach { (kind, _) -> out += "crash" to mapOf("kind" to kind) }
        dir.mkdirs()
        versionFile.writeText(BuildConfig.VERSION_NAME)
        seenFile.writeText((crashes.maxOfOrNull { it.second } ?: seen ?: System.currentTimeMillis()).toString())
        return out
    }

    @Serializable
    internal data class Event(
        val name: String,
        val at: Long,
        val props: JsonObject = JsonObject(emptyMap()),
    )

    /** About the device: the server's `INSTALL_FIELDS`. No name, account, address or Android id. */
    @Serializable
    internal data class Device(
        val build: String,
        val version: String,
        val versionCode: Int,
        val android: String,
        val sdk: Int,
        val maker: String,
        val model: String,
        val kind: String,
        val language: String,
        val timeZone: String,
    )

    @Serializable
    private data class Batch(
        val install: String,
        val device: Device,
        val events: List<Event>,
        val test: Boolean = false,
    )

    companion object {
        private const val TAG = "Usage"
        private const val DIR = "usage"
        private const val QUEUE = "queue.json"
        private const val INSTALL = "install"
        private const val SENT = "sent"
        private const val VERSION = "version"
        private const val CRASHES_SEEN = "crashes"
        private const val TABLET_DP = 600
        private val JSON = "application/json".toMediaType()
        internal val json = Json { ignoreUnknownKeys = true }

        private fun primitive(value: Any): JsonPrimitive = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

        /** Where the stats start on: no sign from the SIM, the network, the language or the time zone points to Europe. */
        fun onHere(context: Context): Boolean {
            val phone = context.getSystemService(TelephonyManager::class.java)
            val countries =
                listOf(
                    attempt(TAG, "Couldn't read the SIM country") { phone?.simCountryIso }.getOrNull(),
                    attempt(TAG, "Couldn't read the network country") { phone?.networkCountryIso }.getOrNull(),
                    Locale.getDefault().country,
                )
            return UsageRules.onByDefault(countries, TimeZone.getDefault().id)
        }

        internal fun device(context: Context): Device {
            val kind =
                when {
                    TvHome.isTv(context) -> "tv"
                    context.resources.configuration.smallestScreenWidthDp >= TABLET_DP -> "tablet"
                    else -> "phone"
                }
            return Device(
                build = if (BuildConfig.GITHUB_UPDATES) "github" else "play",
                version = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                android = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                maker = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                kind = kind,
                language = Locale.getDefault().toLanguageTag(),
                timeZone = TimeZone.getDefault().id,
            )
        }
    }
}
