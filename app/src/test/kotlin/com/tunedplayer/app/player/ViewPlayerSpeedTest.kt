package com.tunedplayer.app.player

import android.app.Activity
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

/** Playback speed is for films and episodes; live TV never offers it (Melvin, 2026-11-19: "remove that for live tv"). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewPlayerSpeedTest {
    private lateinit var exo: ExoPlayer

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        exo = ExoPlayer.Builder(activity).build()
    }

    @After
    fun tearDown() = exo.release()

    private fun shown(live: Boolean) = ViewPlayer(exo, { null }, {}, { false }, { live })

    @Test
    fun liveTvHasNoSpeedSetting() {
        val player = shown(live = true)

        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
        assertFalse(player.availableCommands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
        player.setPlaybackSpeed(2f)
        assertEquals(1f, exo.playbackParameters.speed)
    }

    @Test
    fun filmsAndEpisodesChangeSpeed() {
        val player = shown(live = false)

        assertTrue(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
        player.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, exo.playbackParameters.speed)
    }
}
