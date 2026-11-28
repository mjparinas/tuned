package com.tunedplayer.app.screenshots

import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.takahirom.roborazzi.captureRoboImage
import com.tunedplayer.app.R
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.data.Accent
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.player.CastingScreen
import com.tunedplayer.app.player.ChannelZap
import com.tunedplayer.app.player.InfoPills
import com.tunedplayer.app.player.StatusNote
import com.tunedplayer.app.player.StreamInfo
import com.tunedplayer.app.player.TvControls
import com.tunedplayer.app.player.TvState
import com.tunedplayer.app.player.ViewPlayer
import com.tunedplayer.app.player.backButton
import com.tunedplayer.app.player.placeBesideCaptions
import com.tunedplayer.app.player.playerClock
import com.tunedplayer.app.player.subtitleText
import com.tunedplayer.app.player.tintScrubber
import com.tunedplayer.app.player.titleText
import com.tunedplayer.app.player.usable
import com.tunedplayer.app.ui.theme.playerAccent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player's controls while casting (docs/decisions/player.md): the player screen is an activity around Media3, not
 * a route, so these build its views the way `PlayerActivity` does and give the controls a stand-in TV. Everything on
 * the controls (time, seek bar, play and pause, CC, greyed-out buttons) is Media3's own control view reading the
 * app's [ViewPlayer], as in the app.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_SIDEWAYS)
class PlayerScreenshots {
    @Test
    fun castingPlaying() = shot(
        "player-casting",
        TvState(
            positionMs = 7 * MINUTE + 12_000,
            durationMs = 22 * MINUTE + 41_000,
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            seekable = true,
            subtitle = TvState.Subtitle("en", on = true),
        ),
        note = null,
        busy = false,
        casting = true,
    )

    /** A live channel the TV is still loading: the only time the casting screen shows a spinner, besides connecting. */
    @Test
    fun castingLoading() = shot(
        "player-casting-loading",
        TvState(playbackState = Player.STATE_BUFFERING),
        note = null,
        busy = false,
        casting = true,
    )

    /** A DLNA TV says nothing back about its volume, so there is no slider; paused here. */
    @Test
    fun castingPausedDlna() = shot(
        "player-casting-paused",
        TvState(playWhenReady = false, playbackState = Player.STATE_READY),
        note = null,
        busy = false,
        casting = true,
        volume = null,
    )

    /** The seek bar in the accent, the fit and lock buttons and time of day at the top right, the stream's pills under the title. */
    @Test
    fun controlsAtAGlance() = shot(
        "player-controls",
        TvState(
            positionMs = 14 * MINUTE + 5_000,
            durationMs = 41 * MINUTE + 30_000,
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            seekable = true,
        ),
        note = null,
        busy = false,
        glance = StreamInfo.pills(1920, 1080, 23.976f, "audio/mp4a-latm", 2),
    )

    /** Subtitles off on the phone: the subtitle timing button beside CC is greyed out (Melvin, 2026-11-20). */
    @Test
    fun subtitlesOff() = shot(
        "player-subtitles-off",
        TvState(
            positionMs = 14 * MINUTE + 5_000,
            durationMs = 41 * MINUTE + 30_000,
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            seekable = true,
            subtitle = TvState.Subtitle("en", on = false),
        ),
        note = null,
        busy = false,
        captionsOn = false,
    )

    @Test
    fun castingStarting() = shot("player-casting-starting", TvState(), note = "Starting on SHIELD…", busy = true, casting = true)

