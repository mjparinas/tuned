package com.tunedplayer.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.tunedplayer.app.data.ServerFlags
import com.tunedplayer.app.data.settleNewRegions
import com.tunedplayer.app.diagnostics.CrashReporter
import com.tunedplayer.app.diagnostics.FreezeWatch
import com.tunedplayer.app.diagnostics.Lighter
import com.tunedplayer.app.diagnostics.SpeedTrace
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.work.CloudSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunedApp :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Settings' Record a speed trace restarted the app to time this start; a file check otherwise.
        SpeedTrace.startIfAsked(this)
        CrashReporter.install(this)
        FreezeWatch.start(this)
        Lighter.load(this)
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
        SpeedTrace.mark("App built")
        // Background work waits until the first screen is up (docs/decisions/speed.md › Quiet start).
        container.quietStart.begin()
        // A sign-out the app was closed in the middle of finishes now, before the first screen is chosen.
        container.reset.resumeIfPending()
        container.appScope.launchSafely("Freeze") {
            withContext(Dispatchers.IO) { FreezeWatch.collectPastFreezes(this@TunedApp) }
            // After the past freezes are in, so Automatic sees them; then again whenever the setting changes.
            container.settings.settings
                .map { it.lighterMode }
                .distinctUntilChanged()
                .collect { mode -> withContext(Dispatchers.IO) { Lighter.refresh(this@TunedApp, mode) } }
        }
        lockAdultWhenAway()
        followForAdmin()
        CloudSyncWorker.watch(this)
        container.appScope.launchSafely("Profiles") { container.profiles.sync() }
        // Remember the countries already there, so ones a refresh adds can follow the rest.
        container.appScope.launchSafely("Regions") {
            container.quietStart.await()
            settleNewRegions(container.db, container.settings)
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
     * The admin's side (docs/decisions/admin.md): each time the app comes up, the usage stats note it and the flags are
     * read a few seconds later, after the first frame; each time it leaves the screen, waiting stats go up if due.
     */
    private fun followForAdmin() {
        container.usage.watch()
        var cold = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        container.usage.opened(cold)
                        cold = false
                        container.appScope.launchSafely("Flags") {
                            ServerFlags.loadSaved(this@TunedApp)
                            delay(FLAGS_DELAY_MS)
                            ServerFlags.refresh(this@TunedApp, container.http, container.tvSignInUrl, container.usage.idIfOn())
                        }
                    }

                    Lifecycle.Event.ON_STOP -> {
                        container.appScope.launchSafely("Usage") { container.usage.flush() }
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
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Lighter mode (weak TV boxes): pictures in half the memory, a smaller memory cache, and no fade-in.
        val lighter = Lighter.on.value
        return ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, if (lighter) LIGHTER_MEMORY_SHARE else MEMORY_SHARE).build() }
            .diskCache {
                DiskCache
                    .Builder()
                    .directory(context.filesDir.resolve("images"))
                    .maxSizePercent(IMAGE_CACHE_SHARE)
                    .minimumMaxSizeBytes(IMAGE_CACHE_MIN)
                    .maximumMaxSizeBytes(IMAGE_CACHE_MAX)
                    .build()
            }.crossfade(!lighter)
            .allowRgb565(lighter)
            .build()
    }
}

private const val ADULT_RELOCK_MS = 5 * 60_000L
private const val FLAGS_DELAY_MS = 3_000L
private const val MEMORY_SHARE = 0.2
private const val LIGHTER_MEMORY_SHARE = 0.1
private const val IMAGE_CACHE_SHARE = 0.05
private const val IMAGE_CACHE_MIN = 512L * 1024 * 1024
private const val IMAGE_CACHE_MAX = 2L * 1024 * 1024 * 1024

val Context.container: AppContainer get() = (applicationContext as TunedApp).container
