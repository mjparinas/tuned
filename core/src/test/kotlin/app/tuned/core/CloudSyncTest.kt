package app.tuned.core

import app.tuned.core.CloudSync.Reason
import app.tuned.core.CloudSync.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CloudSyncTest {
    private fun decide(
        local: String,
        synced: String?,
        remote: String?,
        remoteAt: Long = 0,
        localAt: Long = 0,
    ) = CloudSync.decide(local, synced, remote, remoteAt, localAt)

    @Test
    fun anEmptyDriveGetsThisDevicesCopy() = assertEquals(Step.PUSH, decide("a", null, null))

    @Test
    fun theSameCopyEverywhereDoesNothing() = assertEquals(Step.NOTHING, decide("a", "b", "a"))

    @Test
    fun aNewDeviceTakesWhatIsAlreadyInDrive() = assertEquals(Step.PULL, decide("fresh", null, "phone"))

    @Test
    fun onlyThisDeviceChangedSoItSends() = assertEquals(Step.PUSH, decide("new", "old", "old"))

    @Test
    fun onlyTheOtherDeviceChangedSoThisOneTakes() = assertEquals(Step.PULL, decide("old", "old", "new"))

    @Test
    fun bothChangedAndTheNewerWins() {
        assertEquals(Step.PULL, decide("mine", "old", "theirs", remoteAt = 200, localAt = 100))
        assertEquals(Step.PUSH, decide("mine", "old", "theirs", remoteAt = 100, localAt = 200))
    }

    @Test
    fun theFingerprintChangesWithTheCopy() {
        val one = CloudSync.fingerprint("same".toByteArray())
        assertEquals(one, CloudSync.fingerprint("same".toByteArray()))
        assertNotEquals(one, CloudSync.fingerprint("other".toByteArray()))
    }

    @Test
    fun googlesSignInAnswers() {
        assertEquals(CloudSync.Poll.SIGNED_IN, CloudSync.poll(200, null))
        assertEquals(CloudSync.Poll.WAIT, CloudSync.poll(428, "authorization_pending"))
        assertEquals(CloudSync.Poll.SLOW_DOWN, CloudSync.poll(403, "slow_down"))
        assertEquals(CloudSync.Poll.DENIED, CloudSync.poll(403, "access_denied"))
        assertEquals(CloudSync.Poll.EXPIRED, CloudSync.poll(400, "expired_token"))
        assertEquals(CloudSync.Poll.FAILED, CloudSync.poll(500, null))
    }

    @Test
    fun asksForAScopeGooglesCodeSignInAllows() {
        // Google answers drive.appdata with "invalid_scope: Invalid device flow scope" (2026-11-18), so sync never signed in.
        assertNotEquals("https://www.googleapis.com/auth/drive.appdata", CloudSync.SCOPE)
        assertEquals("https://www.googleapis.com/auth/drive.file", CloudSync.SCOPE)
    }

    @Test
    fun aFailedSignInSaysWhatGoogleSaid() {
        assertEquals(CloudSync.Failure(Reason.OFFLINE, null), CloudSync.failure(null, null, null))
        val unknown = CloudSync.failure(401, "invalid_client", "The OAuth client was not found.")
        assertEquals(Reason.UNKNOWN_CLIENT, unknown.reason)
        assertEquals("invalid_client: The OAuth client was not found.", unknown.detail)
        assertEquals(Reason.WRONG_CLIENT_TYPE, CloudSync.failure(401, "invalid_client", "Invalid client type.").reason)
        assertEquals(Reason.WRONG_CLIENT_TYPE, CloudSync.failure(400, "unauthorized_client", null).reason)
        assertEquals(Reason.WRONG_SECRET, CloudSync.failure(401, "invalid_client", "Unauthorized").reason)
        assertEquals(Reason.SCOPE_NOT_ALLOWED, CloudSync.failure(400, "invalid_scope", "Invalid scope").reason)
        assertEquals(Reason.DENIED, CloudSync.failure(403, "access_denied", null).reason)
        assertEquals(Reason.EXPIRED, CloudSync.failure(400, "expired_token", null).reason)
        assertEquals(CloudSync.Failure(Reason.OTHER, "HTTP 500"), CloudSync.failure(500, null, null))
    }

    @Test
    fun aDisabledDriveApiIsToldApartFromNoNetwork() {
        assertTrue(CloudSync.driveApiOff(403, """{"error":{"errors":[{"reason":"accessNotConfigured"}]}}"""))
        assertTrue(CloudSync.driveApiOff(403, """{"error":{"status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}"""))
        assertFalse(CloudSync.driveApiOff(403, """{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}"""))
        assertFalse(CloudSync.driveApiOff(500, "accessNotConfigured"))
    }
}
