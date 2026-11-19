package app.tuned.data

import android.content.Context
import androidx.core.content.edit
import app.tuned.core.CloudSync
import app.tuned.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The backup in the person's Tuned account (docs/decisions/accounts.md), which keeps a phone and a TV the same: the saved
 * playlists, settings, profiles, favourites and the rest ([SyncCopyRepository.syncCopy]). It replaced sync through Google
 * Drive (Melvin, 2026-11-19: "i want to completely deprecate google drive sync"). Each run takes the other device's copy
 * or sends this one's ([CloudSync.decide]); everything works offline and catches up on the next run.
 */
class CloudSyncRepository(
    context: Context,
    /** The Tuned account, which holds the backup. */
    val account: TunedAccount,
    private val syncCopy: SyncCopyRepository,
    /** A pulled copy signed this device in: start its first download. */
    private val onSignedIn: () -> Unit,
    /** A pulled copy changed the guide fixes: read the guide again. */
    private val onGuideChanged: () -> Unit = {},
) {
    enum class Problem { NONE, OFFLINE }

    /** How restoring from the welcome screen ended ([restore]). */
    enum class Restore {
        /** A playlist came back, with everything else: open the app. */
        RESTORED,

        /** The account has no backup yet. */
        NOTHING_SAVED,

        /** The backup came back, but held no playlist this device can open (only files, say). */
        NO_PLAYLIST,

        OFFLINE,
    }

    data class Status(
        val lastSyncAt: Long,
        val problem: Problem,
    )

    private val prefs = context.getSharedPreferences("tuned.backup", Context.MODE_PRIVATE)
    private val lock = Mutex()

    val signedIn: StateFlow<Boolean> = account.signedIn

    private val statusFlow = MutableStateFlow(Status(prefs.getLong(KEY_LAST_SYNC, 0), Problem.NONE))
    val status: StateFlow<Status> = statusFlow.asStateFlow()

    /** Signs this device out of the account. What is on the device, and the backup, stay. */
    suspend fun stop() {
        account.signOut()
        forget()
    }

    /** Signing out of Tuned: forgets the account here at once; other devices and the backup are untouched. */
    fun disconnect() {
        account.disconnect()
        forget()
    }

    private fun forget() {
        prefs.edit(commit = true) { clear() }
        statusFlow.value = Status(0, Problem.NONE)
    }

    /**
     * One run: takes the other device's copy, sends this one's, or does nothing. Returns false when it should be tried
     * again later (offline, the server didn't answer, or another device saved at the same moment).
     */
    suspend fun run(): Boolean = lock.withLock {
        if (!account.signedIn.value) return@withLock true
        try {
            sync()
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            statusFlow.value = Status(now, Problem.NONE)
            true
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't reach the backup", e)
            statusFlow.value = statusFlow.value.copy(problem = Problem.OFFLINE)
            false
        }
    }

    /**
     * The welcome screen's "Restore from your Tuned account": takes the backup as it is, never sending this fresh
     * device's empty copy over it. The device stays signed in afterwards, so it keeps in step from then on.
     */
    suspend fun restore(): Restore = lock.withLock {
        if (!account.signedIn.value) return@withLock Restore.NOTHING_SAVED
        try {
            val found = account.find() ?: return@withLock Restore.NOTHING_SAVED
            val applied =
                try {
                    syncCopy.applySyncCopy(found.copy)
                } catch (e: SyncCopyRepository.NotACopy) {
                    AppLog.w(TAG, "Couldn't read the backup to restore it", e)
                    return@withLock Restore.NOTHING_SAVED
                }
            markSynced(found.fingerprint ?: CloudSync.fingerprint(found.copy), found.version)
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            statusFlow.value = Status(now, Problem.NONE)
            if (applied.guideChanged) onGuideChanged()
            if (applied.signedIn) {
                onSignedIn()
                Restore.RESTORED
            } else {
                Restore.NO_PLAYLIST
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't restore the backup", e)
            Restore.OFFLINE
        }
    }

    private suspend fun sync() {
        val plain = syncCopy.syncCopy()
        val local = CloudSync.fingerprint(plain)
        val synced = prefs.getString(KEY_SYNCED, null)
        if (local != synced && prefs.getString(KEY_NOTICED, null) != local) {
            // The first run that sees this change: remember when, for "the newer change wins".
            prefs.edit {
                putString(KEY_NOTICED, local)
                putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            }
        }
        val remote = account.find()
        val remoteFingerprint = remote?.let { it.fingerprint ?: CloudSync.fingerprint(it.copy) }
        val localChangedAt = prefs.getLong(KEY_CHANGED_AT, 0)
        when (CloudSync.decide(local, synced, remoteFingerprint, remote?.changedAt ?: 0, localChangedAt)) {
            CloudSync.Step.NOTHING -> {
                markSynced(local, remote?.version ?: 0)
            }

            CloudSync.Step.PUSH -> {
                // A Conflict (another device saved since find) is an IOException: the next run decides again.
                account.upload(plain, local, System.currentTimeMillis(), remote?.version ?: 0)
                markSynced(local, (remote?.version ?: 0) + 1)
            }

            CloudSync.Step.PULL -> {
                val found = requireNotNull(remote)
                val applied =
                    try {
                        syncCopy.applySyncCopy(found.copy)
                    } catch (e: SyncCopyRepository.NotACopy) {
                        // A damaged copy: this device's replaces it rather than leaving the backup stuck.
                        AppLog.w(TAG, "Couldn't read the backup; sending this device's", e)
                        account.upload(plain, local, System.currentTimeMillis(), found.version)
                        markSynced(local, found.version + 1)
                        return
                    }
                markSynced(requireNotNull(remoteFingerprint), found.version)
                if (applied.guideChanged) onGuideChanged()
                if (applied.signedIn) onSignedIn()
            }
        }
    }

    private fun markSynced(
        fingerprint: String,
        version: Int,
    ) = prefs.edit {
        putString(KEY_SYNCED, fingerprint)
        putString(KEY_NOTICED, fingerprint)
        putInt(KEY_VERSION, version)
    }

    private companion object {
        const val TAG = "Backup"
        const val KEY_SYNCED = "synced"
        const val KEY_NOTICED = "noticed"
        const val KEY_CHANGED_AT = "changedAt"
        const val KEY_LAST_SYNC = "lastSync"
        const val KEY_VERSION = "version"
    }
}
