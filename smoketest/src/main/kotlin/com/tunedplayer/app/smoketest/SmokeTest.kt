package com.tunedplayer.app.smoketest

import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tunedplayer.app.fakeprovider.FakeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The github debug app, freshly installed, against the fake provider: add a playlist link, see the channels, play one,
 * change channel twice with the remote, and go back to the list. At no moment may two stream connections be open
 * (AGENTS.md: "one stream connection, ever"); the fake provider counts them.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun freshInstall() {
        FakeProvider.start()
        device.executeShellCommand("pm clear $PACKAGE")
        FakeProvider.reset()
    }

    @Test
    fun signInPlayChangeChannelAndGoBack() {
        launch()
        device.wait(Until.findObject(By.text("Playlist link")), SCREEN_MS).click()
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), SCREEN_MS).text = FakeProvider.PLAYLIST_URL
        device.findObject(By.text("Add playlist")).click()
        val first = device.wait(Until.findObject(By.text(FakeProvider.FIRST_CHANNEL)), SIGN_IN_MS)
        checkNotNull(first) { "Live TV never listed the channels" }

        first.click()
        awaitStreams(1)
        Thread.sleep(PLAY_MS)
        dismissFullScreenNote()
        device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
        awaitStreams(2)
        Thread.sleep(PLAY_MS)
        dismissFullScreenNote()
        device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
        awaitStreams(3)
        Thread.sleep(PLAY_MS)

        assertTrue("Back didn't return to Live TV", leavePlayer())
        assertEquals("streams opened: ${FakeProvider.streamsRequested}", 1, FakeProvider.mostOpenAtOnce)
    }

    /**
     * Melvin, 2026-11-18: "If somebody is watching a show and they are downloading shows, the app should prioritize the
     * stream and have the downloads wait. Remember only 1 stream at a time." A film downloads; playing a channel stops
     * it before the channel's stream opens, and it carries on once the player closes.
     */
    @Test
    fun aDownloadWaitsWhileAChannelPlays() {
        launch()
        signIn()
        device.wait(Until.findObject(By.text("Movies")), SCREEN_MS).click()
        val film = device.wait(Until.findObject(By.text(FakeProvider.FIRST_FILM)), SCREEN_MS)
        checkNotNull(film) { "Movies never listed the films" }
        film.click()
        val download = device.wait(Until.findObject(By.desc("Download")), SCREEN_MS)
        checkNotNull(download) { "The film page has no Download button" }
        download.click()
        awaitFilmRequests(1)

        device.pressBack()
        device.wait(Until.findObject(By.text("Live TV")), SCREEN_MS).click()
        device.wait(Until.findObject(By.text(FakeProvider.FIRST_CHANNEL)), SCREEN_MS).click()
        awaitStreams(1, channelsOnly = true)
        Thread.sleep(PLAY_MS)
        assertEquals("streams opened: ${FakeProvider.streamsRequested}", 1, FakeProvider.mostOpenAtOnce)

        assertTrue("Back didn't return to Live TV", leavePlayer())
        // The channel carries on in the mini player, so the download still waits; Back on Live TV closes it.
        closeMiniPlayer()
        awaitFilmRequests(2)
        Thread.sleep(PLAY_MS)
        assertEquals("streams opened: ${FakeProvider.streamsRequested}", 1, FakeProvider.mostOpenAtOnce)
    }

    /**
     * Melvin, 2026-11-21: Back on live TV keeps the channel playing "in a small video somewhere out of the way" while
     * browsing, and "when i find something i like and click on it, it should stop the mini live tv from streaming
     * first before it streams the new show". The same stream carries on into the mini player (no new request), the
     * next pick opens only after it hangs up, and Back on Live TV closes it.
     */
    @Test
    fun liveTvKeepsPlayingInTheMiniPlayerAndHangsUpForTheNextPick() {
        val pm = instrumentation.targetContext.packageManager
        assumeTrue("no picture-in-picture on this emulator", pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        launch()
        signIn()
        device.wait(Until.findObject(By.text(FakeProvider.FIRST_CHANNEL)), SCREEN_MS).click()
        awaitStreams(1)
        Thread.sleep(PLAY_MS)
        val asked = FakeProvider.streamsRequested.size

        assertTrue("Back didn't return to Live TV", leavePlayer())
        assertTrue("Back didn't shrink the player into the mini player", await { inMiniPlayer() })
        Thread.sleep(PLAY_MS)
        assertEquals("the mini player reconnected: ${FakeProvider.streamsRequested}", asked, FakeProvider.streamsRequested.size)
        assertEquals("the mini player's stream isn't open", 1, FakeProvider.openNow)

        val next = device.findObjects(By.textStartsWith("Channel ")).firstOrNull { it.text != FakeProvider.FIRST_CHANNEL }
        checkNotNull(next) { "Live TV lists no other channel" }
        next.click()
        awaitStreams(2)
        Thread.sleep(PLAY_MS)
        assertTrue("the next pick stayed in the small window", await { !inMiniPlayer() })
        assertEquals("streams opened: ${FakeProvider.streamsRequested}", 1, FakeProvider.mostOpenAtOnce)

        assertTrue("Back didn't return to Live TV", leavePlayer())
        assertTrue("Back didn't shrink the player into the mini player", await { inMiniPlayer() })
        closeMiniPlayer()
        assertTrue("Back on Live TV didn't close the mini player", await { !inMiniPlayer() })
        assertEquals("streams opened: ${FakeProvider.streamsRequested}", 1, FakeProvider.mostOpenAtOnce)
    }

    /** Back on Live TV with the mini player showing closes it, and its stream hangs up. */
    private fun closeMiniPlayer() {
        device.pressBack()
        check(await { FakeProvider.openNow == 0 }) { "the mini player's stream is still open" }
    }

    /** The player is in Android's picture-in-picture window. */
    private fun inMiniPlayer(): Boolean = device.executeShellCommand("dumpsys activity activities").contains("mode=pinned")

    private fun await(condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + STREAM_MS
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun signIn() {
        device.wait(Until.findObject(By.text("Playlist link")), SCREEN_MS).click()
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), SCREEN_MS).text = FakeProvider.PLAYLIST_URL
        device.findObject(By.text("Add playlist")).click()
        checkNotNull(device.wait(Until.findObject(By.text(FakeProvider.FIRST_CHANNEL)), SIGN_IN_MS)) { "Live TV never listed the channels" }
    }

    /** Waits until the film has been asked for [count] times (the download, then again once it carries on). */
    private fun awaitFilmRequests(count: Int) {
        val until = System.currentTimeMillis() + DOWNLOAD_MS
        while (System.currentTimeMillis() < until) {
            if (FakeProvider.streamsRequested.count { it.startsWith("/movie/") } >= count) return
            Thread.sleep(POLL_MS)
        }
        error("Waited for the film's download to be asked for $count times; asked for: ${FakeProvider.streamsRequested}")
    }

    private fun launch() {
        val context = instrumentation.context
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE)) { "$PACKAGE isn't installed" }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), SCREEN_MS)
    }

    /** Android's "Viewing full screen" note shows over the first full-screen video and takes the next key or Back. */
    private fun dismissFullScreenNote() {
        device.findObject(By.text("GOT IT"))?.click()
    }

    private fun leavePlayer(): Boolean {
        repeat(BACK_TRIES) {
            dismissFullScreenNote()
            device.pressBack()
            if (device.wait(Until.hasObject(By.textStartsWith("Channel ")), BACK_MS)) return true
        }
        return false
    }

    /** Waits until [count] different channels (or, without [channelsOnly], streams) have been asked for. */
    private fun awaitStreams(
        count: Int,
        channelsOnly: Boolean = false,
    ) {
        val until = System.currentTimeMillis() + STREAM_MS
        while (System.currentTimeMillis() < until) {
            val asked = FakeProvider.streamsRequested.filter { !channelsOnly || it.startsWith("/live/") }
            if (asked.toSet().size >= count) return
            Thread.sleep(POLL_MS)
        }
        error("Waited for $count channels to play; asked for: ${FakeProvider.streamsRequested}")
    }

    private companion object {
        const val PACKAGE = "com.tunedplayer.app.debug"
        const val SCREEN_MS = 15_000L
        const val SIGN_IN_MS = 120_000L
        const val STREAM_MS = 20_000L
        const val DOWNLOAD_MS = 60_000L
        const val PLAY_MS = 3_000L
        const val POLL_MS = 200L
        const val BACK_TRIES = 3
        const val BACK_MS = 3_000L
    }
}
