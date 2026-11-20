package com.tunedplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.tunedplayer.app.core.FileServer
import com.tunedplayer.app.core.WebVtt
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.diagnostics.AppLog
import java.io.File

/**
 * Subtitles on a Chromecast (Melvin, 2026-11-19: "Is there a way to support subtitles while streaming?"). The TV's
 * player reads WebVTT from a web address, so the subtitles the phone has for a film or episode (fetched with subtitle
 * search, kept as SubRip) are turned into WebVTT and served from the phone like a saved file, and sent with the video
 * as its one text track. The TV shows them when captions are on, and the CC button turns them on and off there.
 */
internal class CastSubtitles(
    private val player: () -> ExoPlayer?,
    /** Shares a file the phone makes on the server the TV reads from, returning its folder's address, or null. */
    private val serve: (name: String, made: FileServer.Made) -> String?,
    private val unshare: (folder: String) -> Unit,
    /** The subtitle timing set in the player, worked into the cues as they are made. */
    private val offsetMs: () -> Long,
) {
    private var folder: String? = null

    /** The subtitles sent with the video on the TV now, or null. */
    var sent: TvState.Subtitle? = null
        private set

    /** The text track for [item], served from now on, or null when the phone has no subtitles for it. */
    fun trackFor(item: ItemEntity): MediaTrack? {
        stop()
        val config = subtitleOf(item) ?: return null
        val file =
            config.uri.path
                ?.let(::File)
                ?.takeIf { it.isFile } ?: return null
        val shared = serve("subtitles-${item.id}", Made(file, offsetMs())) ?: return null
        folder = shared
        val language = config.language
        sent = TvState.Subtitle(language, on = captionsOn())
        AppLog.i(TAG) { "Sending subtitles ($language) with ${item.name}" }
        return MediaTrack
            .Builder(TRACK_ID, MediaTrack.TYPE_TEXT)
            .setContentId(shared + FILE)
            .setContentType(WebVtt.MIME)
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
            .apply { language?.let { setLanguage(it) } }
            .build()
    }

    /** The track to turn on as the video loads: the subtitles, when captions are on. */
    fun activeIds(): LongArray? = if (sent?.on == true) longArrayOf(TRACK_ID) else null

    /** The CC button while casting. */
    fun show(
        client: RemoteMediaClient?,
        on: Boolean,
    ) {
        val now = sent ?: return
        client?.setActiveMediaTracks(if (on) longArrayOf(TRACK_ID) else LongArray(0))
        sent = now.copy(on = on)
    }

    /** No more subtitles on the TV: the next video sends its own. */
    fun stop() {
        folder?.let(unshare)
        folder = null
        sent = null
    }

    /** The subtitle file the phone's player has for [item] (a side track from subtitle search), if any. */
    private fun subtitleOf(item: ItemEntity): MediaItem.SubtitleConfiguration? {
        val p = player() ?: return null
        return (0 until p.mediaItemCount)
            .map(p::getMediaItemAt)
            .firstOrNull { it.mediaId == item.id.toString() }
            ?.localConfiguration
            ?.subtitleConfigurations
            ?.firstOrNull { it.uri.scheme == "file" }
    }

    /** Captions are on in the phone's player: not turned off, and "None" not picked from the CC menu. */
    private fun captionsOn(): Boolean {
        val params = player()?.trackSelectionParameters ?: return false
        return C.TRACK_TYPE_TEXT !in params.disabledTrackTypes && params.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT == 0
    }

    /** The WebVTT of one SubRip file, made when the TV first asks and kept for its later asks. */
    private class Made(
        private val srt: File,
        private val offsetMs: Long,
    ) : FileServer.Made {
        private val vtt by lazy { WebVtt.fromSubRip(srt.readText(), offsetMs).toByteArray() }

        override fun bytes(file: String): ByteArray? = if (file == FILE) vtt else null

        override fun type(file: String): String = "${WebVtt.MIME}; charset=utf-8"
    }

    companion object {
        private const val TAG = "Cast"
        private const val TRACK_ID = 1L
        private const val FILE = "subtitles.vtt"

        /** The TV's state for the player's controls, from what the Cast library last heard from it. */
        fun state(
            client: RemoteMediaClient?,
            live: Boolean,
            subtitle: TvState.Subtitle?,
        ): TvState {
            val status = client?.mediaStatus
            val (playWhenReady, state) =
                when (status?.playerState) {
                    MediaStatus.PLAYER_STATE_PLAYING -> true to Player.STATE_READY
                    MediaStatus.PLAYER_STATE_PAUSED -> false to Player.STATE_READY
                    MediaStatus.PLAYER_STATE_IDLE -> false to idle(status.idleReason)
                    else -> true to Player.STATE_BUFFERING // loading, buffering, or nothing heard yet
                }
            val length = client?.streamDuration ?: 0L
            val duration = if (live || length <= 0) C.TIME_UNSET else length
            return TvState(
                positionMs = if (live) 0 else client?.approximateStreamPosition?.coerceAtLeast(0) ?: 0,
                durationMs = duration,
                playWhenReady = playWhenReady,
                playbackState = state,
                seekable = duration != C.TIME_UNSET,
                subtitle = subtitle,
            )
        }

        /** A film that played to its end has ended; one that failed or was stopped waits for play to send it again. */
        private fun idle(reason: Int): Int = if (reason == MediaStatus.IDLE_REASON_FINISHED) Player.STATE_ENDED else Player.STATE_IDLE
    }
}
