package app.tuned.data

import android.content.Context
import app.tuned.data.db.TunedDatabase
import app.tuned.diagnostics.attempt
import app.tuned.work.Reminders
import app.tuned.work.ScanWorker
import app.tuned.work.SyncScheduler
import app.tuned.work.TvHome
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URI

/** Adding, switching, removing and signing out of playlists: what changes when the playlist in use changes. */
class PlaylistSession(
    private val context: Context,
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val db: TunedDatabase,
    private val reminders: Reminders,
    private val scheduler: SyncScheduler,
    private val account: AccountRepository,
    private val scanControl: ScanWorker.Control,
) {
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
            if (TvHome.isTv(context)) attempt("TvHome", "Couldn't clear the home screen") { TvHome(context).clear() }
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

    /** Playlists picked from a file are copied here, one file each, so they still read after the original moves. */
    val playlistFiles: File get() = File(context.filesDir, "playlists")

    /**
     * Removes a saved playlist. Removing the one in use switches to the next saved one; removing the last one signs
     * out. Returns true when nothing is left, so the caller can show the welcome screen.
     */
    suspend fun removePlaylist(login: Credentials): Boolean {
        val active = credentials.load()?.samePlaylist(login) == true
        val next = credentials.saved().firstOrNull { !it.samePlaylist(login) }
        if (active && next == null) {
            signOut()
            return true
        }
        credentials.forget(login)
        if (login.kind == Credentials.Kind.File) {
            attempt("Playlists", "Couldn't delete the playlist file") { File(URI(login.playlistUrl)).delete() }
        }
        if (active && next != null) useLogin(next)
        return false
    }

    /** Forgets the login, the cached playlist and guide, and stops background refreshes. */
    suspend fun signOut() {
        playlistFiles.deleteRecursively()
        scheduler.cancelAll()
        scanControl.stop()
        reminders.cancelAll()
        if (TvHome.isTv(context)) attempt("TvHome", "Couldn't clear the home screen") { TvHome(context).clear() }
        credentials.clear()
        account.clear()
        db.items().deleteAll()
        db.programmes().deleteAll()
        settings.update { it.copy(lastPlaylistSync = 0, lastGuideSync = 0, lastSyncError = "") }
        settings.lockAdult() // the PIN stays: it guards the device, not one login
    }
}
