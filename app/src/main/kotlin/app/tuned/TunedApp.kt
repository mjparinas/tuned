package app.tuned

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.tuned.data.AccountRepository
import app.tuned.data.BackupRepository
import app.tuned.data.ChannelScanner
import app.tuned.data.Connectivity
import app.tuned.data.CredentialStore
import app.tuned.data.Credentials
import app.tuned.data.MetadataRepository
import app.tuned.data.Offline
import app.tuned.data.Picks
import app.tuned.data.SavedArtwork
import app.tuned.data.SettingsRepository
import app.tuned.data.SyncRepository
import app.tuned.data.TuneRepository
import app.tuned.data.db.TunedDatabase
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.CrashReporter
import app.tuned.diagnostics.loggingHandler
import app.tuned.updates.GitHubUpdates
import app.tuned.work.OfflineWorker
import app.tuned.work.Reminders
import app.tuned.work.ScanWorker
import app.tuned.work.SyncScheduler
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything the app shares, built once and on first use (nothing heavy runs before the first frame). */
class AppContainer(
    private val context: Context,
) {
    /** A failure in background work is logged (Settings › Developer › Logs), never a crash. */
    val appScope = CoroutineScope(SupervisorJob() + loggingHandler("App"))
    val settings = SettingsRepository(context)
    val credentials = CredentialStore(context)
    val db by lazy { TunedDatabase.create(context) }
    val http: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    val sync by lazy { SyncRepository(db, http, credentials, settings, context.noBackupFilesDir.resolve("sync")) }
    val tune by lazy { TuneRepository(db, settings) }
    val scheduler = SyncScheduler(context)
    val updates by lazy { GitHubUpdates(context, http) }
    val backup by lazy { BackupRepository(credentials, settings, db) }
    val reminders by lazy { Reminders(context) }
    val offline by lazy { Offline(context, db, settings) }
    val picks by lazy { Picks(db, appScope) }
    val connectivity by lazy { Connectivity(context, appScope) }
    val metadata by lazy { MetadataRepository(http, settings, db.metadata(), appScope, connectivity::onlineNow) }
    val savedArtwork by lazy { SavedArtwork(context, db, metadata) }

    /** True while the player is open; downloads and channel scans hold back so only one stream is ever open. */
    val playerOpen = MutableStateFlow(false)
    private val streamBusy: StateFlow<Boolean> by lazy {
        val downloading =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkFlow(OfflineWorker.NAME)
                .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        combine(playerOpen, downloading) { open, down -> open || down }.stateIn(appScope, SharingStarted.Eagerly, false)
    }
    val account by lazy { AccountRepository(http, credentials, settings, playerOpen) }
    val channelScan by lazy { ChannelScanner(context, db, http, credentials, settings, streamBusy) }
    val scanControl by lazy { ScanWorker.Control(context) }

    /**
     * Makes [login] the one in use and remembers it among the saved playlists. A different playlist's channels and
     * guide are cleared first, so the lists never mix two providers, then everything is downloaded afresh.
     */
    suspend fun useLogin(login: Credentials) {
        val previous = credentials.load()
        credentials.save(login)
        credentials.remember(login)
        if (previous != null && !previous.samePlaylist(login)) {
            reminders.cancelAll()
            db.items().deleteAll()
            db.shows().deleteShowGroups()
            db.programmes().deleteAll()
            db.catchUp().deleteAll()
            db.channelScan().deleteAll()
            settings.update { it.copy(lastPlaylistSync = 0, lastGuideSync = 0) }
        }
        settings.update { it.copy(lastSyncError = "") }
        account.clear()
        scheduler.refreshNow(first = true)
        val s = settings.settings.first()
        scheduler.schedule(s.playlistRefreshHours, s.guideRefreshHours, s.unmeteredOnly)
    }

    /** Forgets the login, the cached playlist and guide, and stops background refreshes. */
    suspend fun signOut() {
        scheduler.cancelAll()
        scanControl.stop()
        reminders.cancelAll()
        credentials.clear()
        account.clear()
        db.items().deleteAll()
        db.programmes().deleteAll()
        settings.update { it.copy(lastPlaylistSync = 0, lastGuideSync = 0, lastSyncError = "") }
        settings.lockAdult() // the PIN stays: it guards the device, not one login
    }
}

class TunedApp :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Debug builds log any disk or network work on the main thread, so a slip that would stutter shows up early.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }
        container = AppContainer(this)
        lockAdultWhenAway()
        container.appScope.launch {
            container.settings.settings
                .map { it.developerMode }
                .distinctUntilChanged()
                .collect { AppLog.verbose = it }
        }
        // Re-plan background refreshes whenever their settings change (not on every launch).
        container.appScope.launch {
            container.settings.settings
                .map { Triple(it.playlistRefreshHours, it.guideRefreshHours, it.unmeteredOnly) }
                .distinctUntilChanged()
                .drop(1)
                .collect { (playlist, guide, unmetered) ->
                    if (container.credentials.load() != null) container.scheduler.schedule(playlist, guide, unmetered)
                }
        }
    }

    /** Adult categories lock again five minutes after the app leaves the screen (a PIN unlock is for one sitting). */
    private fun lockAdultWhenAway() {
        var pending: Job? = null
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        pending =
                            container.appScope.launch {
                                delay(ADULT_RELOCK_MS)
                                container.settings.lockAdult()
                            }
                    }

                    Lifecycle.Event.ON_START -> {
                        pending?.cancel()
                    }

                    else -> {}
                }
            },
        )
    }

    /**
     * Logos, posters, backdrops and cast photos: memory plus a disk cache in app storage (not the cache folder, which
     * Android empties when space runs low), so artwork downloads once and then loads from the device, online or not.
     * Coil reads its disk cache before the network and ignores servers' cache headers, so a picture seen once is
     * never fetched again until it's the least recently used one over the limit: 5% of the storage, 512 MB to 2 GB.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader
        .Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache {
            DiskCache
                .Builder()
                .directory(context.filesDir.resolve("images"))
                .maxSizePercent(IMAGE_CACHE_SHARE)
                .minimumMaxSizeBytes(IMAGE_CACHE_MIN)
                .maximumMaxSizeBytes(IMAGE_CACHE_MAX)
                .build()
        }.crossfade(true)
        .build()
}

private const val ADULT_RELOCK_MS = 5 * 60_000L
private const val IMAGE_CACHE_SHARE = 0.05
private const val IMAGE_CACHE_MIN = 512L * 1024 * 1024
private const val IMAGE_CACHE_MAX = 2L * 1024 * 1024 * 1024

val Context.container: AppContainer get() = (applicationContext as TunedApp).container
