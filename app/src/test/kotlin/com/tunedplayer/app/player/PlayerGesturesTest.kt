package com.tunedplayer.app.player

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Tapping the video while the controls showed hid them and then showed them again (Melvin, 2026-11-18): the finger's
 * lift toggled them through PlayerView's click, and the confirmed single tap toggled them back. One tap is one toggle.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerGesturesTest {
    private lateinit var activity: Activity
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        player = ExoPlayer.Builder(activity).build()
        view = PlayerView(activity)
        view.player = player
        view.controllerShowTimeoutMs = 0
        view.setControllerAnimationEnabled(false)
        val root = FrameLayout(activity)
        root.addView(view, FrameLayout.LayoutParams(WIDTH, HEIGHT))
        activity.setContentView(root)
        view.layout(0, 0, WIDTH, HEIGHT)
        val gestures = PlayerGestures(activity, view, isLive = { true }, onSeek = {}, onChannel = {}, bars = LevelBars(activity))
        view.setOnTouchListener(gestures)
        idle()
    }

    @After
    fun tearDown() = player.release()

    @Test
    fun aTapHidesShownControlsAndTheyStayHidden() {
        view.showController()
        idle()
        assertEquals(View.VISIBLE, controllerVisibility())

        tap()

        assertEquals(View.GONE, controllerVisibility())
    }

    @Test
    fun aTapShowsHiddenControlsAndTheyStayShown() {
        view.hideController()
        idle()
        assertEquals(View.GONE, controllerVisibility())

        tap()

        assertEquals(View.VISIBLE, controllerVisibility())
    }

    private fun tap() {
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, X, Y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(down, down + TAP_MS, MotionEvent.ACTION_UP, X, Y, 0))
        // Past the double-tap window, so the single tap is confirmed.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout() + SETTLE_MS))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun controllerVisibility(): Int = view.findViewById<View>(androidx.media3.ui.R.id.exo_controller).visibility

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080

        // Away from the centre, where the play button would take the tap, and above the bar at the bottom.
        const val X = 150f
        const val Y = 300f
        const val TAP_MS = 50L
        const val SETTLE_MS = 500L
    }
}
