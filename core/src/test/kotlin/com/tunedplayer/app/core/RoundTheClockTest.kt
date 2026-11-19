package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoundTheClockTest {
    @Test
    fun `the usual ways of writing 24-7 count`() {
        for (text in listOf(
            "24/7 Friends",
            "US| 24/7 CARTOONS",
            "24-7 Seinfeld",
            "247: The Simpsons",
            "Friends 24x7",
            "24 / 7 Movies",
            "24 7 Kids",
        )) {
            assertTrue(RoundTheClock.is247("Entertainment", text), text)
        }
        assertTrue(RoundTheClock.is247("EN| 24/7 SHOWS", "Friends"))
    }

    @Test
    fun `other numbers do not`() {
        for (text in listOf("CNN", "Channel 2470", "1247 Radio", "Sky Sports 24", "Fox 7 News", "24 Hours in A&E")) {
            assertFalse(RoundTheClock.is247("News", text), text)
        }
    }
}
