package com.tunedplayer.app.core

/**
 * Channel scan (Melvin, 2026-11-17: "scan through all the channels kinda like the old days of scanning for tv signals
 * and hiding anything automatically that isn't working … differentiate between the server is down, versus the channel
 * is down versus there are current internet issues. we want to avoid flagging channels as down incorrectly").
 *
 * The rules, kept here so they can be tested without a device:
 * - [classify] reads what one short look at a stream returned. Only a clear answer counts: an HTTP error, nothing at
 *   all, a text page where video should be, a timeout or a refused connection. Anything binary counts as working.
 * - A failure is only the channel's fault when the internet is up, the server answers, and another channel plays
 *   ([ScanEngine]). Otherwise the scan waits, retries, and stops with the reason instead of blaming channels.
 * - A channel is hidden only after failing in [HIDE_AFTER] scans at least [MIN_APART_MS] apart ([afterFailure]), and
 *   comes back as soon as a scan finds it working, or when the person shows it again (then it is never hidden again).
 */
object ChannelScan {
    /** Put among the hidden keys (with countries and groups) to leave out channels scans have hidden. */
    const val HIDDEN_KEY = "scan:dead"
    const val TIMEOUT_MS = 10_000L
    const val SAMPLE_BYTES = 16 * 1024
    const val HIDE_AFTER = 2
    const val MIN_APART_MS = 6 * 60 * 60_000L

    enum class Failure {
        HTTP,
        NO_DATA,
        NOT_VIDEO,
        TIMEOUT,
        CONNECTION,
        ACCOUNT,

        /** It wouldn't play in the player while the internet and the service were working ([afterPlayFailure]). */
        PLAYBACK,
    }

    /**
     * One look at a stream: the HTTP [code] (null when no answer came), the first bytes, and whether the time ran out
     * or the connection failed. Null means it works.
     */
    fun classify(
        code: Int?,
        bytes: ByteArray,
        timedOut: Boolean,
        connectionFailed: Boolean,
    ): Failure? = when {
        connectionFailed -> Failure.CONNECTION
        code == null -> if (timedOut) Failure.TIMEOUT else Failure.CONNECTION
        code in accountCodes -> Failure.ACCOUNT
        code !in OK_CODES -> Failure.HTTP
        bytes.isEmpty() -> if (timedOut) Failure.TIMEOUT else Failure.NO_DATA
        isText(bytes) && !isPlaylist(bytes) -> Failure.NOT_VIDEO
        else -> null
    }

    /** Codes that speak about the login or the number of connections, not the channel. */
    private val accountCodes = setOf(401, 403, 407, 429, 458, 509)
    private val OK_CODES = 200..299

    /** An HLS playlist: the scan then looks at its first segment or variant ([firstUri]). */
    fun isPlaylist(bytes: ByteArray): Boolean = bytes
        .decodeToString(0, minOf(bytes.size, PLAYLIST_PROBE))
        .trimStart(BOM, ' ', '\r', '\n')
        .startsWith("#EXTM3U")

    /** An error page or JSON where a stream should be: the first bytes are all printable text. */
    fun isText(bytes: ByteArray): Boolean {
        val head = bytes.take(TEXT_PROBE)
        return head.isNotEmpty() &&
            head.all { b ->
                val v = b.toInt() and BYTE
                v == TAB || v == LF || v == CR || v in PRINTABLE
            }
    }

    private const val PLAYLIST_PROBE = 16
    private const val BOM = '\uFEFF'
    private const val TEXT_PROBE = 256
    private const val BYTE = 0xFF
    private const val TAB = 9
    private const val LF = 10
    private const val CR = 13
    private val PRINTABLE = 0x20..0x7E

    /** The first segment or variant an HLS playlist points at, resolved against [base]. */
    fun firstUri(
        playlist: String,
        base: String,
    ): String? {
        val line = playlist.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return resolve(base, line)
    }

    private fun resolve(
        base: String,
        ref: String,
    ): String = when {
        "://" in ref -> ref
        ref.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ref
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + ref
    }

    /** What the device remembers about a channel between scans. */
    data class Record(
        val failures: Int = 0,
        val firstFailAt: Long = 0,
        val lastFailAt: Long = 0,
        val lastOkAt: Long = 0,
        val reason: Failure? = null,
        /** The person showed it again: it is never hidden by a scan after that. */
        val kept: Boolean = false,
    ) {
        val hidden: Boolean get() = !kept && failures >= HIDE_AFTER
    }

    fun afterOk(
        previous: Record?,
        now: Long,
    ): Record = Record(lastOkAt = now, kept = previous?.kept ?: false)

    /** A failure the scan blamed on the channel. Two in the same scan, or close together, count once. */
    fun afterFailure(
        previous: Record?,
        failure: Failure,
        now: Long,
    ): Record {
        val p = previous ?: Record()
        return when {
            p.failures == 0 -> p.copy(failures = 1, firstFailAt = now, lastFailAt = now, reason = failure)
            now - p.lastFailAt < MIN_APART_MS -> p.copy(reason = failure)
            else -> p.copy(failures = p.failures + 1, lastFailAt = now, reason = failure)
        }
    }

    /** The person's "Show again": a fresh start, and kept out of the scan's hiding from now on. */
    fun keep(previous: Record?): Record = Record(lastOkAt = previous?.lastOkAt ?: 0, kept = true)

    /**
     * Melvin, 2026-11-17: "if a channel doesn't load but you know the service is up and internet is working, hide that
     * channel from results". True only when all three say so: the phone's network is validated, the service answers
     * (the Xtream account call accepts the login with a connection free, or for a plain M3U there is no such call), and
     * another channel drew a picture shortly before or after, which shows streams were flowing.
     */
    fun blameOnPlay(
        internetUp: Boolean,
        server: ScanEngine.Server,
        anotherChannelPlayed: Boolean,
    ): Boolean = internetUp && anotherChannelPlayed && (server == ScanEngine.Server.UP || server == ScanEngine.Server.UNKNOWN)

    /** Another channel's picture counts within this long of the failure, before or after. */
    const val OTHER_PLAYED_WITHIN_MS = 10 * 60_000L

    /** A failure in the player that [blameOnPlay] confirmed: hidden at once, unless the person chose to keep it. */
    fun afterPlayFailure(
        previous: Record?,
        now: Long,
    ): Record {
        val p = previous ?: Record()
        return p.copy(
            failures = maxOf(p.failures, HIDE_AFTER),
            firstFailAt = if (p.firstFailAt > 0) p.firstFailAt else now,
            lastFailAt = now,
            reason = Failure.PLAYBACK,
        )
    }
}
