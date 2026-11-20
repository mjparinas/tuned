package com.tunedplayer.app

import android.content.Context
import com.tunedplayer.app.data.AccountChannels
import com.tunedplayer.app.data.AccountRepository
import com.tunedplayer.app.data.AppReset
import com.tunedplayer.app.data.ChannelEditor
import com.tunedplayer.app.data.ChannelScanner
import com.tunedplayer.app.data.CloudSyncRepository
import com.tunedplayer.app.data.Connectivity
import com.tunedplayer.app.data.CredentialStore
import com.tunedplayer.app.data.EventsRepository
import com.tunedplayer.app.data.GuideFixRepository
import com.tunedplayer.app.data.IntroStore
import com.tunedplayer.app.data.MetadataRepository
import com.tunedplayer.app.data.Offline
import com.tunedplayer.app.data.OwnLists
import com.tunedplayer.app.data.PeopleRepository
import com.tunedplayer.app.data.PhoneSignIn
import com.tunedplayer.app.data.Picks
import com.tunedplayer.app.data.PlaylistCache
import com.tunedplayer.app.data.PlaylistSession
import com.tunedplayer.app.data.ProfileRepository
import com.tunedplayer.app.data.Recordings
import com.tunedplayer.app.data.SavedArtwork
import com.tunedplayer.app.data.ServerFlags
import com.tunedplayer.app.data.SettingsRepository
import com.tunedplayer.app.data.SharedChannels
import com.tunedplayer.app.data.StalkerLinks
import com.tunedplayer.app.data.SubtitleRepository
import com.tunedplayer.app.data.SyncCopyRepository
import com.tunedplayer.app.data.SyncRepository
import com.tunedplayer.app.data.TraktAccount
import com.tunedplayer.app.data.TuneRepository
import com.tunedplayer.app.data.TunedAccount
import com.tunedplayer.app.data.TvRelay
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.diagnostics.DeviceClock
import com.tunedplayer.app.diagnostics.UsageStats
import com.tunedplayer.app.diagnostics.loggingHandler
import com.tunedplayer.app.player.TvCasting
import com.tunedplayer.app.premium.DeviceSeatsClient
import com.tunedplayer.app.premium.Premium
import com.tunedplayer.app.updates.GitHubUpdates
import com.tunedplayer.app.work.OfflineWorker
import com.tunedplayer.app.work.Reminders
import com.tunedplayer.app.work.ScanWorker
import com.tunedplayer.app.work.ScheduledRecordings
import com.tunedplayer.app.work.SyncScheduler
import com.tunedplayer.app.work.TvHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything the app shares, built once and on first use (nothing heavy runs before the first frame). */
class AppContainer(
    private val context: Context,
) {
    /** A failure in background work is logged (Settings › Updates and advanced › Logs), never a crash. */
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
            .addNetworkInterceptor(DeviceClock.interceptor)
            .build()
    }

    /** The signed-in Stalker portal, and turning its channels into streams for the player, scans and casting. */
    val stalker by lazy { StalkerLinks(http, credentials::load) }
    val sync by lazy { SyncRepository(db, http, credentials::load, settings, context.noBackupFilesDir.resolve("sync"), stalker) }
    val tune by lazy { TuneRepository(db, settings) }
    val scheduler = SyncScheduler(context)
    val guideFixes by lazy { GuideFixRepository(db, sync, scheduler, credentials) }
    val updates by lazy { GitHubUpdates(context, http) }
    val syncCopy by lazy { SyncCopyRepository(credentials, settings, db, profiles, trakt, IntroStore(context)) }
    val tunedAccount by lazy { TunedAccount(context, http) { tvSignInUrl } }
    val cloudSync by lazy {
        CloudSyncRepository(
            context,
            tunedAccount,
            syncCopy,
            onSignedIn = { scheduler.refreshNow(first = true) },
            onGuideChanged = { scheduler.refreshNow(guideOnly = true) },
            onUsage = { usage.track(it) },
            onSwitch = { session.useLogin(it) },
            onRefresh = { scheduler.refreshNow() },
        )
    }

    /** The live channels and guide the website's Channels and guide page reads (docs/decisions/web.md). */
    val sharedChannels by lazy { SharedChannels(context, db, credentials::load, tunedAccount) }
    val accountChannels by lazy { AccountChannels(tunedAccount, sync, credentials::load) }
    val reminders by lazy { Reminders(context) }
    val events by lazy { EventsRepository(db, settings, reminders) }
    val offline by lazy { Offline(context, db, settings) { watching() || recordingOpen.value } }
    val recordings by lazy { Recordings(context) }
    val lists by lazy { OwnLists(db) }
    val channelEditor by lazy { ChannelEditor(db) }
    val picks by lazy { Picks(db, appScope) { TvHome.schedule(context) } }
    val connectivity by lazy { Connectivity(context, appScope) }

    /** TMDB is asked only while online and while the admin's `tmdb` switch is on; the device's cache answers either way. */
    private fun tmdbReachable() = connectivity.onlineNow() && ServerFlags.now.value.tmdb

    val metadata by lazy { MetadataRepository(http, BuildConfig.TMDB_API_KEY, db.metadata(), appScope, ::tmdbReachable) }
    val savedArtwork by lazy { SavedArtwork(context, db, metadata) }
    val trakt by lazy { TraktAccount(context, http) }
    val people by lazy { PeopleRepository(http, BuildConfig.TMDB_API_KEY, db.metadata(), ::tmdbReachable) }
    val profiles by lazy { ProfileRepository(db, settings) }
    val premium by lazy { Premium(context, appScope, DeviceSeatsClient.create(context, http)) { tunedAccount } }
    val subtitles by lazy { SubtitleRepository(http, settings, credentials, metadata, context.filesDir.resolve("subtitles")) }

    /** True while the player is open; downloads and channel scans hold back so only one stream is ever open. */
    val playerOpen = MutableStateFlow(false)

    /** True while a download holds its connection; set by [OfflineWorker] until its connection is closed. */
    val downloadOpen = MutableStateFlow(false)

    /** True while a scheduled recording holds its connection ([com.tunedplayer.app.work.RecordWorker]); downloads and scans wait for it. */
    val recordingOpen = MutableStateFlow(false)

    /** Programmes set to record from the guide. */
    val scheduledRecordings by lazy { ScheduledRecordings(context) }

    /** A TV playing Tuned's stream, which carries on while the app is in the background; downloads and scans wait for it too. */
    val tvCasting by lazy { TvCasting(context) { appScope.launch { offline.start() } } }

    /** Something is being watched, on the phone or on a TV: the stream comes first and downloads wait. */
    fun watching(): Boolean = playerOpen.value || tvCasting.active.value

    private val streamBusy: StateFlow<Boolean> by lazy {
        combine(playerOpen, tvCasting.active, downloadOpen, recordingOpen) { open, tv, down, rec -> open || tv || down || rec }
            .stateIn(appScope, SharingStarted.Eagerly, false)
    }
    val account by lazy { AccountRepository(http, credentials::load, settings, streamBusy) }
    val channelScan by lazy { ChannelScanner(context, db, http, credentials, settings, streamBusy, stalker::resolve) }
    val scanControl by lazy { ScanWorker.Control(context) }

    /** Adding, switching, removing and signing out of playlists. */
    val session by lazy {
        PlaylistSession(
            context,
            credentials,
            settings,
            db,
            reminders,
            scheduler,
            account,
            scanControl,
            playlistCopies,
        )
    }

    /** Recent playlists' channels and guide, kept so switching back is instant (docs/decisions/onboarding.md). */
    val playlistCopies by lazy {
        PlaylistCache(
            db,
            context.noBackupFilesDir.resolve(PlaylistCache.DIR),
            context.noBackupFilesDir.resolve("sync/playlist.fingerprint"),
        )
    }

    /** Signing out: the app back as it was installed. */
    val reset by lazy { AppReset(context, this) }

    /** Signing in on a TV with a phone (docs/decisions/onboarding.md): the relay, and taking what arrives. */
    val tvRelay by lazy { TvRelay(http, tvSignInUrl) }

    /** Where [tvRelay] lives; tests point it at a stand-in before first use. */
    var tvSignInUrl: String = BuildConfig.TV_SIGNIN_URL

    /** Anonymous usage stats for the admin (docs/decisions/admin.md), sent to the same site as [tvRelay]. */
    val usage by lazy { UsageStats(context, { http }, settings, appScope, { tvSignInUrl }) { ServerFlags.now.value.usageBatchMinutes } }
    val phoneSignIn by lazy { PhoneSignIn(credentials, syncCopy, session) { premium.unlocked.value } }
}
