package app.tuned.core

import java.util.Locale
import kotlin.math.ceil

/**
 * Pause and scrub live TV (Melvin, 2026-11-17). Live channels arrive as one endless MPEG-TS stream, which a player
 * can't seek in. The player keeps a copy of what it receives on the device, cut into short pieces with a playlist, the
 * way HLS works; playing that local playlist can pause, rewind and catch up to live.
 *
 * This cuts the stream: feed it bytes as they arrive and it says where each piece ends. A piece ends on a packet
 * boundary once it holds at least [targetSeconds] of video by the stream's clock (PCR), at a packet that marks a key
 * frame (random access), or regardless once it reaches twice the target. Every piece starts with the stream's latest
 * PAT and PMT, so each one can be read on its own.
 */
class TsSegmenter(
    private val targetSeconds: Double = 4.0,
    private val sink: Sink,
) {
    interface Sink {
        /** Bytes for the current piece. */
        fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        )

        /** The current piece is complete and lasted [seconds]; anything written next starts a new one. */
        fun cut(seconds: Double)
    }

    private val carry = ByteArray(PACKET)
    private var carried = 0
    private var synced = false
    private var pat: ByteArray? = null
    private var pmt: ByteArray? = null
    private var pmtPid = -1
    private var startPcr = -1L
    private var lastPcr = -1L
    private var pieceHasData = false

    /** Seconds in the current, unfinished piece. */
    val pending: Double get() = if (startPcr < 0 || lastPcr < 0) 0.0 else elapsed(startPcr, lastPcr)

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ) {
        var i = offset
        val end = offset + length
        while (i < end) {
            if (carried > 0 || end - i < PACKET) {
                val take = minOf(PACKET - carried, end - i)
                System.arraycopy(bytes, i, carry, carried, take)
                carried += take
                i += take
                if (carried == PACKET) {
                    carried = 0
                    if (carry[0] == SYNC) packet(carry.copyOf()) else resync(carry)
                }
                continue
            }
            if (bytes[i] != SYNC) {
                synced = false
                i++
                continue
            }
            packet(bytes.copyOfRange(i, i + PACKET))
            i += PACKET
        }
    }

    /** Lost sync inside the carried bytes: start over at the next sync byte in them. */
    private fun resync(buffer: ByteArray) {
        synced = false
        val next = (1 until PACKET).firstOrNull { buffer[it] == SYNC } ?: return
        val rest = PACKET - next
        System.arraycopy(buffer, next, carry, 0, rest)
        carried = rest
    }

    private fun packet(p: ByteArray) {
        synced = true
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val payloadStart = p[1].toInt() and 0x40 != 0
        if (pid == 0 && payloadStart) {
            pat = p
            pmtPid = pmtPidFrom(p)
        } else if (pid == pmtPid && payloadStart) {
            pmt = p
        }
        val randomAccess = readAdaptation(p)
        val seconds = pending
        if (pieceHasData && seconds >= targetSeconds && (randomAccess || seconds >= targetSeconds * 2)) {
            sink.cut(seconds)
            startPcr = lastPcr
            pieceHasData = false
        }
        if (!pieceHasData) {
            // Each piece opens with the stream's tables, unless this packet is one of them.
            pat?.takeIf { pid != 0 }?.let { sink.write(it, 0, PACKET) }
            pmt?.takeIf { pid != pmtPid }?.let { sink.write(it, 0, PACKET) }
            pieceHasData = true
        }
        sink.write(p, 0, PACKET)
    }

    /** Notes the packet's clock (PCR), if it carries one; true when it starts a key frame (random access). */
    private fun readAdaptation(p: ByteArray): Boolean {
        val adaptation = (p[3].toInt() shr 4) and 0x3
        if ((adaptation != 2 && adaptation != 3) || (p[4].toInt() and 0xFF) == 0) return false
        val flags = p[5].toInt() and 0xFF
        if (flags and 0x10 != 0 && (p[4].toInt() and 0xFF) >= PCR_FIELD) {
            val pcr = pcrOf(p)
            if (startPcr < 0 || pcr < lastPcr - WRAP_GUARD) startPcr = pcr
            lastPcr = pcr
        }
        return flags and 0x40 != 0
    }

    /** The PMT's PID from the first programme in a PAT packet, or -1. */
    private fun pmtPidFrom(p: ByteArray): Int {
        var at = HEADER
        if ((p[3].toInt() shr 4) and 0x2 != 0) at += 1 + (p[4].toInt() and 0xFF)
        at += 1 + (p[at].toInt() and 0xFF) // pointer field
        val sectionStart = at
        var entry = sectionStart + PAT_HEADER
        while (entry + 4 <= minOf(PACKET, sectionStart + PAT_HEADER + MAX_PROGRAMS * 4)) {
            val programme = ((p[entry].toInt() and 0xFF) shl 8) or (p[entry + 1].toInt() and 0xFF)
            val pid = ((p[entry + 2].toInt() and 0x1F) shl 8) or (p[entry + 3].toInt() and 0xFF)
            if (programme != 0) return pid // programme 0 is the network table
            entry += 4
        }
        return -1
    }

    private fun pcrOf(p: ByteArray): Long {
        fun b(i: Int) = (p[i].toLong() and 0xFF)
        return (b(6) shl 25) or (b(7) shl 17) or (b(8) shl 9) or (b(9) shl 1) or (b(10) shr 7)
    }

    private fun elapsed(
        from: Long,
        to: Long,
    ): Double = ((to - from + PCR_WRAP) % PCR_WRAP) / PCR_HZ

    companion object {
        const val PACKET = 188
        private const val SYNC: Byte = 0x47
        private const val HEADER = 4
        private const val PAT_HEADER = 8
        private const val MAX_PROGRAMS = 40
        private const val PCR_FIELD = 7
        private const val PCR_HZ = 90_000.0
        private const val PCR_WRAP = 1L shl 33

        /** A PCR that goes back by more than this is a new clock (a restart or splice), not a wrap. */
        private const val WRAP_GUARD = 90_000L * 10
    }
}

/** The playlist for the pieces on the device: a live (EVENT-like, sliding) HLS media playlist. */
object LocalHls {
    data class Piece(
        val sequence: Long,
        val file: String,
        val seconds: Double,
        /** A new connection started here, so the stream's clock may jump. */
        val discontinuity: Boolean = false,
    )

    /** [discontinuitySequence] counts the discontinuities in pieces that already left the front of the window. */
    fun playlist(
        pieces: List<Piece>,
        discontinuitySequence: Long,
        ended: Boolean = false,
    ): String {
        val target = ceil(pieces.maxOfOrNull { it.seconds } ?: 1.0).toInt().coerceAtLeast(1)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(pieces.firstOrNull()?.sequence ?: 0).append('\n')
            append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence).append('\n')
            for (p in pieces) {
                if (p.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:").append(String.format(Locale.ROOT, "%.3f", p.seconds)).append(",\n")
                append(p.file).append('\n')
            }
            if (ended) append("#EXT-X-ENDLIST\n")
        }
    }
}
