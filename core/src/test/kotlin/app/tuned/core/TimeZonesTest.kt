package app.tuned.core

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeZonesTest {
    // Los Angeles rather than Vancouver: British Columbia now stays on UTC−7 all year, so its offset no longer
    // changes in the autumn, and these tests need a zone that does.
    private val pacific = ZoneId.of("America/Los_Angeles")
    private val toronto = ZoneId.of("America/Toronto")

    private fun at(
        text: String,
        zone: ZoneId,
    ): Long = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    @Test
    fun blankFollowsTheDevice() {
        assertEquals(toronto, TimeZones.resolve(TimeZones.DEVICE, device = toronto))
    }

    @Test
    fun anUnknownZoneFollowsTheDevice() {
        assertEquals(toronto, TimeZones.resolve("Mars/Olympus_Mons", device = toronto))
    }

    @Test
    fun aChosenZoneWinsOverTheDevice() {
        assertEquals(pacific, TimeZones.resolve("America/Los_Angeles", device = toronto))
    }

    @Test
    fun everyChoiceIsAZoneThePhoneKnows() {
        TimeZones.CHOICES.forEach { ZoneId.of(it) }
        assertEquals(TimeZones.CHOICES.size, TimeZones.CHOICES.toSet().size)
    }

    @Test
    fun tomorrowStartsAtMidnightInTheChosenZone() {
        // 9:17 p.m. Tuesday in Los Angeles is already Wednesday in Toronto.
        val now = at("2026-11-17T21:17", pacific)
        assertEquals(at("2026-11-18T00:00", pacific), TimeZones.nextMidnight(now, pacific))
        assertEquals(at("2026-11-19T00:00", toronto), TimeZones.nextMidnight(now, toronto))
    }

    @Test
    fun midnightOnAClockChangeDayIsStillTheNextDay() {
        val now = at("2026-11-01T12:00", pacific) // the day clocks fall back
        assertEquals(at("2026-11-02T00:00", pacific), TimeZones.nextMidnight(now, pacific))
    }

    @Test
    fun theSameMomentCanBeTodayInOneZoneAndTomorrowInAnother() {
        val now = at("2026-11-17T21:00", pacific)
        val event = at("2026-11-17T23:30", pacific) // 2:30 a.m. Wednesday in Toronto
        assertTrue(TimeZones.sameDay(now, event, pacific))
        assertFalse(TimeZones.sameDay(now, event, toronto))
    }

    @Test
    fun aGuideTimeInUtcReadsInLocalTime() {
        val start = XmltvParser.parseTime("20261118050000 +0000")!!
        val local =
            java.time.Instant
                .ofEpochMilli(start)
                .atZone(pacific)
        assertEquals(21, local.hour) // 9 p.m. Tuesday Pacific
        assertEquals(17, local.dayOfMonth)
    }

    @Test
    fun offsetsReadPlainly() {
        val now = at("2026-11-17T12:00", pacific)
        assertEquals("UTC−8", TimeZones.offsetLabel(pacific, now))
        assertEquals("UTC+5:30", TimeZones.offsetLabel(ZoneId.of("Asia/Kolkata"), now))
        assertEquals("UTC", TimeZones.offsetLabel(ZoneId.of("UTC"), now))
    }

    @Test
    fun choicesNameTheCityAndOffset() {
        val now = at("2026-11-17T12:00", pacific)
        val label = TimeZones.label("America/Los_Angeles", now, Locale.CANADA)
        assertTrue(label.startsWith("Los Angeles · "), label)
        assertTrue(label.endsWith("UTC−8)") || label.endsWith("UTC−8"), label)
        val vancouver = TimeZones.label("America/Vancouver", now, Locale.CANADA)
        assertTrue(vancouver.endsWith("UTC−7)") || vancouver.endsWith("UTC−7"), vancouver)
        assertEquals("UTC", TimeZones.label("UTC", now, Locale.CANADA))
        assertEquals("Buenos Aires", TimeZones.label("America/Argentina/Buenos_Aires", now, Locale.CANADA).substringBefore(" ·"))
    }
}
