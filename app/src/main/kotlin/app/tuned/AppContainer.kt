package app.tuned

import android.content.Context
import app.tuned.data.AccountRepository
import app.tuned.data.AppReset
import app.tuned.data.ChannelEditor
import app.tuned.data.ChannelScanner
import app.tuned.data.CloudSyncRepository
import app.tuned.data.Connectivity
import app.tuned.data.CredentialStore
import app.tuned.data.EventsRepository
import app.tuned.data.GuideFixRepository
import app.tuned.data.IntroStore
import app.tuned.data.MetadataRepository
import app.tuned.data.Offline
import app.tuned.data.OwnLists
import app.tuned.data.PeopleRepository
import app.tuned.data.PhoneSignIn
import app.tuned.data.Picks
import app.tuned.data.PlaylistSession
import app.tuned.data.ProfileRepository
import app.tuned.data.Recordings
import app.tuned.data.SavedArtwork
import app.tuned.data.SettingsRepository
import app.tuned.data.StalkerLinks
import app.tuned.data.SubtitleRepository
import app.tuned.data.SyncCopyRepository
import app.tuned.data.SyncRepository
import app.tuned.data.TraktAccount
import app.tuned.data.TuneRepository
import app.tuned.data.TunedAccount
import app.tuned.data.TvRelay
import app.tuned.data.db.TunedDatabase
import app.tuned.diagnostics.DeviceClock
import app.tuned.diagnostics.loggingHandler
import app.tuned.player.TvCasting
import app.tuned.premium.DeviceSeatsClient
import app.tuned.premium.Premium
import app.tuned.updates.GitHubUpdates
import app.tuned.work.OfflineWorker
import app.tuned.work.Reminders
import app.tuned.work.ScanWorker
import app.tuned.work.SyncScheduler
import app.tuned.work.TvHome
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
    val cloudSync by lazy {
        CloudSyncRepository(
            context,
            TunedAccount(context, http) { tvSignInUrl },
            syncCopy,
            onSignedIn = { scheduler.refreshNow(first = true) },
            onGuideChanged = { scheduler.refreshNow(guideOnly = true) },
        )
    }
    val reminders by lazy { Reminders(context) }
    val events by lazy { EventsRepository(db, settings, reminders) }
    val offline by lazy { Offline(context, db, settings, ::watching) }
    val recordings by lazy { Recordings(context) }
    val lists by lazy { OwnLists(db) }
    val channelEditor by lazy { ChannelEditor(db) }
    val picks by lazy { Picks(db, appScope) { TvHome.schedule(context) } }
    val connectivity by lazy { Connectivity(context, appScope) }
    val metadata by lazy { MetadataRepository(http, BuildConfig.TMDB_API_KEY, db.metadata(), appScope, connectivity::onlineNow) }
    val savedArtwork by lazy { SavedArtwork(context, db, metadata) }
    val trakt by lazy { TraktAccount(context, http) }
    val people by lazy { PeopleRepository(http, BuildConfig.TMDB_API_KEY, db.metadata(), connectivity::onlineNow) }
    val profiles by lazy { ProfileRepository(db, settings) }
    val premium by lazy { Premium(context, appScope, DeviceSeatsClient.create(context, http)) }
    val subtitles by lazy { SubtitleRepository(http, settings, credentials, metadata, context.filesDir.resolve("subtitles")) }

    /** True while the player is open; downloads and channel scans hold back so only one stream is ever open. */
    val playerOpen = MutableStateFlow(false)

    /** True while a download holds its connection; set by [OfflineWorker] until its connection is closed. */
    val downloadOpen = MutableStateFlow(false)

    /** A TV playing Tuned's stream, which carries on while the app is in the background; downloads and scans wait for it too. */
    val tvCasting by lazy { TvCasting(context) { appScope.launch { offline.start() } } }

    /** Something is being watched, on the phone or on a TV: the stream comes first and downloads wait. */
    fun watching(): Boolean = playerOpen.value || tvCasting.active.value

    private val streamBusy: StateFlow<Boolean> by lazy {
        combine(playerOpen, tvCasting.active, downloadOpen) { open, tv, down -> open || tv || down }
            .stateIn(appScope, SharingStarted.Eagerly, false)
    }
    val account by lazy { AccountRepository(http, credentials, settings, streamBusy) }
    val channelScan by lazy { ChannelScanner(context, db, http, credentials, settings, streamBusy, stalker::resolve) }
    val scanControl by lazy { ScanWorker.Control(context) }

    /** Adding, switching, removing and signing out of playlists. */
    val session by lazy { PlaylistSession(context, credentials, settings, db, reminders, scheduler, account, scanControl) }

    /** Signing out: the app back as it was installed. */
    val reset by lazy { AppReset(context, this) }

    /** Signing in on a TV with a phone (docs/decisions/onboarding.md): the relay, and taking what arrives. */
    val tvRelay by lazy { TvRelay(http, tvSignInUrl) }

    /** Where [tvRelay] lives; tests point it at a stand-in before first use. */
    var tvSignInUrl: String = BuildConfig.TV_SIGNIN_URL
    val phoneSignIn by lazy { PhoneSignIn(credentials, syncCopy, session) { premium.unlocked.value } }
}
