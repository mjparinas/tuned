package app.tuned.core

import app.tuned.core.ChannelScan.Failure

/**
 * Runs a channel scan one channel at a time through [Io], deciding whose fault each failure is before anything is
 * recorded against a channel:
 * 1. **The internet.** A failure with no working network is never the channel's: the scan waits for the network, then
 *    tries the channel again, and stops with [Outcome.NO_INTERNET] if it doesn't come back.
 * 2. **The server.** The server's own answer (Xtream's account call) must come back, and the account must have a free
 *    connection; otherwise the scan waits and retries, then stops with [Outcome.SERVER_DOWN] or [Outcome.ACCOUNT_BUSY].
 * 3. **Other channels.** A failure only counts once another channel plays after it: the next channel in the list, or,
 *    after a long run of failures, the last channel that played, looked at again. That shows streams were getting
 *    through all along. If the server or the streams stop instead, the failures waiting to be judged are dropped
 *    unjudged. With nothing that has played yet, a failed channel waits for a second look at the end of the scan.
 */
class ScanEngine(
    private val io: Io,
) {
    interface Io {
        fun now(): Long

        /** One short look at a channel's stream; null when it works. */
        suspend fun probe(id: Long): Failure?

        suspend fun internetUp(): Boolean

        suspend fun server(): Server

        suspend fun pause(ms: Long)

        suspend fun recordOk(id: Long)

        suspend fun recordFailure(
            id: Long,
            failure: Failure,
        )

        fun progress(
            done: Int,
            total: Int,
        )
    }

    enum class Server { UP, DOWN, BUSY, UNKNOWN }

    enum class Outcome { DONE, NO_INTERNET, SERVER_DOWN, ACCOUNT_BUSY }

    data class Result(
        val outcome: Outcome,
        val checked: Int,
        val working: Int,
        val failed: Int,
        val unsure: Int,
    )

    private sealed interface Step {
        data object Done : Step

        data object Later : Step

        data class Stop(
            val outcome: Outcome,
        ) : Step
    }

    private var control: Long? = null
    private var lastOkAt = Long.MIN_VALUE / 2
    private var working = 0
    private var failed = 0
    private var dropped = 0
    private var strikes = 0

    /** Failures since the last channel that played, judged by the next one that does. */
    private val pending = mutableListOf<Pair<Long, Failure>>()

    /** Scans [channels] in order. [knownGood] is a channel that played recently, to compare against from the start. */
    suspend fun run(
        channels: List<Long>,
        knownGood: Long?,
    ): Result {
        control = knownGood
        preflight()?.let { return Result(it, 0, 0, 0, 0) }
        val later = mutableListOf<Long>()
        channels.forEachIndexed { index, id ->
            when (val step = check(id)) {
                Step.Later -> later += id
                is Step.Stop -> return stop(step.outcome, later.size)
                Step.Done -> Unit
            }
            io.progress(index + 1, channels.size)
            io.pause(PACE_MS)
        }
        // Channels that failed before anything had played get a second look, now there is something to compare against.
        var unsure = 0
        later.forEachIndexed { index, id ->
            when (val step = check(id)) {
                Step.Later -> unsure++
                is Step.Stop -> return stop(step.outcome, unsure + later.size - index)
                Step.Done -> Unit
            }
            io.pause(PACE_MS)
        }
        val outcome = settlePending()
        return Result(outcome, working + failed, working, failed, unsure + dropped)
    }

    private fun stop(
        outcome: Outcome,
        waiting: Int,
    ): Result {
        dropped += pending.size
        pending.clear()
        return Result(outcome, working + failed, working, failed, waiting + dropped)
    }

    private suspend fun preflight(): Outcome? {
        if (!awaitInternet()) return Outcome.NO_INTERNET
        var server = io.server()
        var tries = 1
        while ((server == Server.DOWN || server == Server.BUSY) && tries < SERVER_TRIES) {
            io.pause(RETRY_MS)
            server = io.server()
            tries++
        }
        return when (server) {
            Server.DOWN -> Outcome.SERVER_DOWN
            Server.BUSY -> Outcome.ACCOUNT_BUSY
            else -> null
        }
    }

    private suspend fun check(id: Long): Step {
        strikes = 0
        var step = attempt(id)
        while (step == null) step = attempt(id)
        return step
    }

    /** One try at a channel: where it stands, or null to try it again after waiting. */
    private suspend fun attempt(id: Long): Step? {
        val failure = io.probe(id) ?: return ok(id)
        if (!io.internetUp()) return if (awaitInternet()) null else Step.Stop(Outcome.NO_INTERNET)
        if (io.now() - lastOkAt <= RECENT_OK_MS) {
            pending += id to failure // judged when the next channel plays
            return Step.Done
        }
        // A while since anything played: ask the server, and look at the last channel that played again.
        val server = io.server()
        val streamsFlow =
            when (server) {
                Server.DOWN, Server.BUSY -> false
                else -> control?.takeIf { it != id }?.let { other -> io.probe(other) == null } ?: return Step.Later
            }
        if (streamsFlow) {
            lastOkAt = io.now()
            pending += id to failure
            settled()
            return Step.Done
        }
        // The server is down, the account is full, or it answers but no stream gets through: not the channels' fault.
        dropped += pending.size
        pending.clear()
        if (++strikes >= SERVER_TRIES) return Step.Stop(if (server == Server.BUSY) Outcome.ACCOUNT_BUSY else Outcome.SERVER_DOWN)
        io.pause(RETRY_MS)
        return null
    }

    private suspend fun ok(id: Long): Step {
        settled()
        io.recordOk(id)
        control = id
        lastOkAt = io.now()
        working++
        return Step.Done
    }

    /** Something played after the waiting failures, so they were the channels' own. */
    private suspend fun settled() {
        for ((id, failure) in pending) {
            io.recordFailure(id, failure)
            failed++
        }
        pending.clear()
    }

    /**
     * Failures at the very end of the list, with nothing after them: one more look at the last channel that played. If
     * that fails too, the streams stopped rather than the channels, and the scan says so.
     */
    private suspend fun settlePending(): Outcome {
        if (pending.isEmpty()) return Outcome.DONE
        val other = control?.takeIf { c -> pending.none { it.first == c } }
        if (other != null && io.probe(other) == null) {
            settled()
            return Outcome.DONE
        }
        dropped += pending.size
        pending.clear()
        return if (other == null) Outcome.DONE else Outcome.SERVER_DOWN
    }

    private suspend fun awaitInternet(): Boolean {
        repeat(INTERNET_WAITS) {
            if (io.internetUp()) return true
            io.pause(RETRY_MS)
        }
        return io.internetUp()
    }

    companion object {
        /** Between channels, so a scan never hammers the server. */
        const val PACE_MS = 750L

        /** A channel that played this recently shows streams are getting through. */
        const val RECENT_OK_MS = 20_000L
        const val RETRY_MS = 60_000L
        const val SERVER_TRIES = 3
        const val INTERNET_WAITS = 5
    }
}
