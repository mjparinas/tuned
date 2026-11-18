package app.tuned

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.tuned.data.AccountRepository
import app.tuned.data.ChannelScanner
import app.tuned.data.CloudSyncRepository
import app.tuned.data.Connectivity
import app.tuned.data.CredentialStore
import app.tuned.data.EventsRepository
import app.tuned.data.GoogleDrive
import app.tuned.data.GuideFixRepository
import app.tuned.data.MetadataRepository
import app.tuned.data.Offline
import app.tuned.data.PeopleRepository
import app.tuned.data.Picks
import app.tuned.data.PlaylistSession
import app.tuned.data.ProfileRepository
import app.tuned.data.Recordings
import app.tuned.data.SavedArtwork
import app.tuned.data.SettingsRepository
import app.tuned.data.SubtitleRepository
import app.tuned.data.SyncCopyRepository
import app.tuned.data.SyncRepository
import app.tuned.data.TraktAccount
import app.tuned.data.TuneRepository
import app.tuned.data.db.TunedDatabase
import app.tuned.diagnostics.loggingHandler
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
            .build()
    }
    val sync by lazy { SyncRepository(db, http, credentials, settings, context.noBackupFilesDir.resolve("sync")) }
    val tune by lazy { TuneRepository(db, settings) }
    val scheduler = SyncScheduler(context)
    val guideFixes by lazy { GuideFixRepository(db, sync, scheduler, credentials) }
    val updates by lazy { GitHubUpdates(context, http) }
    val syncCopy by lazy { SyncCopyRepository(credentials, settings, db) }
    val cloudSync by lazy {
        CloudSyncRepository(context, GoogleDrive(context, http), syncCopy) { scheduler.refreshNow(first = true) }
    }
    val reminders by lazy { Reminders(context) }
    val events by lazy { EventsRepository(db, settings, reminders) }
    val offline by lazy { Offline(context, db, settings) }
    val recordings by lazy { Recordings(context) }
    val picks by lazy { Picks(db, appScope) { TvHome.schedule(context) } }
    val connectivity by lazy { Connectivity(context, appScope) }
    val metadata by lazy { MetadataRepository(http, BuildConfig.TMDB_API_KEY, db.metadata(), appScope, connectivity::onlineNow) }
    val savedArtwork by lazy { SavedArtwork(context, db, metadata) }
    val trakt by lazy { TraktAccount(context, http) }
    val people by lazy { PeopleRepository(http, BuildConfig.TMDB_API_KEY, db.metadata(), connectivity::onlineNow) }
    val profiles by lazy { ProfileRepository(db, settings) }
    val premium by lazy { Premium(context, appScope) }
    val subtitles by lazy { SubtitleRepository(http, settings, credentials, metadata, context.filesDir.resolve("subtitles")) }

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

    /** Adding, switching, removing and signing out of playlists. */
    val session by lazy { PlaylistSession(context, credentials, settings, db, reminders, scheduler, account, scanControl) }
}
