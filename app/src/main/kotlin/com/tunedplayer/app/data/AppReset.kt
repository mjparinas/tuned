package com.tunedplayer.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import coil3.SingletonImageLoader
import com.tunedplayer.app.AppContainer
import com.tunedplayer.app.data.db.wipe
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.widget.Widgets
import com.tunedplayer.app.work.TvHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Signing out puts Tuned back as it was when installed (Melvin, 2026-11-19: "basically it should just reset the app to
 * a preinstalled state"): every playlist and login, the cached channels and guide, every setting, profile and PIN, and
 * the Trakt and Tuned accounts on this device. The one choice is the downloads: delete them (the default) or keep them.
 *
 * Not reset: Premium, which belongs to the Play account and the device seat, not to a playlist (it is asked again
 * afterwards), the crash reports and log in Settings › Updates and advanced, and the backup in the Tuned account, so
 * signing back in brings everything back.
 *
 * A reset that stops part way (the app is closed or crashes) finishes the next time the app starts: [PENDING] is written
 * before anything is removed and cleared only once every step succeeded, and every step is safe to run twice.
 */
class AppReset(
    private val context: Context,
    private val c: AppContainer,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** What a sign-out would ask about: how many downloads and recordings there are, and the space they take. */
    data class Saved(
        val count: Int,
        val bytes: Long,
    )

    suspend fun saved(): Saved = withContext(Dispatchers.IO) {
        val (downloads, downloadBytes) = c.offline.usage()
        val (recordings, recordingBytes) = c.recordings.usage()
        Saved(downloads + recordings, downloadBytes + recordingBytes)
    }

    /** Whether a reset was started and hasn't finished. */
    val pending: Boolean get() = prefs.contains(KEY_KEEP)

    /** Signs out and resets everything; [keepDownloads] keeps the finished downloads and the recordings. */
    suspend fun signOut(keepDownloads: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_KEEP, keepDownloads) }
        run(keepDownloads)
    }

    /**
     * At start: finishes a reset the app was closed in the middle of. The login is forgotten at once, before the first
     * screen is chosen, so the welcome screen shows rather than a half-emptied home screen.
     */
    fun resumeIfPending() {
        if (!pending) return
        c.credentials.clear()
        val keep = prefs.getBoolean(KEY_KEEP, false)
        c.appScope.launchSafely(TAG) { run(keep) }
    }

    private suspend fun run(keepDownloads: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            val steps =
                listOf(
                    // The login first, so nothing new can start: refreshes, scans and downloads all need it.
                    step("the login") { c.credentials.clear() },
                    step("the playlist files") { c.session.playlistFiles.deleteRecursively() },
                    step("the account status") { c.account.clear() },
                    step("Trakt") { c.trakt.disconnect() },
                    step("Tuned account") { c.cloudSync.disconnect() },
                    // A fresh install gets a new id, so the stats never tie the time before signing out to the time after.
                    step("the usage stats") { c.usage.forget() },
                    step("the saved flags") { ServerFlags.forget(context) },
                    // Anything mid-write finishes before the database is emptied under it.
                    step("background work") { stopAllWork() },
                    step("the TV home screen") { if (TvHome.isTv(context)) TvHome(context).clear() },
                    step("the downloads") { downloads(keepDownloads) },
                    // Channels, guide, favourites, history, lists, profiles and their PINs, in one transaction. Kept
                    // downloads keep their rows and the TMDB details saved for them, so their pages still look right.
                    step("the database") { c.db.wipe(if (keepDownloads) KEPT_WITH_DOWNLOADS else emptySet()) },
                    step("the settings") { c.settings.reset() },
                    step("the learned intros") { IntroStore(context).clear() },
                    step("the channel scan report") { c.channelScan.forgetReport() },
                    step("the files") { files() },
                    step("the pictures") { pictures(keepDownloads) },
                    step("the profiles") { c.profiles.sync() },
                    step("the home screen widgets") { Widgets.refresh(context) },
                )
            val failed = steps.filter { (name, step) -> attempt(TAG, "Couldn't reset $name") { step() }.isFailure }.map { it.first }
            if (failed.isEmpty()) {
                prefs.edit(commit = true) { clear() }
            } else {
                AppLog.w(TAG, "Sign-out finishes at the next start: ${failed.joinToString()}")
            }
            // Premium belongs to the Play account and the device seat, not the playlist: ask again rather than lose it.
            c.premium.refresh()
        }
    }

    private fun step(
        name: String,
        block: suspend () -> Unit,
    ) = name to block

    private suspend fun downloads(keep: Boolean) {
        if (keep) {
            c.offline.keepFinishedOnly()
        } else {
            c.offline.deleteFiles()
            c.recordings.deleteAll()
        }
    }

    /** Subtitles, the sync staging files, the copies of recent playlists and the cache (the live rewind buffer, update downloads). */
    private fun files() {
        context.filesDir.resolve(SUBTITLES).deleteRecursively()
        context.noBackupFilesDir.resolve(SYNC).deleteRecursively()
        context.noBackupFilesDir.resolve(PlaylistCache.DIR).deleteRecursively()
        context.cacheDir
            .listFiles()
            .orEmpty()
            .forEach { it.deleteRecursively() }
    }

    /** Kept downloads keep their artwork (see [SavedArtwork]), so their pages look the same offline. */
    private fun pictures(keepDownloads: Boolean) {
        val images = SingletonImageLoader.get(context)
        images.memoryCache?.clear()
        if (!keepDownloads) images.diskCache?.clear()
    }

    /** Cancels every background job (all of them belong to a playlist or an account) and waits for running ones to end. */
    private suspend fun stopAllWork() {
        val work = WorkManager.getInstance(context)
        c.scanControl.stop()
        work.cancelAllWork()
        withTimeoutOrNull(WORK_STOP_MS) {
            val running = WorkQuery.fromStates(WorkInfo.State.RUNNING)
            while (work.getWorkInfos(running).get().isNotEmpty()) delay(POLL_MS)
        } ?: AppLog.w(TAG, "Background work was still stopping when the reset went on")
    }

    private companion object {
        const val TAG = "SignOut"
        const val PREFS = "tuned.reset"
        const val KEY_KEEP = "pending_keep_downloads"
        const val SUBTITLES = "subtitles"
        const val SYNC = "sync"
        const val WORK_STOP_MS = 10_000L
        const val POLL_MS = 100L
        val KEPT_WITH_DOWNLOADS = setOf("downloads", "metadata", "tmdb_seasons")
    }
}
