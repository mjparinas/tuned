package app.tuned.core

import app.tuned.core.ChannelScan.Failure
import app.tuned.core.ScanEngine.Outcome
import app.tuned.core.ScanEngine.Server
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanEngineTest {
    /** A pretend device: channels in [dead] fail, and the network and server do whatever the test says. */
    private class FakeIo(
        val dead: MutableSet<Long> = mutableSetOf(),
    ) : ScanEngine.Io {
        var clock = 0L
        var internet: () -> Boolean = { true }
        var server: () -> Server = { Server.UP }
        var streamsDown = false

        /** Every stream fails once this many looks have been taken. */
        var streamsDownAfter = Int.MAX_VALUE
        val probed = mutableListOf<Long>()
        val ok = mutableListOf<Long>()
        val failures = mutableListOf<Long>()

        override fun now() = clock

        override suspend fun probe(id: Long): Failure? {
            clock += 1_000
            probed += id
            val down = streamsDown || probed.size > streamsDownAfter
            return if (down || !internet() || id in dead) Failure.HTTP else null
        }

        override suspend fun internetUp() = internet()

        override suspend fun server() = server.invoke()

        override suspend fun pause(ms: Long) {
            clock += ms
        }

        override suspend fun recordOk(id: Long) {
            ok += id
        }

        override suspend fun recordFailure(
            id: Long,
            failure: Failure,
        ) {
            failures += id
        }

        override fun progress(
            done: Int,
            total: Int,
        ) = Unit
    }

    private fun <T> run(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return requireNotNull(result).getOrThrow()
    }

    @Test
    fun onlyTheDeadChannelIsBlamed() {
        val io = FakeIo(dead = mutableSetOf(3))
        val result = run { ScanEngine(io).run(listOf(1, 2, 3, 4), knownGood = null) }
        assertEquals(Outcome.DONE, result.outcome)
        assertEquals(listOf(3L), io.failures)
        assertEquals(3, result.working)
        assertEquals(1, result.failed)
    }

    @Test
    fun aDropInTheInternetBlamesNoChannel() {
        val io = FakeIo()
        var drops = 2
        // The network is down for the first two looks at it, then back.
        io.internet = { if (drops > 0 && io.probed.size >= 2) false.also { drops-- } else true }
        val result = run { ScanEngine(io).run(listOf(1, 2, 3), knownGood = null) }
        assertEquals(Outcome.DONE, result.outcome)
        assertTrue(io.failures.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), io.ok)
    }

    @Test
    fun noInternetStopsTheScan() {
        val io = FakeIo()
        io.internet = { io.probed.isEmpty() }
        val result = run { ScanEngine(io).run(listOf(1, 2, 3), knownGood = null) }
        assertEquals(Outcome.NO_INTERNET, result.outcome)
        assertTrue(io.failures.isEmpty())
    }

    @Test
    fun aServerThatIsDownFromTheStartScansNothing() {
        val io = FakeIo()
        io.server = { Server.DOWN }
        val result = run { ScanEngine(io).run(listOf(1, 2, 3), knownGood = null) }
        assertEquals(Outcome.SERVER_DOWN, result.outcome)
        assertTrue(io.probed.isEmpty())
    }

    @Test
    fun aServerThatGoesDownMidScanBlamesNoChannel() {
        val io = FakeIo()
        // Channels 1 and 2 play; then the server stops answering and nothing plays.
        io.server = { if (io.probed.size >= 2) Server.DOWN else Server.UP }
        io.streamsDownAfter = 2
        val result = run { ScanEngine(io).run(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), knownGood = null) }
        assertEquals(Outcome.SERVER_DOWN, result.outcome)
        // Channel 3 failed just after channel 2 played, but nothing played after it, so it isn't blamed either.
        assertTrue(io.failures.isEmpty())
        assertEquals(listOf(1L, 2L), io.ok)
    }

    @Test
    fun aFailureIsConfirmedByTheNextChannelPlaying() {
        val io = FakeIo(dead = mutableSetOf(2))
        run { ScanEngine(io).run(listOf(1, 2, 3), knownGood = null) }
        assertEquals(listOf(2L), io.failures)
        // No extra look was needed: channel 3 playing settled it.
        assertEquals(listOf(1L, 2L, 3L), io.probed)
    }

    @Test
    fun aFailureAtTheEndIsCheckedAgainstTheLastChannelThatPlayed() {
        val io = FakeIo(dead = mutableSetOf(3))
        run { ScanEngine(io).run(listOf(1, 2, 3), knownGood = null) }
        assertEquals(listOf(3L), io.failures)
        assertEquals(listOf(1L, 2L, 3L, 2L), io.probed)
    }

    @Test
    fun whenNoStreamGetsThroughTheServerIsBlamedNotTheChannels() {
        val io = FakeIo()
        io.streamsDown = true
        val result = run { ScanEngine(io).run(listOf(1, 2, 3), knownGood = 9) }
        assertEquals(Outcome.SERVER_DOWN, result.outcome)
        assertTrue(io.failures.isEmpty())
    }

    @Test
    fun aFullAccountStopsTheScan() {
        val io = FakeIo()
        io.server = { Server.BUSY }
        val result = run { ScanEngine(io).run(listOf(1, 2), knownGood = null) }
        assertEquals(Outcome.ACCOUNT_BUSY, result.outcome)
        assertTrue(io.probed.isEmpty())
    }

    @Test
    fun aFailureBeforeAnythingPlayedIsLookedAtAgainAtTheEnd() {
        val io = FakeIo(dead = mutableSetOf(1))
        io.server = { Server.UNKNOWN } // a plain M3U login: no server call to ask
        val result = run { ScanEngine(io).run(listOf(1, 2, 3), knownGood = null) }
        assertEquals(Outcome.DONE, result.outcome)
        assertEquals(listOf(1L), io.failures)
        assertEquals(0, result.unsure)
    }

    @Test
    fun withNothingToCompareAgainstNoChannelIsBlamed() {
        val io = FakeIo(dead = mutableSetOf(1, 2))
        io.server = { Server.UNKNOWN }
        val result = run { ScanEngine(io).run(listOf(1, 2), knownGood = null) }
        assertTrue(io.failures.isEmpty())
        assertEquals(2, result.unsure)
    }

    @Test
    fun aSlowRunOfFailuresIsCheckedAgainstAKnownGoodChannel() {
        val io = FakeIo(dead = mutableSetOf(2, 3, 4))
        val result = run { ScanEngine(io).run(listOf(1, 2, 3, 4), knownGood = null) }
        assertEquals(listOf(2L, 3L, 4L), io.failures)
        assertEquals(Outcome.DONE, result.outcome)
        // After the first failure, more than 20 s pass, so channel 1 was looked at again to be sure.
        assertTrue(io.probed.count { it == 1L } > 1)
    }
}
