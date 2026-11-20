package com.tunedplayer.app.core

import java.net.URI
import java.net.URISyntaxException

/**
 * Reading a live HLS channel's playlists, so the player can keep the channel on the device for pause, rewind and
 * recording the way it does a plain MPEG-TS channel (Melvin, 2026-11-20: "do 7"). The player already downloads every
 * piece of the channel; Tuned keeps a copy of each one as it passes. To do that it needs to know which requests are
 * the channel's pieces, and, while the player plays from the device, which pieces to fetch next (one at a time, on
 * the one connection).
 *
 * Only plain MPEG-TS pieces can be kept: encrypted pieces (`#EXT-X-KEY`) arrive as ciphertext, fMP4 pieces
 * (`#EXT-X-MAP`) and byte ranges can't be joined into one stream, and a separate audio rendition would be a second
 * stream of pieces. Those channels play as they always have, without the copy.
 */
object HlsPlaylist {
    data class Variant(
        val uri: String,
        val bandwidth: Long,
    )

    data class Segment(
        val uri: String,
        val sequence: Long,
        val seconds: Double,
    )

    sealed interface Parsed

    /** A master playlist: the qualities on offer. [keepable] is false when the audio comes as its own rendition. */
    data class Master(
        val variants: List<Variant>,
        val keepable: Boolean,
    ) : Parsed

    /** A media playlist: the pieces now in the window, oldest first. */
    data class Media(
        val segments: List<Segment>,
        val targetSeconds: Double,
        val ended: Boolean,
        val keepable: Boolean,
    ) : Parsed {
        /** A subtitle rendition's playlist (WebVTT pieces), which is no part of the picture or sound. */
        val subtitles: Boolean get() =
            segments.isNotEmpty() &&
                segments.all { s -> path(s.uri).let { it.endsWith(".vtt") || it.endsWith(".webvtt") } }
    }

    /** Reads [text], resolving its links against [base] (the address it came from); null when it isn't a playlist. */
    fun parse(
        text: String,
        base: String,
    ): Parsed? {
        val lines =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return null
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) master(lines, base) else media(lines, base)
    }

    private fun master(
        lines: List<String>,
        base: String,
    ): Master {
        val variants = ArrayList<Variant>()
        var pending: Long? = null
        var separateAudio = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pending = attribute(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
                }

                line.startsWith("#EXT-X-MEDIA") -> {
                    if (attribute(line, "TYPE") == "AUDIO" && attribute(line, "URI") != null) separateAudio = true
                }

                !line.startsWith("#") && pending != null -> {
                    resolve(base, line)?.let { variants += Variant(it, pending ?: 0L) }
                    pending = null
                }
            }
        }
        return Master(variants, keepable = !separateAudio && variants.isNotEmpty())
    }

    private fun media(
        lines: List<String>,
        base: String,
    ): Media {
        val m = MediaReader(base)
        lines.forEach(m::line)
        return m.build()
    }

    /** A media playlist, a line at a time. */
    private class MediaReader(
        private val base: String,
    ) {
        private val segments = ArrayList<Segment>()
        private var sequence = 0L
        private var target = DEFAULT_TARGET
        private var duration: Double? = null
        private var ended = false
        private var keepable = true

        fun line(line: String) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = value(line).toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = value(line).toDoubleOrNull() ?: DEFAULT_TARGET
                line.startsWith("#EXTINF:") -> duration = value(line).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-BYTERANGE") -> keepable = false
                line.startsWith("#EXT-X-KEY") -> if (attribute(line, "METHOD") != "NONE") keepable = false
                !line.startsWith("#") -> piece(line)
            }
        }

        private fun piece(line: String) {
            val seconds = duration ?: return
            resolve(base, line)?.let { segments += Segment(it, sequence, seconds) }
            sequence++
            duration = null
        }

        fun build(): Media {
            // fMP4 or audio-only pieces without a map line are still not MPEG-TS.
            val plain = segments.none { s -> path(s.uri).let { it.endsWith(".m4s") || it.endsWith(".mp4") || it.endsWith(".aac") } }
            return Media(segments, target, ended, keepable && plain && segments.isNotEmpty())
        }

        private fun value(line: String) = line.substringAfter(':').trim()
    }

    /** A playlist's address: its path ends in `.m3u8` (or `.m3u`, as some providers write it). */
    fun isPlaylist(uri: String): Boolean = path(uri).let { it.endsWith(".m3u8") || it.endsWith(".m3u") }

    /** [ref] against [base], as a browser would; null when either can't be read. */
    fun resolve(
        base: String,
        ref: String,
    ): String? = try {
        URI(base).resolve(URI(ref.replace(" ", "%20"))).toString()
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun path(uri: String): String = uri.substringBefore('?').substringBefore('#').lowercase()

    /** `NAME=value` or `NAME="value"` from an attribute list. */
    private fun attribute(
        line: String,
        name: String,
    ): String? {
        val match = Regex("(?:^|[:,])$name=(\"[^\"]*\"|[^,]*)").find(line) ?: return null
        return match.groupValues[1].trim('"')
    }

    private const val DEFAULT_TARGET = 6.0
}
