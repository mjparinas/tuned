package app.tuned.core

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeZonesTest {
    private val vancouver = ZoneId.of("America/Vancouver")
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
        assertEquals(vancouver, TimeZones.resolve("America/Vancouver", device = toronto))
    }

    @Test
    fun everyChoiceIsAZoneThePhoneKnows() {
        TimeZones.CHOICES.forEach { ZoneId.of(it) }
        assertEquals(TimeZones.CHOICES.size, TimeZones.CHOICES.toSet().size)
    }

    @Test
    fun tomorrowStartsAtMidnightInTheChosenZone() {
        // 9:17 p.m. Tuesday in Vancouver is already Wednesday in Toronto.
        val now = at("2026-11-17T21:17", vancouver)
        assertEquals(at("2026-11-18T00:00", vancouver), TimeZones.nextMidnight(now, vancouver))
        assertEquals(at("2026-11-19T00:00", toronto), TimeZones.nextMidnight(now, toronto))
    }

    @Test
    fun midnightOnAClockChangeDayIsStillTheNextDay() {
        val now = at("2026-11-01T12:00", vancouver) // the day clocks fall back
        assertEquals(at("2026-11-02T00:00", vancouver), TimeZones.nextMidnight(now, vancouver))
    }

    @Test
    fun theSameMomentCanBeTodayInOneZoneAndTomorrowInAnother() {
        val now = at("2026-11-17T21:00", vancouver)
        val event = at("2026-11-17T23:30", vancouver) // 2:30 a.m. Wednesday in Toronto
        assertTrue(TimeZones.sameDay(now, event, vancouver))
        assertFalse(TimeZones.sameDay(now, event, toronto))
    }

    @Test
    fun aGuideTimeInUtcReadsInLocalTime() {
        val start = XmltvParser.parseTime("20261118050000 +0000")!!
        val local =
            java.time.Instant
                .ofEpochMilli(start)
                .atZone(vancouver)
        assertEquals(21, local.hour) // 9 p.m. Tuesday Pacific
        assertEquals(17, local.dayOfMonth)
    }

    @Test
    fun offsetsReadPlainly() {
        val now = at("2026-11-17T12:00", vancouver)
        assertEquals("UTC−8", TimeZones.offsetLabel(vancouver, now))
        assertEquals("UTC+5:30", TimeZones.offsetLabel(ZoneId.of("Asia/Kolkata"), now))
        assertEquals("UTC", TimeZones.offsetLabel(ZoneId.of("UTC"), now))
    }

    @Test
    fun choicesNameTheCityAndOffset() {
        val now = at("2026-11-17T12:00", vancouver)
        val label = TimeZones.label("America/Vancouver", now, Locale.CANADA)
        assertTrue(label.startsWith("Vancouver · "), label)
        assertTrue(label.endsWith("UTC−8)") || label.endsWith("UTC−8"), label)
        assertEquals("UTC", TimeZones.label("UTC", now, Locale.CANADA))
        assertEquals("Buenos Aires", TimeZones.label("America/Argentina/Buenos_Aires", now, Locale.CANADA).substringBefore(" ·"))
    }
}