    private fun shot(
        name: String,
        state: TvState,
        note: String?,
        busy: Boolean,
        glance: StreamInfo.Pills? = null,
        captionsOn: Boolean = true,
        casting: Boolean = false,
        volume: Float? = 0.6f,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val exo = ExoPlayer.Builder(activity).build()
        exo.setMediaItems(
            listOf(1L, 2L, 3L).map { id ->
                MediaItem
                    .Builder()
                    .setMediaId("$id")
                    .setUri("http://tv.invalid/$id.mp4")
                    .build()
            },
            1,
            0,
        )
        val tv =
            object : TvControls {
                // The controls read the stand-in TV in every shot, for times and seek bars without a stream.
                override val casting = true

                override fun state() = state

                override fun play() = Unit

                override fun pause() = Unit

                override fun seekTo(positionMs: Long) = Unit

                override fun showSubtitles(on: Boolean) = Unit
            }
        val playerView =
            PlayerView(activity).apply {
                setBackgroundColor(Color.BLACK)
                setShowSubtitleButton(true)
                setShowNextButton(true)
                setShowPreviousButton(true)
                player = ViewPlayer(exo, { tv }, {}, { false })
            }
        tintScrubber(playerView, playerAccent(activity, Accent.VIOLET, dynamicColour = false))
        placeBesideCaptions(playerView, captionButtons(activity, timingUsable = !casting && captionsOn))
        val density = activity.resources.displayMetrics.density
        val titles =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText(activity).apply { text = "The Lighthouse Keeper" })
                addView(subtitleText(activity).apply { text = "Harbour Town · S2 E4" })
                if (glance != null) {
                    val pills = InfoPills(activity, { null }, { false }).apply { show(glance) }
                    addView(pills.view, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = (6 * density).toInt() })
                }
            }
        val overlay =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.player_top_scrim)
                val pad = (24 * density).toInt()
                setPadding(pad / 2, pad / 2, pad, pad * 2)
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton(activity) {})
                addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
                // The top row's own buttons (PlayerTools), with no three-dot menu: fit greyed out while the TV plays.
                addView(toolButton(activity, R.drawable.ic_player_fit, R.string.player_fit).apply { usable(!casting) })
                addView(toolButton(activity, R.drawable.ic_player_lock, R.string.player_lock))
                addView(playerClock(activity))
            }
        // Notes over the video are for the phone's own player; while casting they go on the casting screen.
        val status = StatusNote(activity).apply { if (!casting) note?.let { show(it, busy) } }
        val root =
            FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(
                    overlay,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
                )
                addView(
                    status,
                    FrameLayout
                        .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                        .apply { bottomMargin = (STATUS_RAISE_DP * density).toInt() },
                )
            }
        if (casting) castOnto(root, playerView, CastingScreen.Look("SHIELD", null, state.takeIf { note == null }, volume), note, busy)
        activity.setContentView(root)
        playerView.showController()
        (playerView.player as ViewPlayer).tvChanged()
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.usable(false)
        shadowOf(Looper.getMainLooper()).idle()
        root.captureRoboImage("$name.png")
        exo.release()
    }

    /**
     * The channel card the moment a live channel is flipped to (Melvin, 2026-11-28: faster zapping): number and name at
     * once, over the last picture, before the new stream has started. The logo sits at its left when the channel has one.
     */
    @Test
    fun channelCard() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val channel =
            ItemEntity(
                id = 7,
                kind = ContentKind.LIVE.code,
                name = "CA| Harbour News HD",
                searchName = "harbour news hd",
                groupName = "CA| NEWS",
                region = "CA",
                adult = false,
                logo = null,
                url = "http://tv.invalid/7.ts",
                tvgId = null,
                showKey = null,
                showName = null,
                season = null,
                episode = null,
                sortOrder = 7,
                number = 7,
            )
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(24, 32, 44)) }
        val zap = ChannelZap(activity, { listOf(channel) }) { false }
        zap.attach(root)
        activity.setContentView(root)
        zap.listener.onMediaItemTransition(MediaItem.Builder().setMediaId("7").build(), Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        shadowOf(Looper.getMainLooper()).idle()
        root.captureRoboImage("player-channel-card.png")
    }

    /** The casting screen over [playerView], as `PlayerActivity` shows it while a TV plays. */
    private fun castOnto(
        root: FrameLayout,
        playerView: PlayerView,
        look: CastingScreen.Look,
        note: String?,
        busy: Boolean,
    ) {
        val screen = CastingScreen(root.context, { null }) { 0 }
        screen.attach(root, playerView)
        screen.tint(playerAccent(root.context, Accent.VIOLET, dynamicColour = false))
        screen.update(look)
        screen.note(note, busy)
    }

    /** Subtitle search and timing; timing is greyed out while casting (the TV draws the captions) or while subtitles are off. */
    private fun captionButtons(
        activity: ComponentActivity,
        timingUsable: Boolean,
    ) = listOf(
        toolButton(activity, R.drawable.ic_player_subtitle_search, R.string.subtitle_search),
        toolButton(activity, R.drawable.ic_player_subtitle_timing, R.string.subtitle_timing).apply { usable(timingUsable) },
    )

    private fun toolButton(
        activity: ComponentActivity,
        icon: Int,
        label: Int,
    ) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = activity.getString(label)
        setBackgroundColor(Color.TRANSPARENT)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** The same as PlayerActivity's: the note sits this far above the centre. */
        const val STATUS_RAISE_DP = 72
    }
}

/** A typical phone held sideways, as for watching: 891 by 411 dp at 3x. */
const val PHONE_SIDEWAYS = "w891dp-h411dp-land-xxhdpi"
