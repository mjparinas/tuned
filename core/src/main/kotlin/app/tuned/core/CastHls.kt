package app.tuned.core

import kotlin.math.ceil

/**
 * A saved film or episode cast as HLS made on the fly (Melvin, 2026-11-18: "can we do on the fly encoding instead of
 * doing the full conversion first?"). The phone cuts the file into pieces of about [TARGET_US] at key frames, lists
 * them in a playlist the TV reads first, and writes each piece ([Fmp4]) only when the TV asks for it, so casting
 * starts at once, a seek makes only the piece it lands in, and nothing big is written to the phone.
 */
object CastHls {
    const val PLAYLIST = "index.m3u8"
    const val INIT = "init.mp4"
    private const val PIECE_PREFIX = "p"
    private const val PIECE_SUFFIX = ".m4s"

    /** Where each piece starts: the first key frame, then the first key frame [targetUs] or more after the last start. */
    fun starts(
        keyFramesUs: List<Long>,
        targetUs: Long = TARGET_US,
    ): List<Long> {
        val sorted = keyFramesUs.filter { it >= 0 }.distinct().sorted()
        if (sorted.isEmpty()) return listOf(0L)
        val starts = arrayListOf(sorted.first())
        for (k in sorted) if (k - starts.last() >= targetUs) starts += k
        return starts
    }

    /** The VOD playlist for pieces starting at [starts], of a film [durationUs] long. */
    fun playlist(
        starts: List<Long>,
        durationUs: Long,
    ): String {
        val lengths = lengths(starts, durationUs)
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append(ceil(lengths.max() / US_PER_S).toInt().coerceAtLeast(1)).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-MAP:URI=\"").append(INIT).append("\"\n")
            lengths.forEachIndexed { i, length ->
                append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", length / US_PER_S)).append(",\n")
                append(pieceName(i)).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    /** Each piece's length; the first counts from 0 so the timeline starts there, the last runs to the end. */
    fun lengths(
        starts: List<Long>,
        durationUs: Long,
    ): List<Double> = starts.indices.map { i ->
        val from = if (i == 0) 0L else starts[i]
        val to = if (i + 1 < starts.size) starts[i + 1] else maxOf(durationUs, starts[i] + MIN_LAST_US)
        (to - from).coerceAtLeast(MIN_LAST_US).toDouble()
    }

    fun pieceName(index: Int): String = "$PIECE_PREFIX$index$PIECE_SUFFIX"

    /** The piece number in a request for [name], or null when it isn't one. */
    fun pieceIndex(name: String): Int? = name
        .takeIf { it.startsWith(PIECE_PREFIX) && it.endsWith(PIECE_SUFFIX) }
        ?.substring(PIECE_PREFIX.length, name.length - PIECE_SUFFIX.length)
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

    /** The content type for each name the stream serves. */
    fun mimeFor(name: String): String = if (name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp4"

    /** About six seconds a piece, as most HLS is: short enough to start and seek quickly, long enough to stay cheap. */
    const val TARGET_US = 6_000_000L
    private const val MIN_LAST_US = 100_000L
    private const val US_PER_S = 1_000_000.0
}
