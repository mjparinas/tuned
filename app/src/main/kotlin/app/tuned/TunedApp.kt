package app.tuned

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.CrashReporter
import app.tuned.diagnostics.launchSafely
import app.tuned.work.CloudSyncWorker
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
        CloudSyncWorker.watch(this)
        container.appScope.launchSafely("Profiles") { container.profiles.sync() }
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
                        // The store build asks Play what this account owns, in the background (docs/decisions/premium.md).
                        container.premium.refresh()
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
