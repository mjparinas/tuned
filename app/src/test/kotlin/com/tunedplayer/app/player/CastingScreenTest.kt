package com.tunedplayer.app.player

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's screen while casting (Melvin, 2026-11-21: "it just says playing on shield, and then the spinner is
 * actively spinning"): the spinner shows only while the TV loads, the controls stay up, and the stale frame is covered.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastingScreenTest {
    private lateinit var exo: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var screen: CastingScreen
    private var tvState = TvState(playbackState = Player.STATE_READY, playWhenReady = true)
    private val heard = mutableListOf<Int>()

    private val tv =
        object : TvControls {
            override val casting = true

            override fun state() = tvState

            override fun play() = Unit

            override fun pause() = Unit

            override fun seekTo(positionMs: Long) = Unit

            override fun showSubtitles(on: Boolean) = Unit
        }

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        exo = ExoPlayer.Builder(activity).build()
        playerView = PlayerView(activity)
        val root = FrameLayout(activity).apply { addView(playerView) }
        activity.setContentView(root)
        screen = CastingScreen(activity, { null }) { REST_MS }
        screen.attach(root, playerView)
    }

    @After
    fun tearDown() = exo.release()

    private fun look(
        state: TvState?,
        volume: Float? = 0.4f,
    ) = CastingScreen.Look("SHIELD", null, state, volume)

    @Test
    fun aPlayingTvShowsNoSpinner() {
        screen.update(look(TvState(playbackState = Player.STATE_READY, playWhenReady = true)))

        assertEquals("Playing", screen.statusText.toString())
        assertFalse(screen.spinning)
    }

    @Test
    fun theSpinnerShowsOnlyWhileTheTvLoads() {
        screen.update(look(TvState(playbackState = Player.STATE_BUFFERING)))
        assertEquals("Loading on SHIELD…", screen.statusText.toString())
        assertTrue(screen.spinning)

        screen.update(look(TvState(playbackState = Player.STATE_READY, playWhenReady = false)))
        assertEquals("Paused", screen.statusText.toString())
        assertFalse(screen.spinning)
    }

    @Test
    fun aStepOnTheWayShowsUntilCleared() {
        screen.update(look(null))
        screen.note("Connecting to SHIELD…", busy = true)
        assertEquals("Connecting to SHIELD…", screen.statusText.toString())
        assertTrue(screen.spinning)

        screen.update(look(TvState(playbackState = Player.STATE_READY, playWhenReady = true)))
        screen.note(null, busy = false)
        assertEquals("Playing", screen.statusText.toString())
        assertFalse(screen.spinning)
    }

    @Test
    fun theControlsStayUpWhileCastingAndHideAgainAfter() {
        screen.update(look(TvState(playbackState = Player.STATE_READY)))
        assertEquals(0, playerView.controllerShowTimeoutMs)
        assertFalse(playerView.controllerHideOnTouch)
        assertEquals(View.VISIBLE, screen.backdrop.visibility)

        screen.update(null)
        assertEquals(REST_MS, playerView.controllerShowTimeoutMs)
        assertTrue(playerView.controllerHideOnTouch)
        assertEquals(View.GONE, screen.backdrop.visibility)
        assertEquals(View.GONE, screen.header.visibility)
    }

    @Test
    fun aDlnaTvHasNoVolumeSlider() {
        screen.update(look(TvState(playbackState = Player.STATE_READY), volume = null))
        assertFalse(screen.volumeShown)

        screen.update(look(TvState(playbackState = Player.STATE_READY), volume = 0.5f))
        assertTrue(screen.volumeShown)
    }

    /** The bug itself: PlayerView's spinner follows onPlaybackStateChanged, which a TV that started playing never sent. */
    @Test
    fun theTvStartingToPlayReachesThePlayerViewsListeners() {
        val shown = ViewPlayer(exo, { tv }, {}, { false })
        shown.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    heard += playbackState
                }
            },
        )
        tvState = TvState(playbackState = Player.STATE_BUFFERING)
        shown.tvChanged()
        tvState = TvState(playbackState = Player.STATE_READY, playWhenReady = true)
        shown.tvChanged()

        assertEquals(Player.STATE_READY, heard.last())
        assertTrue(shown.isPlaying)
    }

    @Test
    fun whatTheTvDoesInWords() {
        assertEquals(TvLine.LOADING, TvLine.of(TvState(playbackState = Player.STATE_BUFFERING)))
        assertEquals(TvLine.PLAYING, TvLine.of(TvState(playbackState = Player.STATE_READY, playWhenReady = true)))
        assertEquals(TvLine.PAUSED, TvLine.of(TvState(playbackState = Player.STATE_READY, playWhenReady = false)))
        assertEquals(TvLine.FINISHED, TvLine.of(TvState(playbackState = Player.STATE_ENDED)))
        assertEquals(TvLine.STOPPED, TvLine.of(TvState(playbackState = Player.STATE_IDLE)))
    }

    private companion object {
        const val REST_MS = 4_000
    }
}
