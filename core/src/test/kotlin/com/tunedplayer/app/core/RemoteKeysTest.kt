package com.tunedplayer.app.core

import com.tunedplayer.app.core.RemoteKeys.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteKeysTest {
    @Test
    fun arrowsChangeChannelOnLiveTvWhileTheControlsAreHidden() {
        assertEquals(Action.NEXT_CHANNEL, RemoteKeys.action(RemoteKeys.DPAD_UP, live = true, controlsShown = false))
        assertEquals(Action.PREVIOUS_CHANNEL, RemoteKeys.action(RemoteKeys.DPAD_DOWN, live = true, controlsShown = false))
        assertEquals(Action.NONE, RemoteKeys.action(RemoteKeys.DPAD_LEFT, live = true, controlsShown = false))
    }

    @Test
    fun arrowsSkipInFilms() {
        assertEquals(Action.SEEK_FORWARD, RemoteKeys.action(RemoteKeys.DPAD_RIGHT, live = false, controlsShown = false))
        assertEquals(Action.SEEK_BACK, RemoteKeys.action(RemoteKeys.DPAD_LEFT, live = false, controlsShown = false))
        assertEquals(Action.NONE, RemoteKeys.action(RemoteKeys.DPAD_UP, live = false, controlsShown = false))
    }

    @Test
    fun arrowsMoveBetweenButtonsOnceTheControlsShow() {
        for (key in listOf(RemoteKeys.DPAD_UP, RemoteKeys.DPAD_DOWN, RemoteKeys.DPAD_LEFT, RemoteKeys.DPAD_RIGHT)) {
            assertEquals(Action.NONE, RemoteKeys.action(key, live = true, controlsShown = true))
            assertEquals(Action.NONE, RemoteKeys.action(key, live = false, controlsShown = true))
        }
    }

    @Test
    fun channelGuideAndInfoButtonsAlwaysWork() {
        assertEquals(Action.NEXT_CHANNEL, RemoteKeys.action(RemoteKeys.CHANNEL_UP, live = true, controlsShown = true))
        assertEquals(Action.PREVIOUS_CHANNEL, RemoteKeys.action(RemoteKeys.PAGE_DOWN, live = true, controlsShown = false))
        assertEquals(Action.GUIDE, RemoteKeys.action(RemoteKeys.GUIDE, live = true, controlsShown = true))
        assertEquals(Action.GUIDE, RemoteKeys.action(RemoteKeys.MENU, live = true, controlsShown = false))
        assertEquals(Action.INFO, RemoteKeys.action(RemoteKeys.INFO, live = false, controlsShown = true))
    }

    @Test
    fun fireTvMenuButtonShowsTheControlsInFilms() {
        assertEquals(Action.INFO, RemoteKeys.action(RemoteKeys.MENU, live = false, controlsShown = false))
        assertEquals(Action.INFO, RemoteKeys.action(RemoteKeys.MENU, live = false, controlsShown = true))
    }

    @Test
    fun channelButtonsDoNothingInFilms() {
        assertEquals(Action.NONE, RemoteKeys.action(RemoteKeys.CHANNEL_UP, live = false, controlsShown = false))
        assertEquals(Action.NONE, RemoteKeys.action(RemoteKeys.GUIDE, live = false, controlsShown = false))
    }

    @Test
    fun numberKeysTypeAChannelNumber() {
        assertEquals(0, RemoteKeys.digit(RemoteKeys.DIGIT_0))
        assertEquals(9, RemoteKeys.digit(RemoteKeys.NUMPAD_9))
        assertEquals(null, RemoteKeys.digit(RemoteKeys.DPAD_UP))
        assertEquals("12", RemoteKeys.typeDigit("1", 2))
        assertEquals("23456", RemoteKeys.typeDigit("12345", 6))
    }

    @Test
    fun lastChannelFlipsBackOnLiveTvOnly() {
        assertEquals(Action.LAST_CHANNEL, RemoteKeys.action(RemoteKeys.LAST_CHANNEL, live = true, controlsShown = true))
        assertEquals(Action.NONE, RemoteKeys.action(RemoteKeys.LAST_CHANNEL, live = false, controlsShown = false))
    }
}
