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
 * a TV. The copy is sealed with a passphrase the person chooses ([PassphraseCrypto]) before it
 * leaves the device, so Google holds only ciphertext. Each run takes the other device's copy or sends this one's
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
    enum class Problem { NONE, OFFLINE, PASSPHRASE, DRIVE_API_OFF }

    data class Status(
        val lastSyncAt: Long,
        val problem: Problem,
    )

    private val prefs = context.getSharedPreferences("tuned.cloudsync", Context.MODE_PRIVATE)
    private val box = KeystoreBox("tuned.cloudsync")
    private val lock = Mutex()

    val available: Boolean get() = drive.available
    val signedIn: StateFlow<Boolean> = drive.signedIn

    private val hasPassphraseFlow = MutableStateFlow(prefs.contains(KEY_PASSPHRASE))
    val hasPassphrase: StateFlow<Boolean> = hasPassphraseFlow.asStateFlow()

    private val statusFlow = MutableStateFlow(Status(prefs.getLong(KEY_LAST_SYNC, 0), Problem.NONE))
    val status: StateFlow<Status> = statusFlow.asStateFlow()

    /** Keeps [passphrase] sealed on this device, so later runs need no typing. Every device uses the same one. */
    fun setPassphrase(passphrase: String) {
        prefs.edit {
            putString(KEY_PASSPHRASE, box.seal(passphrase))
            // A new passphrase changes every fingerprint: start over as a device that hasn't synced.
            remove(KEY_SYNCED)
            remove(KEY_NOTICED)
        }
        hasPassphraseFlow.value = true
        statusFlow.value = statusFlow.value.copy(problem = Problem.NONE)
    }

    /** Stops syncing here: signs out of Google and forgets the passphrase. What is on the device stays. */
    suspend fun stop() {
        drive.signOut()
        prefs.edit { clear() }
        hasPassphraseFlow.value = false
        statusFlow.value = Status(0, Problem.NONE)
    }

    /**
     * One run: takes the other device's copy, sends this one's, or does nothing. Returns false when it should be tried
     * again later (offline, or Drive didn't answer).
     */
    suspend fun run(): Boolean = lock.withLock {
        if (!drive.available || !drive.signedIn.value) return@withLock true
        val passphrase = passphrase() ?: return@withLock true
        try {
            sync(passphrase)
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            statusFlow.value = Status(now, Problem.NONE)
            true
        } catch (_: PassphraseCrypto.WrongPassphrase) {
            AppLog.w(TAG, "The sync passphrase doesn't open the copy in Drive")
            statusFlow.value = statusFlow.value.copy(problem = Problem.PASSPHRASE)
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

    private suspend fun sync(passphrase: CharArray) {
        val plain = syncCopy.syncCopy()
        val local = CloudSync.fingerprint(plain, passphrase)
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
                push(plain, local, passphrase, remote?.id)
            }

            CloudSync.Step.PULL -> {
                val found = requireNotNull(remote)
                val opened =
                    try {
                        PassphraseCrypto.open(drive.download(found.id), passphrase)
                    } catch (_: PassphraseCrypto.NotOurs) {
                        // A damaged copy in Drive: this device's replaces it rather than leaving sync stuck.
                        AppLog.w(TAG, "The copy in Drive is damaged; sending this device's")
                        push(plain, local, passphrase, found.id)
                        return
                    }
                val signedIn = syncCopy.applySyncCopy(opened)
                markSynced(requireNotNull(found.fingerprint))
                if (signedIn) onSignedIn()
            }
        }
    }

    private suspend fun push(
        plain: ByteArray,
        fingerprint: String,
        passphrase: CharArray,
        existing: String?,
    ) {
        drive.upload(PassphraseCrypto.seal(plain, passphrase), fingerprint, System.currentTimeMillis(), existing)
        markSynced(fingerprint)
    }

    private fun markSynced(fingerprint: String) = prefs.edit {
        putString(KEY_SYNCED, fingerprint)
        putString(KEY_NOTICED, fingerprint)
    }

    private fun passphrase(): CharArray? {
        val blob = prefs.getString(KEY_PASSPHRASE, null) ?: return null
        return attempt(TAG, "Couldn't read the sync passphrase") { box.open(blob).toCharArray() }.getOrNull()
    }

    private companion object {
        const val TAG = "CloudSync"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_SYNCED = "synced"
        const val KEY_NOTICED = "noticed"
        const val KEY_CHANGED_AT = "changedAt"
        const val KEY_LAST_SYNC = "lastSync"
    }
}
