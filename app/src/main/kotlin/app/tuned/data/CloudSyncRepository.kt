package app.tuned.data

import android.content.Context
import androidx.core.content.edit
import app.tuned.core.CloudSync
import app.tuned.core.PassphraseCrypto
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Sync between devices (from the app audit, item 13; Melvin chose his Google account's Drive app folder, 2026-11-17):
 * the saved playlists, settings, favourites and watch later ([SyncCopyRepository.syncCopy]) kept the same on a phone and
 * a TV. The copy goes to Drive's hidden app folder as it is, with no passphrase (Melvin, 2026-11-18: "i dont think we
 * need encryption"), so signing in to Google is all a device needs. Each run takes the other device's copy or sends this one's
 * ([CloudSync.decide]); everything works offline and catches up on the next run.
 */
class CloudSyncRepository(
    context: Context,
    /** The Google account: signing in, and the copy in Drive. */
    val drive: GoogleDrive,
    private val syncCopy: SyncCopyRepository,
    /** A pulled copy signed this device in: start its first download. */
    private val onSignedIn: () -> Unit,
) {
    enum class Problem { NONE, OFFLINE, DRIVE_API_OFF }

    data class Status(
        val lastSyncAt: Long,
        val problem: Problem,
    )

    private val prefs = context.getSharedPreferences("tuned.cloudsync", Context.MODE_PRIVATE)
    private val box = KeystoreBox("tuned.cloudsync")
    private val lock = Mutex()

    val available: Boolean get() = drive.available
    val signedIn: StateFlow<Boolean> = drive.signedIn

    private val statusFlow = MutableStateFlow(Status(prefs.getLong(KEY_LAST_SYNC, 0), Problem.NONE))
    val status: StateFlow<Status> = statusFlow.asStateFlow()

    /** Stops syncing here: signs out of Google. What is on the device stays. */
    suspend fun stop() {
        drive.signOut()
        prefs.edit { clear() }
        statusFlow.value = Status(0, Problem.NONE)
    }

    /**
     * One run: takes the other device's copy, sends this one's, or does nothing. Returns false when it should be tried
     * again later (offline, or Drive didn't answer).
     */
    suspend fun run(): Boolean = lock.withLock {
        if (!drive.available || !drive.signedIn.value) return@withLock true
        try {
            sync()
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            statusFlow.value = Status(now, Problem.NONE)
            true
        } catch (e: GoogleDrive.DriveApiOff) {
            AppLog.w(TAG, "Google Drive turned the request down", e)
            statusFlow.value = statusFlow.value.copy(problem = Problem.DRIVE_API_OFF)
            true
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't sync with Google Drive", e)
            statusFlow.value = statusFlow.value.copy(problem = Problem.OFFLINE)
            false
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
        val remote = drive.find()
        val localChangedAt = prefs.getLong(KEY_CHANGED_AT, 0)
        when (CloudSync.decide(local, synced, remote?.fingerprint, remote?.changedAt ?: 0, localChangedAt)) {
            CloudSync.Step.NOTHING -> {
                markSynced(local)
            }

            CloudSync.Step.PUSH -> {
                push(plain, local, remote?.id)
            }

            CloudSync.Step.PULL -> {
                val found = requireNotNull(remote)
                val signedIn =
                    try {
                        syncCopy.applySyncCopy(readable(drive.download(found.id)))
                    } catch (_: PassphraseCrypto.NotOurs) {
                        // A damaged copy, or a locked one this device can't open: this device's replaces it rather
                        // than leaving sync stuck.
                        AppLog.w(TAG, "Couldn't read the copy in Drive; sending this device's")
                        push(plain, local, found.id)
                        return
                    }
                markSynced(requireNotNull(found.fingerprint))
                if (signedIn) onSignedIn()
            }
        }
    }

    private suspend fun push(
        plain: ByteArray,
        fingerprint: String,
        existing: String?,
    ) {
        drive.upload(plain, fingerprint, System.currentTimeMillis(), existing)
        markSynced(fingerprint)
        // Drive now holds a plain copy, so the passphrase an older version kept is no longer needed.
        prefs.edit { remove(KEY_LEGACY_PASSPHRASE) }
    }

    /**
     * The copy as plain bytes. One an older version locked with a passphrase opens with the passphrase this device
     * still holds from then; without it, or with the wrong one, it counts as unreadable ([PassphraseCrypto.NotOurs]).
     */
    private fun readable(bytes: ByteArray): ByteArray {
        if (!PassphraseCrypto.isSealed(bytes)) return bytes
        val passphrase = legacyPassphrase() ?: throw PassphraseCrypto.NotOurs()
        return try {
            PassphraseCrypto.open(bytes, passphrase)
        } catch (_: PassphraseCrypto.WrongPassphrase) {
            throw PassphraseCrypto.NotOurs()
        }
    }

    private fun markSynced(fingerprint: String) = prefs.edit {
        putString(KEY_SYNCED, fingerprint)
        putString(KEY_NOTICED, fingerprint)
    }

    private fun legacyPassphrase(): CharArray? {
        val blob = prefs.getString(KEY_LEGACY_PASSPHRASE, null) ?: return null
        return attempt(TAG, "Couldn't read the sync passphrase") { box.open(blob).toCharArray() }.getOrNull()
    }

    private companion object {
        const val TAG = "CloudSync"
        /** Kept by versions before 2026-11-18, which locked the copy; read only to open such a copy once. */
        const val KEY_LEGACY_PASSPHRASE = "passphrase"
        const val KEY_SYNCED = "synced"
        const val KEY_NOTICED = "noticed"
        const val KEY_CHANGED_AT = "changedAt"
        const val KEY_LAST_SYNC = "lastSync"
    }
}
