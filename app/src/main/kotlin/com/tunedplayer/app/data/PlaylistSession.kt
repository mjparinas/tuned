package com.tunedplayer.app.data

import android.content.Context
import com.tunedplayer.app.container
import com.tunedplayer.app.data.db.GuideSearchIndex
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.data.db.offMain
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.work.Reminders
import com.tunedplayer.app.work.ScanWorker
import com.tunedplayer.app.work.SyncScheduler
import com.tunedplayer.app.work.TvHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val cache: PlaylistCache,
) {
    /** Programmes set to record point at this playlist's channels, so they go with it, as reminders do. */
    private val recordings get() = context.container.scheduledRecordings

    /**
     * Makes [login] the one in use and remembers it among the saved playlists. A different playlist's channels and
     * guide are cleared first, so the lists never mix two providers. The one left is copied aside, and one used lately
     * comes back from its copy at once ([PlaylistCache]); then everything is refreshed.
     */
    suspend fun useLogin(login: Credentials) {
        val previous = credentials.load()
        val added = credentials.saved().none { it.samePlaylist(login) }
        credentials.save(login)
        credentials.remember(login)
        var restored = false
        if (previous != null && !previous.samePlaylist(login)) {
            reminders.cancelAll()
            recordings.cancelAll()
            if (TvHome.isTv(context)) attempt("TvHome", "Couldn't clear the home screen") { TvHome(context).clear() }
            val before = settings.settings.first()
            withContext(Dispatchers.IO) {
                if (before.keepPlaylistCopies && before.lastPlaylistSync > 0) {
                    attempt(TAG, "Couldn't keep a copy of the playlist") {
                        cache.save(previous, PlaylistCache.Synced(before.lastPlaylistSync, before.lastGuideSync))
                    }.onFailure { cache.forget(previous) }
                }
                clearCache()
            }
            settings.update { it.copy(lastPlaylistSync = 0, lastGuideSync = 0) }
            if (before.keepPlaylistCopies) restored = restoreCopy(login)
        }
        settings.update { it.copy(lastSyncError = "") }
        account.clear()
        if (added) context.container.usage.track("playlist_added", "kind" to usageKind(login.kind))
        // From a copy the lists are already full, so the refresh runs in the background, not as a first download.
        scheduler.refreshNow(first = !restored)
        val s = settings.settings.first()
        scheduler.schedule(s.playlistRefreshHours, s.guideRefreshHours, s.unmeteredOnly)
    }

    /** Fills the emptied cache from [login]'s copy; true when it did. A copy that fails part way is emptied again. */
    private suspend fun restoreCopy(login: Credentials): Boolean {
        val synced =
            withContext(Dispatchers.IO) {
                attempt(TAG, "Couldn't restore the playlist's copy") { cache.restore(login) }
                    .onFailure {
                        cache.forget(login)
                        clearCache()
                    }.getOrNull()
            } ?: return false
        settings.update { it.copy(lastPlaylistSync = synced.playlist, lastGuideSync = synced.guide) }
        // The guide search index is rebuilt from the restored guide, in the background like any old cache.
        attempt(TAG, "Couldn't index the restored guide") { GuideSearchIndex.fillIfMissing(db) }
        return true
    }

    /** Empties what belongs to one playlist: channels, films, shows, the guide and its search index, catch-up, scans. */
    private suspend fun clearCache() {
        db.items().deleteAll()
        db.shows().deleteShowGroups()
        db.shows().deleteCovers()
        db.shows().deleteDetails()
        db.programmes().deleteAll()
        offMain { GuideSearchIndex.lock.withLock { GuideSearchIndex.reset(db.openHelper.writableDatabase) } }
        db.catchUp().deleteAll()
        db.channelScan().deleteAll()
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
        // After the switch, which keeps a copy of the playlist it leaves: a removed one keeps none.
        withContext(Dispatchers.IO) { cache.forget(login) }
        return false
    }

    /** Forgets the login, the cached playlist and guide, and stops background refreshes. */
    suspend fun signOut() {
        playlistFiles.deleteRecursively()
        scheduler.cancelAll()
        scanControl.stop()
        reminders.cancelAll()
        recordings.cancelAll()
        if (TvHome.isTv(context)) attempt("TvHome", "Couldn't clear the home screen") { TvHome(context).clear() }
        credentials.clear()
        cache.clear()
        account.clear()
        db.items().deleteAll()
        db.programmes().deleteAll()
        settings.update { it.copy(lastPlaylistSync = 0, lastGuideSync = 0, lastSyncError = "") }
        settings.lockAdult() // the PIN stays: it guards the device, not one login
    }
}

private const val TAG = "Playlists"

/** The playlist's kind in the usage stats' words (docs/decisions/admin.md); never its address. */
private fun usageKind(kind: Credentials.Kind) = when (kind) {
    Credentials.Kind.Xtream -> "xtream"
    Credentials.Kind.Link -> "link"
    Credentials.Kind.File -> "file"
    Credentials.Kind.Stalker -> "portal"
}
