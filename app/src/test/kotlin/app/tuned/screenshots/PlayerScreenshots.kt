package app.tuned.screenshots

import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.tuned.R
import app.tuned.player.StatusNote
import app.tuned.player.TvControls
import app.tuned.player.TvState
import app.tuned.player.ViewPlayer
import app.tuned.player.backButton
import app.tuned.player.placeBesideCaptions
import app.tuned.player.subtitleText
import app.tuned.player.titleText
import app.tuned.player.usable
import com.github.takahirom.roborazzi.captureRoboImage
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
        note = "Playing on SHIELD",
        busy = false,
    )

    @Test
    fun castingStarting() = shot("player-casting-starting", TvState(), note = "Starting on SHIELD…", busy = true)

    private fun shot(
        name: String,
        state: TvState,
        note: String,
        busy: Boolean,
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
        val search = toolButton(activity, R.drawable.ic_player_subtitle_search, R.string.subtitle_search)
        val timing = toolButton(activity, R.drawable.ic_player_subtitle_timing, R.string.subtitle_timing).apply { usable(false) }
        placeBesideCaptions(playerView, listOf(search, timing))
        val density = activity.resources.displayMetrics.density
        val titles =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText(activity).apply { text = "The Lighthouse Keeper" })
                addView(subtitleText(activity).apply { text = "Harbour Town · S2 E4" })
            }
        val overlay =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.player_top_scrim)
                val pad = (24 * density).toInt()
                setPadding(pad / 2, pad / 2, pad, pad * 2)
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton(activity) {})
                addView(titles)
            }
        val status = StatusNote(activity).apply { show(note, busy) }
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
        activity.setContentView(root)
        playerView.showController()
        (playerView.player as ViewPlayer).tvChanged()
        shadowOf(Looper.getMainLooper()).idle()
        root.captureRoboImage("$name.png")
        exo.release()
    }

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

        /** The same as PlayerActivity's: the note sits half this above the centre. */
        const val STATUS_RAISE_DP = 176
    }
}

/** A typical phone held sideways, as for watching: 891 by 411 dp at 3x. */
const val PHONE_SIDEWAYS = "w891dp-h411dp-land-xxhdpi"
