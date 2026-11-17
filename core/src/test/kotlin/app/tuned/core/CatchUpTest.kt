package app.tuned.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchUpTest {
    private val hour = 3_600_000L

    @Test
    fun readsTheStreamIdFromLiveLinks() {
        assertEquals("123", CatchUp.streamId("http://tv.example:8080/live/u/p/123.ts"))
        assertEquals("9", CatchUp.streamId("http://tv.example/u/p/9"))
        assertEquals("77", CatchUp.streamId("http://tv.example/live/u/p/77.m3u8?token=1"))
        assertNull(CatchUp.streamId("http://tv.example/channel/news"))
    }

    @Test
    fun daysComeOnlyFromArchivedChannelsAndAreCapped() {
        assertEquals(3, CatchUp.days("1", "3"))
        assertEquals(0, CatchUp.days("0", "3"))
        assertEquals(0, CatchUp.days(null, null))
        assertEquals(CatchUp.MAX_DAYS, CatchUp.days("1", "30"))
    }

    @Test
    fun onlyProgrammesThatStartedWithinTheArchiveCanBeReplayed() {
        val now = 100 * 24 * hour
        assertTrue(CatchUp.available(now - hour, now, 1))
        assertFalse(CatchUp.available(now - 25 * hour, now, 1))
        assertFalse(CatchUp.available(now + hour, now, 1))
        assertFalse(CatchUp.available(now - hour, now, 0))
    }

    @Test
    fun aProgrammeRoundsUpToWholeMinutes() {
        assertEquals(60, CatchUp.minutes(0, hour))
        assertEquals(61, CatchUp.minutes(0, hour + 1))
    }

    @Test
    fun theTimeshiftLinkUsesTheServersTimeZone() {
        val start = ZonedDateTime.of(2026, 11, 17, 20, 5, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(
            "http://tv.example/timeshift/u/p/90/2026-11-17:15-05/123.ts",
            XtreamUrls.timeshift("tv.example", "u", "p", "123", start, 90, ZoneId.of("America/Toronto")),
        )
    }

    @Test
    fun readsTheServerTimeZone() {
        val body = """{"user_info":{"auth":1},"server_info":{"url":"x","timezone":"America\/Toronto"}}"""
        assertEquals("America/Toronto", XtreamAccount.timezone(body))
        assertNull(XtreamAccount.timezone("""{"user_info":{"auth":1}}"""))
    }
}
