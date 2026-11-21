package com.tunedplayer.app.data

import android.content.Context
import androidx.core.content.edit
import com.tunedplayer.app.core.CloudSync
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
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
    /** An event for the usage stats: "backup_saved" or "backup_restored" (docs/decisions/admin.md). */
    private val onUsage: (String) -> Unit = {},
    /** The playlist in use was removed on the website or another device: switch to this one. */
    private val onSwitch: suspend (Credentials) -> Unit = {},
    /** The playlist in use changed elsewhere, or the website asked for a refresh: download it again. */
    private val onRefresh: () -> Unit = {},
    /** The copy both sides last agreed on, which [CopyMerge] joins against. */
    private val base: LastSynced = LastSynced(context),
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
        base.clear()
        statusFlow.value = Status(0, Problem.NONE)
    }

    /**
     * One run: takes the other device's copy, sends this one's, or does nothing. Returns false when it should be tried
     * again later (offline, the server didn't answer, or another device saved at the same moment).
     */
    @Suppress("TooGenericExceptionCaught") // logged and reported as a failed run: nothing is hidden
    suspend fun run(): Boolean = lock.withLock {
        if (!account.signedIn.value) return@withLock true
        try {
            sync()
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            statusFlow.value = Status(now, Problem.NONE)
            true
        } catch (_: TunedAccount.Conflict) {
            // Not a failure: another device (or the website) saved between this run's read and its save. The next run
            // reads the newer backup and joins it, so it's one line in the log, not a warning that looks like being offline.
            AppLog.i(TAG) { "Another device saved the backup first; the next run joins it" }
            false
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't reach the backup", e)
            statusFlow.value = statusFlow.value.copy(problem = Problem.OFFLINE)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An answer it couldn't read, or a bug: logged, and tried again later like being offline, never a crash.
            AppLog.e(TAG, "Backup run failed", e)
            statusFlow.value = statusFlow.value.copy(problem = Problem.OFFLINE)
            false
        }
    }

    /**
     * The welcome screen's "Restore from your Tuned account": takes the backup as it is, never sending this fresh
     * device's empty copy over it. The device stays signed in afterwards, so it keeps in step from then on.
     */
    @Suppress("TooGenericExceptionCaught") // logged, and the welcome screen says the restore didn't work
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
            base.save(found.copy)
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_SYNC, now) }
            statusFlow.value = Status(now, Problem.NONE)
            if (applied.guideChanged) onGuideChanged()
            if (applied.signedIn) {
                onSignedIn()
                onUsage("backup_restored")
                Restore.RESTORED
            } else {
                Restore.NO_PLAYLIST
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't restore the backup", e)
            Restore.OFFLINE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Restoring the backup failed", e)
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
                base.save(plain)
            }

            CloudSync.Step.PUSH -> {
                if (remote != null && remoteFingerprint != synced) {
                    // Both sides changed: join them part by part rather than letting this device's copy replace the other.
                    join(plain, local, synced, requireNotNull(remote), requireNotNull(remoteFingerprint), localChangedAt)
                    return
                }
                // A Conflict (another device saved since find) is an IOException: the next run decides again.
                account.upload(plain, local, System.currentTimeMillis(), remote?.version ?: 0)
                markSynced(local, (remote?.version ?: 0) + 1)
                base.save(plain)
                onUsage("backup_saved")
            }

            CloudSync.Step.PULL -> {
                join(plain, local, synced, requireNotNull(remote), requireNotNull(remoteFingerprint), localChangedAt)
            }
        }
    }

    /**
     * The backup changed since this device last synced (the website, or another device): takes it, joined with what
     * changed here meanwhile ([CopyMerge]), and sends the joined copy back when it holds something the backup lacks.
     */
    private suspend fun join(
        plain: ByteArray,
        local: String,
        synced: String?,
        remote: TunedAccount.Remote,
        remoteFingerprint: String,
        localChangedAt: Long,
    ) {
        val localJson = json.parseToJsonElement(plain.decodeToString()).jsonObject
        val remoteJson =
            attempt(TAG, "Couldn't read the backup") { json.parseToJsonElement(remote.copy.decodeToString()).jsonObject }.getOrNull()
        if (remoteJson == null) {
            replaceDamaged(plain, local, remote.version)
            return
        }
        val localChanged = local != synced
        val partial = (remoteJson[CopyMerge.PARTIAL] as? JsonPrimitive)?.booleanOrNull == true
        val agreed =
            when {
                // Never synced, or a copy the website made from nothing: whatever is on either side counts as added.
                synced == null || partial -> {
                    JsonObject(emptyMap())
                }

                // Nothing changed here since the last sync, so this device's copy is the one both agreed on.
                !localChanged -> {
                    localJson
                }

                else -> {
                    lastAgreed() ?: JsonObject(emptyMap())
                }
            }
        val remoteWins = synced == null || !localChanged || remote.changedAt > localChangedAt
        val merged = CopyMerge.merge(agreed, localJson, remoteJson, remoteWins)
        val applied =
            try {
                syncCopy.applySyncCopy(merged.toString().toByteArray(), takenFrom = plain)
            } catch (e: SyncCopyRepository.NotACopy) {
                AppLog.w(TAG, "Couldn't read the backup; sending this device's", e)
                replaceDamaged(plain, local, remote.version)
                return
            }
        if (CopyMerge.same(merged, remoteJson)) {
            markSynced(remoteFingerprint, remote.version)
            base.save(remote.copy)
        } else {
            // This device had changes the backup lacks: send the joined copy, as this device now reads it.
            val joined = syncCopy.syncCopy()
            val fingerprint = CloudSync.fingerprint(joined)
            account.upload(joined, fingerprint, System.currentTimeMillis(), remote.version)
            markSynced(fingerprint, remote.version + 1)
            base.save(joined)
            onUsage("backup_saved")
        }
        if (applied.guideChanged) onGuideChanged()
        if (applied.signedIn) onSignedIn()
        applied.switchTo?.let { onSwitch(it) }
        if (applied.refresh && applied.switchTo == null && !applied.signedIn) onRefresh()
    }

    private fun lastAgreed(): JsonObject? {
        val text = base.load() ?: return null
        return attempt(TAG, "Couldn't read the last synced copy") { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    /** A damaged copy: this device's replaces it rather than leaving the backup stuck. */
    private suspend fun replaceDamaged(
        plain: ByteArray,
        local: String,
        version: Int,
    ) {
        account.upload(plain, local, System.currentTimeMillis(), version)
        markSynced(local, version + 1)
        base.save(plain)
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
        val json = Json { ignoreUnknownKeys = true }
    }
}
