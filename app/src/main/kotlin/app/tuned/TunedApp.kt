package app.tuned

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.tuned.data.BackupRepository
import app.tuned.data.CredentialStore
import app.tuned.data.MetadataRepository
import app.tuned.data.SettingsRepository
import app.tuned.data.SyncRepository
import app.tuned.data.TuneRepository
import app.tuned.data.db.TunedDatabase
import app.tuned.updates.GitHubUpdates
import app.tuned.work.Reminders
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything the app shares, built once and on first use (nothing heavy runs before the first frame). */
class AppContainer(
    private val context: Context,
) {
    val appScope = CoroutineScope(SupervisorJob())
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
    val metadata by lazy { MetadataRepository(http, settings) }

    /** Forgets the login, the cached playlist and guide, and stops background refreshes. */
    suspend fun signOut() {
        scheduler.cancelAll()
        reminders.cancelAll()
        credentials.clear()
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
        container = AppContainer(this)
        lockAdultWhenAway()
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

    /** Logos and posters: memory plus a 256 MB disk cache, through the same OkHttp client as everything else. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader
        .Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache {
            DiskCache
                .Builder()
                .directory(context.cacheDir.resolve("images"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }.crossfade(true)
        .build()
}

private const val ADULT_RELOCK_MS = 5 * 60_000L

val Context.container: AppContainer get() = (applicationContext as TunedApp).container
