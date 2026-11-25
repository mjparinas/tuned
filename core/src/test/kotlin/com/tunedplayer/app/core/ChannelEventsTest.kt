package com.tunedplayer.app.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelEventsTest {
    private val toronto = ZoneId.of("America/Toronto")
    private val vancouver = ZoneId.of("America/Vancouver")

    // Wednesday 25 November 2026, noon in Toronto.
    private val now = at(2026, 11, 25, 12, 0, toronto)

    private fun at(
        y: Int,
        mo: Int,
        d: Int,
        h: Int,
        mi: Int,
        zone: ZoneId,
    ) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun parse(
        name: String,
        zone: ZoneId = toronto,
    ) = ChannelEvents.parse(name, now, zone)

    @Test
    fun slotAndTimeComeOff() {
        assertEquals(ChannelEvents.Named("UFC 312", at(2026, 11, 25, 22, 0, toronto)), parse("PPV 7: UFC 312 10PM ET"))
        assertEquals(
            ChannelEvents.Named("Leafs vs Canucks", at(2026, 11, 25, 19, 0, toronto)),
            parse("US| NHL 05: Leafs vs Canucks @ 7:00 PM EST"),
        )
        assertEquals(
            ChannelEvents.Named("Arsenal v Chelsea", at(2026, 11, 25, 20, 0, ZoneId.of("Europe/London"))),
            parse("UK: EPL 01 | Arsenal v Chelsea 20:00 GMT"),
        )
    }

    @Test
    fun dateAndWeekdayAreRead() {
        assertEquals(
            ChannelEvents.Named("Canelo vs Crawford", at(2026, 11, 28, 21, 0, toronto)),
            parse("DAZN PPV 1: Canelo vs Crawford | Sat 9PM ET"),
        )
        assertEquals(
            ChannelEvents.Named("Duke @ UNC", at(2026, 11, 26, 19, 0, toronto)),
            parse("ESPN+ 12 : Duke @ UNC 11/26 07:00PM"),
        )
        assertEquals(
            ChannelEvents.Named("Lakers vs Celtics", at(2026, 11, 25, 19, 30, toronto)),
            parse("NBA 03 - Lakers vs Celtics (2026-11-25 19:30)"),
        )
        assertEquals(
            ChannelEvents.Named("Real Madrid vs Barcelona", at(2026, 11, 26, 15, 0, toronto)),
            parse("LALIGA 2: Real Madrid vs Barcelona Nov 26 3pm ET"),
        )
    }

    @Test
    fun aTimeWithoutAZoneIsInTheChosenZone() {
        assertEquals(at(2026, 11, 25, 22, 0, vancouver), parse("PPV 7: UFC 312 10PM", vancouver)?.start)
        // A second time for another coast comes off too.
        assertEquals(ChannelEvents.Named("UFC 312", at(2026, 11, 25, 22, 0, toronto)), parse("PPV 7: UFC 312 10PM ET / 7PM PT"))
    }

    @Test
    fun aTimeWithoutADateIsTheNextThatHasNotEnded() {
        // At noon, today's 1 a.m. has ended, so it's tomorrow's; 10 a.m. may still be on.
        assertEquals(at(2026, 11, 26, 1, 0, toronto), parse("PPV 2: UFC Fight Night 1AM ET")?.start)
        assertEquals(at(2026, 11, 25, 10, 0, toronto), parse("PPV 2: UFC Fight Night 10AM ET")?.start)
        assertEquals(at(2026, 11, 26, 0, 30, toronto), parse("PPV 2: UFC Fight Night 12:30AM ET")?.start)
    }

    @Test
    fun anUntimedEventHasNoStart() {
        assertEquals(ChannelEvents.Named("Leafs vs Canucks", null), parse("NHL 05: Leafs vs Canucks"))
        assertEquals(ChannelEvents.Named("UFC 312", null), parse("PPV 7: UFC 312 (Feb 8)"))
        assertEquals(ChannelEvents.Named("Lakers @ Celtics", null), parse("Lakers @ Celtics"))
    }

    @Test
    fun emptySlotsAndChannelsAreNoEvent() {
        assertNull(parse("PPV 7"))
        assertNull(parse("US| NHL 05"))
        assertNull(parse("NFL 01 -- No Event Streaming --"))
        assertNull(parse("PPV 3: Off Air"))
        assertNull(parse("US| ESPN HD"))
        assertNull(parse("Sky Sports Main Event"))
        assertNull(parse("NFL Sunday Ticket 3"))
        assertNull(parse("UK: Sky Sports Golf Open"))
        assertNull(parse("EPL 04: Arsenal v Chelsea Highlights 8PM"))
    }

    @Test
    fun wordsInNamesAreNotTimesOrDates() {
        assertEquals(ChannelEvents.Named("Bobcats vs Sun", null), parse("WNBA 2: Bobcats vs Sun"))
        assertEquals(ChannelEvents.Named("Mayweather vs Pacquiao 2", null), parse("PPV 9: Mayweather vs Pacquiao 2"))
    }

    @Test
    fun findSkipsEndedAndFarOffEvents() {
        val channels =
            listOf(
                ChannelEvents.Channel(1, "PPV 1: UFC 312 10PM ET"),
                ChannelEvents.Channel(2, "PPV 2: UFC 300 11/25 6AM ET"), // ended at 9 a.m.
                ChannelEvents.Channel(3, "PPV 3: Boxing Final 12/25 9PM ET"), // a month away
                ChannelEvents.Channel(4, "PPV 4"),
                ChannelEvents.Channel(5, "PPV 5: Canelo vs Crawford 2/30 9PM ET"), // no such date
            )
        val found = ChannelEvents.find(channels, now, now + 36 * 3_600_000L, toronto)
        assertEquals(listOf(1L), found.map { it.first })
    }

    @Test
    fun namedEventsJoinTheGuidesOrStandAlone() {
        val start = at(2026, 11, 25, 22, 0, toronto)
        val guide = listOf(Events.Event("ufc 312", "UFC 312", start, start + 3 * 3_600_000L, listOf(10)))
        val merged =
            Events.withNamed(
                guide,
                listOf(
                    1L to ChannelEvents.Named("UFC 312", start + 15 * 60_000L),
                    2L to ChannelEvents.Named("UFC 312", null),
                    3L to ChannelEvents.Named("Leafs vs Canucks", null),
                ),
                now,
            )
        assertEquals(2, merged.size)
        assertEquals(listOf(10L, 1L, 2L), merged.first { it.key == "ufc 312" }.channels)
        val untimed = merged.first { it.key == "leafs vs canucks" }
        assertFalse(untimed.timeKnown)
        assertFalse(untimed.liveAt(now))
        assertTrue(guide.first().liveAt(start))
    }
}
