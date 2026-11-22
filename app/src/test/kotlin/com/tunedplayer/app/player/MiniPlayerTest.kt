package com.tunedplayer.app.player

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mini player (Melvin, 2026-11-21): Back keeps live TV, films and episodes playing in a small window while browsing,
 * and "when i find something i like and click on it, it should stop the mini live tv from streaming first before it
 * streams the new show". One stream connection, ever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiniPlayerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun main() = Dispatchers.setMain(dispatcher)

    @After
    fun reset() = Dispatchers.resetMain()

    /** A provider that counts open streams, as the fake provider in the device smoke test does. */
    private class Provider {
        var open = 0
        var most = 0

        fun connect() {
            open++
            most = maxOf(most, open)
        }

        fun hangUp() {
            open--
        }
    }

    /** The mini player's stream hangs up a moment after it is closed, as a real player's release does. */
    private fun TestScope.miniPlaying(provider: Provider): Pair<() -> Unit, MutableStateFlow<Boolean>> {
        val playerOpen = MutableStateFlow(true)
        provider.connect()
        val close: () -> Unit = {
            launch {
                delay(HANG_UP_MS)
                provider.hangUp()
                playerOpen.value = false
            }
        }
        return close to playerOpen
    }

    @Test
    fun `something new picked opens only once the mini player has hung up`() = runTest(dispatcher) {
        val provider = Provider()
        val (close, playerOpen) = miniPlaying(provider)
        var openedWhileMiniOpen: Boolean? = null

        MiniPlayer.handOver(close, playerOpen) {
            openedWhileMiniOpen = playerOpen.value
            provider.connect()
        }
        advanceUntilIdle()

        assertEquals(false, openedWhileMiniOpen)
        assertEquals(1, provider.open)
        assertEquals(1, provider.most, "two streams were open at once")
    }

    @Test
    fun `a player that never says it closed still lets the pick open, after the wait`() = runTest(dispatcher) {
        val playerOpen = MutableStateFlow(true)
        var started = false
        MiniPlayer.handOver({}, playerOpen) { started = true }
        advanceUntilIdle()
        assertTrue(started)
        assertEquals(MiniPlayer.HANG_UP_MS, testScheduler.currentTime)
    }

    @Test
    fun `downloads wait through the hand-over and not after it`() = runTest(dispatcher) {
        val provider = Provider()
        val (close, playerOpen) = miniPlaying(provider)
        var duringClose: Boolean? = null
        val closeAndLook: () -> Unit = {
            close()
            duringClose = MiniPlayer.handingOver
        }
        MiniPlayer.handOver(closeAndLook, playerOpen) {}
        advanceUntilIdle()
        assertEquals(true, duringClose)
        assertFalse(MiniPlayer.handingOver)
    }

    @Test
    fun `only the player screen that shrank can clear the mini player`() {
        val mine: () -> Unit = {}
        val other: () -> Unit = {}
        MiniPlayer.shown(PlayRequest(7, null, 0, 0), mine)
        MiniPlayer.gone(other)
        assertEquals(PlayRequest(7, null, 0, 0), MiniPlayer.showing.value)
        MiniPlayer.gone(mine)
        assertNull(MiniPlayer.showing.value)
    }

    @Test
    fun `closing asks the player screen to close, once there is one`() {
        assertFalse(MiniPlayer.close())
        var closed = 0
        val close: () -> Unit = { closed++ }
        MiniPlayer.shown(PlayRequest(7, null, 0, 0), close)
        assertTrue(MiniPlayer.close())
        assertEquals(1, closed)
        MiniPlayer.gone(close)
    }

    @Test
    fun `the player screen shows in the mini player while small and clears it when it closes`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java, PlayerActivity.intent(context(), 7)).setup()
        val screen = activity.get()
        MiniPlayer.Screen(screen, { AppSettings() }, { 7L }, { false }, { null }) { error("not drawn") }
        screen.onPictureInPictureModeChanged(true, Configuration(screen.resources.configuration))
        assertEquals(PlayRequest(7, null, 0, 0), MiniPlayer.showing.value)
        screen.onPictureInPictureModeChanged(false, Configuration(screen.resources.configuration))
        assertNull(MiniPlayer.showing.value)
        screen.onPictureInPictureModeChanged(true, Configuration(screen.resources.configuration))
        activity.destroy()
        assertNull(MiniPlayer.showing.value)
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `Back shrinks regular content playing on this device with the app open`() {
        assertTrue(shrinks())
    }

    @Test
    fun `live channels films and episodes are regular mini player content but one-off videos are not`() {
        assertTrue(MiniPlayer.regularContent(7L))
        assertFalse(MiniPlayer.regularContent(CatchUpLinks.ID), "catch-up or a recording")
        assertFalse(MiniPlayer.regularContent(null), "nothing loaded")
    }

    @Test
    fun `Back closes the player as before otherwise`() {
        assertFalse(shrinks(enabled = false), "the setting is off")
        assertFalse(shrinks(supported = false), "no picture-in-picture on this device")
        assertFalse(shrinks(regularContent = false), "catch-up or a recording")
        assertFalse(shrinks(casting = true), "closing the player ends the cast")
        assertFalse(shrinks(playing = false), "paused")
        assertFalse(shrinks(appOpen = false), "opened from a widget, with no lists to browse")
    }

    private fun shrinks(
        enabled: Boolean = true,
        supported: Boolean = true,
        regularContent: Boolean = true,
        casting: Boolean = false,
        playing: Boolean = true,
        appOpen: Boolean = true,
    ) = MiniPlayer.shrinksOnBack(enabled, supported, regularContent, casting, playing, appOpen)

    private companion object {
        const val HANG_UP_MS = 400L
    }
}
