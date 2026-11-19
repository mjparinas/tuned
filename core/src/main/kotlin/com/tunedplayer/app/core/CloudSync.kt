package com.tunedplayer.app.core

import java.security.MessageDigest

/**
 * The rules of keeping a phone and a TV the same through the backup in the Tuned account (docs/decisions/accounts.md):
 * what one run does. Plain Kotlin so it is tested without a device.
 */
object CloudSync {
    enum class Step { NOTHING, PULL, PUSH }

    /**
     * What one run does, from three fingerprints of the synced copy: [local] (this device now), [synced] (what this
     * device last sent or took, null before the first run) and [remote] (what the backup holds, null when there is none).
     * Only one side changed: it wins. Both changed: the newer change wins, [remoteChangedAt] against [localChangedAt]
     * (when this device first saw its own change).
     */
    fun decide(
        local: String,
        synced: String?,
        remote: String?,
        remoteChangedAt: Long,
        localChangedAt: Long,
    ): Step {
        if (remote == null) return Step.PUSH
        if (local == remote) return Step.NOTHING
        // First run on this device with a backup already there: take it rather than overwrite another device's.
        if (synced == null) return Step.PULL
        val localChanged = local != synced
        val remoteChanged = remote != synced
        return when {
            localChanged && !remoteChanged -> Step.PUSH
            !localChanged -> Step.PULL
            remoteChangedAt > localChangedAt -> Step.PULL
            else -> Step.PUSH
        }
    }

    /** A fingerprint of the synced copy (SHA-256), stored with the backup to tell whether it changed. */
    fun fingerprint(plain: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) }
}
