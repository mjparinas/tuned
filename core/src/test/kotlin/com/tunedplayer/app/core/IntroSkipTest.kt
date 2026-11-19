package com.tunedplayer.app.core

import com.tunedplayer.app.core.IntroSkip.Intro
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntroSkipTest {
    @Test
    fun offersTheDefaultLengthUntilAShowIsLearned() {
        assertEquals(Intro(0, 85_000), IntroSkip.intro(null, 85_000))
        assertEquals(Intro(90_000, 150_000), IntroSkip.intro(Intro(90_000, 150_000), 85_000))
        assertNull(IntroSkip.intro(Intro(90_000, 150_000), 0)) // Off in Settings
    }

    @Test
    fun theButtonShowsAroundTheIntroOnly() {
        val intro = Intro(90_000, 150_000)
        assertFalse(IntroSkip.showButton(10_000, intro))
        assertTrue(IntroSkip.showButton(80_000, intro))
        assertTrue(IntroSkip.showButton(140_000, intro))
        assertFalse(IntroSkip.showButton(148_000, intro))
        assertFalse(IntroSkip.showButton(5_000, null))
        assertTrue(IntroSkip.showButton(0, Intro(0, 85_000)))
    }

    @Test
    fun learnsASkipNearTheStart() {
        assertEquals(Intro(60_000, 120_000), IntroSkip.learn(60_000, 120_000))
        assertNull(IntroSkip.learn(60_000, 65_000)) // a nudge, not an intro
        assertNull(IntroSkip.learn(20 * 60_000L, 21 * 60_000L)) // the middle of the episode
        assertNull(IntroSkip.learn(60_000, 30 * 60_000L)) // jumping far ahead isn't skipping an intro
        assertNull(IntroSkip.learn(120_000, 60_000)) // going back
    }

    @Test
    fun tapsInARowReadAsOneIntro() {
        val first = IntroSkip.learn(10_000, 40_000)
        val second = IntroSkip.learn(41_000, 71_000, first)
        assertEquals(Intro(10_000, 71_000), second)
        assertEquals(Intro(10_000, 101_000), IntroSkip.learn(72_000, 102_000 - 1_000, second))
        // A later, separate skip starts a new intro.
        assertEquals(Intro(150_000, 200_000), IntroSkip.learn(150_000, 200_000, second))
    }

    @Test
    fun roundTrips() {
        val intro = Intro(61_000, 122_500)
        assertEquals(intro, IntroSkip.decode(IntroSkip.encode(intro)))
        assertNull(IntroSkip.decode(null))
        assertNull(IntroSkip.decode("12"))
        assertNull(IntroSkip.decode("9:3"))
        assertNull(IntroSkip.decode("a:b"))
    }

    @Test
    fun anIntroLongerThanTwoMinutesIsNotBelieved() {
        assertNull(IntroSkip.learn(10_000, 150_000)) // 2:20 of "intro"
        assertEquals(Intro(10_000, 130_000), IntroSkip.learn(10_000, 130_000))
        // One saved before the limit existed is ignored, and the Settings length is offered instead.
        assertEquals(Intro(0, 30_000), IntroSkip.intro(Intro(31_000, 161_000), 30_000))
    }

    @Test
    fun fastForwardingWithSkipForwardDoesNotKeepTheButtonOnScreen() {
        // Melvin, 2026-11-19: the button was still up at 2:00 with Skip intro set to 30 s. He pressed Skip intro, then
        // +30 four times; each tap stretched the "intro" and the button followed him.
        val lesson = IntroSkip.Lesson()
        assertFalse(lesson.skipped)
        assertTrue(lesson.seek(31_000, 61_000))
        assertTrue(lesson.skipped) // past the intro by hand: no button for the rest of this episode
        lesson.seek(62_000, 92_000)
        lesson.seek(93_000, 123_000)
        assertEquals(Intro(31_000, 123_000), lesson.intro)
        lesson.seek(124_000, 154_000) // past two minutes: fast-forwarding, not an intro
        assertNull(lesson.intro)
        assertTrue(lesson.spoiled)
        assertFalse(lesson.seek(10_000, 70_000)) // and nothing more is learned from this episode
        assertTrue(lesson.skipped)
    }

    @Test
    fun aSeekBackOrInTheMiddleTeachesNothing() {
        val lesson = IntroSkip.Lesson()
        assertFalse(lesson.seek(20 * 60_000L, 21 * 60_000L))
        assertFalse(lesson.seek(90_000, 30_000))
        assertFalse(lesson.skipped)
    }
}
