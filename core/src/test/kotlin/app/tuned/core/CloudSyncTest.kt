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
    fun theFingerprintChangesWithTheCopy() {
        val one = CloudSync.fingerprint("same".toByteArray())
        assertEquals(one, CloudSync.fingerprint("same".toByteArray()))
        assertNotEquals(one, CloudSync.fingerprint("other".toByteArray()))
    }
}
