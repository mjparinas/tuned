package app.tuned.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The rules of sync between devices (phone and TV) through the person's Google Drive app folder: what one run does, and
 * Google's answers while signing in with a code. Plain Kotlin so it is tested without a device.
 */
object CloudSync {
    enum class Step { NOTHING, PULL, PUSH }

    /**
     * What one run does, from three fingerprints of the synced copy: [local] (this device now), [synced] (what this
     * device last sent or took, null before the first run) and [remote] (what Drive holds, null when it holds nothing).
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
        // First run on this device with something already in Drive: take it rather than overwrite another device's.
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

    /**
     * A fingerprint of the synced copy keyed with the sync passphrase, so the one stored beside the file in Drive says
     * nothing about what is inside it (the copy holds logins).
     */
    fun fingerprint(
        plain: ByteArray,
        passphrase: CharArray,
    ): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(String(passphrase).toByteArray(), HMAC))
        return mac.doFinal(plain).joinToString("") { "%02x".format(it) }
    }

    enum class Poll { SIGNED_IN, WAIT, SLOW_DOWN, DENIED, EXPIRED, FAILED }

    /** Google's answer while the person enters the code: the status and the "error" field of the reply. */
    fun poll(
        status: Int,
        error: String?,
    ): Poll = when {
        status == HTTP_OK -> Poll.SIGNED_IN
        error == "authorization_pending" -> Poll.WAIT
        error == "slow_down" -> Poll.SLOW_DOWN
        error == "access_denied" -> Poll.DENIED
        error == "expired_token" -> Poll.EXPIRED
        else -> Poll.FAILED
    }

    /** Why signing in to Google ended without an account, in words the dialog can explain. */
    enum class Reason { OFFLINE, UNKNOWN_CLIENT, WRONG_CLIENT_TYPE, WRONG_SECRET, SCOPE_NOT_ALLOWED, DENIED, EXPIRED, OTHER }

    /** A failed sign-in: the [reason], and Google's own words ([detail], null when Google wasn't reached) to show under it. */
    data class Failure(
        val reason: Reason,
        val detail: String?,
    )

    /**
     * Google's answer to a failed sign-in step ([status] null when Google wasn't reached at all), from the "error" and
     * "error_description" fields of the reply. Before this, every one of them read "Couldn't reach Google", which hid a
     * misconfigured OAuth client behind a network message.
     */
    fun failure(
        status: Int?,
        error: String?,
        description: String?,
    ): Failure {
        if (status == null) return Failure(Reason.OFFLINE, null)
        val reason =
            when (error) {
                "access_denied" -> Reason.DENIED
                "expired_token" -> Reason.EXPIRED
                "invalid_scope" -> Reason.SCOPE_NOT_ALLOWED
                "unauthorized_client" -> Reason.WRONG_CLIENT_TYPE
                "deleted_client" -> Reason.UNKNOWN_CLIENT
                "invalid_client" -> invalidClient(description.orEmpty().lowercase())
                else -> Reason.OTHER
            }
        val detail = listOfNotNull(error ?: "HTTP $status", description?.takeIf { it.isNotBlank() }).joinToString(": ")
        return Failure(reason, detail)
    }

    /** Google's "invalid_client" covers an unknown client, the wrong kind of client and a wrong secret. */
    private fun invalidClient(about: String): Reason = when {
        "not found" in about || "deleted" in about -> Reason.UNKNOWN_CLIENT
        "type" in about -> Reason.WRONG_CLIENT_TYPE
        else -> Reason.WRONG_SECRET
    }

    /** Whether Drive turned a request down because the Drive API is off in the app's Google Cloud project. */
    fun driveApiOff(
        status: Int,
        body: String,
    ): Boolean = status == HTTP_FORBIDDEN && ("accessNotConfigured" in body || "SERVICE_DISABLED" in body)

    /** The one scope asked for: a folder in the person's Drive that only Tuned can see, nothing else of theirs. */
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    const val FILE_NAME = "tuned-sync"

    private const val HMAC = "HmacSHA256"
    private const val HTTP_OK = 200
    private const val HTTP_FORBIDDEN = 403
}
