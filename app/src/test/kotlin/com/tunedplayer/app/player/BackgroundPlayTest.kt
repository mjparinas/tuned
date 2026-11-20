package com.tunedplayer.app.player

import android.app.Activity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When leaving the player keeps the sound going (Melvin, 2026-11-19: "do 3, but have a setting that can toggle this off"). */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundPlayTest {
    private lateinit var activity: Activity
    private lateinit var exo: ExoPlayer
    private val scope = TestScope(StandardTestDispatcher())

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        exo = ExoPlayer.Builder(activity).build()
        exo.playWhenReady = true
    }

    @After
    fun tearDown() = exo.release()

    private fun background(
        enabled: Boolean = true,
        casting: Boolean = false,
    ) = BackgroundPlay(activity, scope, { enabled }, { exo }, { casting })

    @Test
    fun leavingThePlayerKeepsPlaying() {
        assertTrue(background().keepPlaying(inPictureInPicture = false))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun theSettingTurnsItOff() {
        assertFalse(background(enabled = false).keepPlaying(inPictureInPicture = false))
    }

    @Test
    fun aCastOrAPauseEndsAsBefore() {
        assertFalse(background(casting = true).keepPlaying(inPictureInPicture = false))
        exo.playWhenReady = false
        assertFalse(background().keepPlaying(inPictureInPicture = false))
    }

    @Test
    fun closingThePictureInPictureWindowStillStops() {
        // The screen is on, so the small window was closed rather than the phone locked.
        assertFalse(background().keepPlaying(inPictureInPicture = true))
    }

    @Test
    fun aLongPauseOutOfSightClosesThePlayer() {
        // Not playing (nothing was prepared), so the idle clock starts at once.
        background().keepPlaying(inPictureInPicture = false)
        scope.advanceTimeBy(BackgroundPlay.IDLE_MS + 1)
        scope.runCurrent()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun comingBackCancelsTheIdleClock() {
        val play = background()
        play.keepPlaying(inPictureInPicture = false)
        play.onStart()
        scope.advanceTimeBy(BackgroundPlay.IDLE_MS + 1)
        scope.runCurrent()
        assertFalse(activity.isFinishing)
    }
}
