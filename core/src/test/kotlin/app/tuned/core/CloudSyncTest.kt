package app.tuned.core

import app.tuned.core.CloudSync.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
    fun theFingerprintDependsOnThePassphrase() {
        val plain = "same".toByteArray()
        val one = CloudSync.fingerprint(plain, "passphrase1".toCharArray())
        assertEquals(one, CloudSync.fingerprint(plain, "passphrase1".toCharArray()))
        assertNotEquals(one, CloudSync.fingerprint(plain, "passphrase2".toCharArray()))
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
}
