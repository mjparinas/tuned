package app.tuned.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * What the on-screen controls drive: the phone's player, or the TV while casting. While casting, the phone's own
 * player is stopped, so the controls are shown the TV instead (Melvin, 2026-11-19: "While casting can you show the time
 * and allow it to scrub through the video. Let the play button pause and play the video"): its position and length,
 * whether it plays, the seek bar and skip buttons moving it, and the CC button turning its subtitles on and off.
 * Moved out of `PlayerActivity`, which passes in what it knows.
 */
@OptIn(UnstableApi::class)
@Suppress("TooManyFunctions") // a Player has many methods, and each one the TV answers while casting is an override
class ViewPlayer(
    private val exo: ExoPlayer,
    private val tv: () -> TvControls?,
    /** Play pressed while held (the account was full) or stopped: hide the status note before starting. */
    private val onStartHeld: () -> Unit,
    /** A live channel pauses into the copy kept on the device; true when it did. */
    private val pauseLive: (ExoPlayer) -> Boolean,
    /** Playing a live channel: no playback speed (Melvin, 2026-11-19: "remove that for live tv"). */
    private val live: () -> Boolean = { false },
) : ForwardingPlayer(exo) {
    /** The TV, while something is cast to it. */
    private val casting: TvControls? get() = tv()?.takeIf { it.casting }

    /** Films, episodes, catch-up and recordings can play faster or slower; live TV and a cast to a TV can't. */
    private val speedAllowed get() = !live() && casting == null

    /** The controls and the media session, told when the TV changes, since the stopped phone player won't tell them. */
    private val watchers = LinkedHashSet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        synchronized(watchers) { watchers += listener }
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        synchronized(watchers) { watchers -= listener }
    }

    /** The TV started, stopped, moved or changed its subtitles, or a cast began or ended: the controls look again. */
    fun tvChanged() {
        val events = Player.Events(FlagSet.Builder().addAll(*TV_EVENTS).build())
        synchronized(watchers) { watchers.toList() }.forEach { it.onEvents(this, events) }
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands().buildUpon()
        if (!speedAllowed) commands.remove(Player.COMMAND_SET_SPEED_AND_PITCH)
        val t = casting ?: return commands.build()
        val state = t.state()
        commands.addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_TRACKS, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
        if (state.seekable) {
            commands.addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        } else {
            commands.removeAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        }
        return commands.build()
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (speedAllowed) super.setPlaybackSpeed(speed)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (speedAllowed) super.setPlaybackParameters(playbackParameters)
    }

    override fun prepare() {
        if (casting == null) super.prepare()
    }

    override fun play() {
        val t = casting
        when {
            t != null -> {
                t.play()
            }

            playbackState == Player.STATE_IDLE -> {
                onStartHeld()
                super.prepare()
                super.play()
            }

            else -> {
                super.play()
            }
        }
    }

    override fun pause() {
        val t = casting
        when {
            t != null -> t.pause()

            // A live channel pauses into the copy kept on the device, so it can carry on, rewind or catch up.
            pauseLive(exo) -> Unit

            else -> super.pause()
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        when {
            casting == null -> super.setPlayWhenReady(playWhenReady)
            playWhenReady -> play()
            else -> pause()
        }
    }

    // What the TV is doing, while casting.

    override fun getPlayWhenReady(): Boolean = casting?.state()?.playWhenReady ?: super.getPlayWhenReady()

    override fun getPlaybackState(): Int = casting?.state()?.playbackState ?: super.getPlaybackState()

    override fun isPlaying(): Boolean {
        val state = casting?.state() ?: return super.isPlaying()
        return state.playWhenReady && state.playbackState == Player.STATE_READY
    }

    override fun isLoading(): Boolean = casting?.state()?.let { it.playbackState == Player.STATE_BUFFERING } ?: super.isLoading()

    override fun getPlaybackSuppressionReason(): Int =
        if (casting != null) Player.PLAYBACK_SUPPRESSION_REASON_NONE else super.getPlaybackSuppressionReason()

    override fun getCurrentPosition(): Long = casting?.state()?.positionMs ?: super.getCurrentPosition()

    override fun getContentPosition(): Long = casting?.state()?.positionMs ?: super.getContentPosition()

    override fun getBufferedPosition(): Long = casting?.state()?.positionMs ?: super.getBufferedPosition()

    override fun getContentBufferedPosition(): Long = casting?.state()?.positionMs ?: super.getContentBufferedPosition()

    override fun getTotalBufferedDuration(): Long = if (casting != null) 0 else super.getTotalBufferedDuration()

    override fun getDuration(): Long = casting?.state()?.durationMs ?: super.getDuration()

    override fun getContentDuration(): Long = casting?.state()?.durationMs ?: super.getContentDuration()

    override fun isCurrentMediaItemSeekable(): Boolean = casting?.state()?.seekable ?: super.isCurrentMediaItemSeekable()

    override fun isCurrentMediaItemDynamic(): Boolean = if (casting != null) live() else super.isCurrentMediaItemDynamic()

    /** The phone's list of videos, with the one on the TV given the TV's length, so the seek bar spans the TV's video. */
    override fun getCurrentTimeline(): Timeline {
        val timeline = super.getCurrentTimeline()
        val state = casting?.state() ?: return timeline
        if (timeline.isEmpty) return timeline
        return TvTimeline(timeline, currentMediaItemIndex, state)
    }

    // Moving the TV.

    override fun seekTo(positionMs: Long) {
        val t = casting
        if (t == null) super.seekTo(positionMs) else t.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        val t = casting
        // The seek bar seeks within the video on screen; another index is a change of channel or episode.
        if (t != null && mediaItemIndex == currentMediaItemIndex) {
            t.seekTo(positionMs.coerceAtLeast(0))
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun seekBack() {
        val t = casting
        if (t == null) super.seekBack() else t.seekTo((t.state().positionMs - seekBackIncrement).coerceAtLeast(0))
    }

    override fun seekForward() {
        val t = casting ?: return super.seekForward()
        val state = t.state()
        val to = state.positionMs + seekForwardIncrement
        t.seekTo(if (state.durationMs == C.TIME_UNSET) to else to.coerceAtMost(state.durationMs))
    }

    /**
     * Previous goes to the start of the video first, as on the phone, but by the TV's position: the stopped phone
     * player still holds where it was when the cast began.
     */
    override fun seekToPrevious() {
        val t = casting ?: return super.seekToPrevious()
        if (t.state().seekable && t.state().positionMs > maxSeekToPreviousPosition) {
            t.seekTo(0)
        } else {
            super.seekToPreviousMediaItem()
        }
    }

    // The TV's subtitles, shown to the CC button as one text track.

    override fun getCurrentTracks(): Tracks {
        val t = casting ?: return super.getCurrentTracks()
        val subtitle = t.state().subtitle ?: return Tracks.EMPTY
        val format =
            Format
                .Builder()
                .setId(TV_TEXT_ID)
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(subtitle.language)
                .build()
        val group = TrackGroup(TV_TEXT_ID, format)
        return Tracks(listOf(Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(subtitle.on))))
    }

    /** The CC menu while casting: picking the track turns the TV's subtitles on, "None" turns them off. */
    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        val t = casting ?: return super.setTrackSelectionParameters(parameters)
        val on =
            parameters.overrides.keys.any { it.id == TV_TEXT_ID } &&
                !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        t.showSubtitles(on)
    }

    /** One window of the phone's timeline with the TV's length and seekability. */
    private class TvTimeline(
        private val inner: Timeline,
        private val index: Int,
        private val state: TvState,
    ) : Timeline() {
        override fun getWindowCount() = inner.windowCount

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            inner.getWindow(windowIndex, window, defaultPositionProjectionUs)
            if (windowIndex == index) {
                window.durationUs = if (state.durationMs == C.TIME_UNSET) C.TIME_UNSET else state.durationMs * US_PER_MS
                window.isSeekable = state.seekable
                window.isPlaceholder = false
                if (state.seekable) window.isDynamic = false
            }
            return window
        }

        override fun getPeriodCount() = inner.periodCount

        override fun getPeriod(
            periodIndex: Int,
            period: Period,
            setIds: Boolean,
        ): Period {
            inner.getPeriod(periodIndex, period, setIds)
            if (period.windowIndex == index && state.durationMs != C.TIME_UNSET) period.durationUs = state.durationMs * US_PER_MS
            return period
        }

        override fun getIndexOfPeriod(uid: Any) = inner.getIndexOfPeriod(uid)

        override fun getUidOfPeriod(periodIndex: Int): Any = inner.getUidOfPeriod(periodIndex)
    }

    private companion object {
        const val TV_TEXT_ID = "tv-subtitles"
        const val US_PER_MS = 1_000L

        val TV_EVENTS =
            intArrayOf(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                Player.EVENT_TRACKS_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
    }
}

/** The TV as the player's controls see it while casting: [CastControl] in the app, a stand-in for screenshots. */
interface TvControls {
    /** Whether something is being cast now. */
    val casting: Boolean

    fun state(): TvState

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun showSubtitles(on: Boolean)
}

/** Where the TV is and what it does, in the terms of Media3's [Player]. */
data class TvState(
    val positionMs: Long = 0,
    /** C.TIME_UNSET when the TV hasn't said (live channels, DLNA, before it starts). */
    val durationMs: Long = C.TIME_UNSET,
    val playWhenReady: Boolean = true,
    /** Player.STATE_BUFFERING while the TV loads, READY once it plays or is paused, ENDED at the end. */
    val playbackState: Int = Player.STATE_BUFFERING,
    val seekable: Boolean = false,
    /** The subtitles sent with the video, or null when there are none. */
    val subtitle: Subtitle? = null,
) {
    data class Subtitle(
        val language: String?,
        val on: Boolean,
    )
}
