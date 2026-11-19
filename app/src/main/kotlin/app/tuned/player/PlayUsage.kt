package app.tuned.player

import androidx.media3.common.PlaybackException
import app.tuned.core.ContentKind
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.UsageStats

/**
 * The player's part of the usage stats (docs/decisions/admin.md): a play once its first picture shows, the first
 * play ever, and a play that failed for good, each with only the section, whether it came from the device or went
 * to a TV, and why it failed in a word. Never which channel, film or episode.
 */
internal class PlayUsage(
    private val usage: UsageStats,
) {
    /** Items already counted since the player opened, so a reconnect or a backup copy isn't a second play. */
    private val counted = mutableSetOf<Long>()

    fun pictured(
        item: ItemEntity?,
        downloaded: Boolean,
        cast: Boolean,
    ) {
        if (item == null || !counted.add(item.id)) return
        val section = section(item.kind)
        usage.track("play", "section" to section, "cast" to cast, "downloaded" to downloaded)
        usage.trackOnce("first_play", "section" to section)
    }

    fun failed(
        item: ItemEntity?,
        error: PlaybackException,
    ) {
        if (item == null) return
        usage.track("play_failed", "section" to section(item.kind), "reason" to reason(error.errorCode))
    }

    internal companion object {
        fun section(kind: Int) = when (ContentKind.fromCode(kind)) {
            ContentKind.LIVE -> "live"
            ContentKind.MOVIE -> "movies"
            ContentKind.SERIES -> "shows"
        }

        /** The server's words: network, timeout, server, codec or other. */
        fun reason(code: Int) = when (code) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT -> "timeout"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "server"
            in IO_FIRST..IO_LAST -> "network"
            in PARSING_FIRST..PARSING_LAST -> "server"
            in DECODING_FIRST..DECODING_LAST, in AUDIO_FIRST..AUDIO_LAST -> "codec"
            else -> "other"
        }

        // Media3's error code ranges: 2xxx input and output, 3xxx parsing, 4xxx decoding, 5xxx audio.
        private const val IO_FIRST = 2000
        private const val IO_LAST = 2999
        private const val PARSING_FIRST = 3000
        private const val PARSING_LAST = 3999
        private const val DECODING_FIRST = 4000
        private const val DECODING_LAST = 4999
        private const val AUDIO_FIRST = 5000
        private const val AUDIO_LAST = 5999
    }
}
