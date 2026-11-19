package com.tunedplayer.app.core

import java.io.BufferedReader
import java.io.StringReader
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3uCatchUpTest {
    // 2026-11-17 20:00 to 21:00 UTC, watched at 22:30.
    private val start = 1_794_945_600_000L
    private val stop = start + 3_600_000L
    private val now = start + 9_000_000L

    private fun entries(text: String) = M3uParser.entries(BufferedReader(StringReader(text))).toList()

    private fun link(
        live: String,
        catchUp: M3uCatchUp,
    ) = CatchUpTemplate.link(live, catchUp, start, stop, now, ZoneOffset.UTC)

    @Test
    fun readsTheChannelsOwnCatchUp() {
        val e =
            entries(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="a" catchup="default" catchup-days="3" catchup-source="http://h/a/{utc}-{duration}.ts",A
                http://h/a.ts
                """.trimIndent(),
            ).single()
        assertEquals(M3uCatchUp("default", 3, "http://h/a/{utc}-{duration}.ts"), e.catchUp)
    }

    @Test
    fun fallsBackToTheHeaderForChannelsThatSayNothing() {
        val e =
            entries(
                """
                #EXTM3U catchup="append" catchup-days="7" catchup-source="?start={utc}"
                #EXTINF:-1,A
                http://h/a.ts
                #EXTINF:-1 group-title="Movies",Film
                http://h/film.mp4
                """.trimIndent(),
            )
        assertEquals(M3uCatchUp("append", 7, "?start={utc}"), e[0].catchUp)
        assertNull(e[1].catchUp, "films have no catch-up")
    }

    @Test
    fun noCatchUpWithoutAModeOrSource() {
        assertNull(M3uCatchUp.from(mapOf("catchup-days" to "3"), emptyMap()))
        assertNull(M3uCatchUp.from(mapOf("catchup" to "default"), emptyMap()), "default needs a template")
        assertNull(M3uCatchUp.from(mapOf("catchup" to "vod"), emptyMap()))
    }

    @Test
    fun daysAreCappedAndDefaulted() {
        assertEquals(CatchUp.MAX_DAYS, M3uCatchUp.from(mapOf("catchup" to "shift", "catchup-days" to "30"), emptyMap())?.days)
        assertEquals(M3uCatchUp.UNKNOWN_DAYS, M3uCatchUp.from(mapOf("catchup" to "flussonic"), emptyMap())?.days)
        assertEquals(M3uCatchUp("shift", 2, null), M3uCatchUp.from(mapOf("timeshift" to "2"), emptyMap()))
        assertEquals("flussonic", M3uCatchUp.from(mapOf("catchup" to "fs"), emptyMap())?.mode)
    }

    @Test
    fun encodesAndDecodes() {
        val c = M3uCatchUp("default", 3, "http://h/{utc}")
        assertEquals(M3uCatchUp("default", 0, "http://h/{utc}"), M3uCatchUp.decode(c.encode()))
        assertEquals(M3uCatchUp("shift", 0, null), M3uCatchUp.decode(M3uCatchUp("shift", 3, null).encode()))
        assertNull(M3uCatchUp.decode("nonsense"))
    }

    @Test
    fun fillsKodiAndTiviMatePlaceholders() {
        val s = start / 1000
        assertEquals(
            "http://h/a?s=$s&e=${s + 3600}&n=${now / 1000}&d=3600&m=60&o=9000&date=2026-11-17:20-00",
            CatchUpTemplate.fill(
                "http://h/a?s={utc}&e={utcend}&n={lutc}&d={duration}&m={duration:60}&o={offset}&date={Y}-{m}-{d}:{H}-{M}",
                start,
                stop,
                now,
                ZoneOffset.UTC,
            ),
        )
        assertEquals(
            "http://h/20261117200000-20261117210000",
            CatchUpTemplate.fill("http://h/\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}", start, stop, now, ZoneOffset.UTC),
        )
        assertEquals("http://h/$s/{unknown}", CatchUpTemplate.fill("http://h/\${start}/{unknown}", start, stop, now, ZoneOffset.UTC))
        assertEquals("http://h/2026-11-17", CatchUpTemplate.fill("http://h/{utc:Y-m-d}", start, stop, now, ZoneOffset.UTC))
    }

    @Test
    fun buildsEachModesLink() {
        val s = start / 1000
        assertEquals("http://h/a.ts?utc=$s&lutc=${now / 1000}", link("http://h/a.ts", M3uCatchUp("shift", 3, null)))
        assertEquals("http://h/a.ts?t=1&utc=$s&lutc=${now / 1000}", link("http://h/a.ts?t=1", M3uCatchUp("shift", 3, null)))
        assertEquals("http://h/a.ts?start=$s", link("http://h/a.ts", M3uCatchUp("append", 3, "?start={utc}")))
        assertEquals("http://h/ch/index-$s-3600.m3u8?token=x", link("http://h/ch/index.m3u8?token=x", M3uCatchUp("flussonic", 3, null)))
        assertEquals("http://h/ch/timeshift_abs-$s.ts", link("http://h/ch/mpegts", M3uCatchUp("flussonic", 3, null)))
        assertEquals(
            "http://h:8080/timeshift/u/p/60/2026-11-17:20-00/42.ts",
            link("http://h:8080/live/u/p/42.ts", M3uCatchUp("xc", 3, null)),
        )
        assertEquals(
            "http://h:8080/timeshift/u/p/60/2026-11-17:20-00/42.m3u8",
            link("http://h:8080/u/p/42.m3u8", M3uCatchUp("xc", 3, null)),
        )
        assertNull(link("http://h/a.ts", M3uCatchUp("flussonic", 3, null)))
    }

    @Test
    fun keepsTheLiveLinksHeaders() {
        val live = StreamLink.join("http://h/a.ts", mapOf("User-Agent" to "Box"))
        val replay = link(live, M3uCatchUp("shift", 3, null))!!
        assertEquals(mapOf("User-Agent" to "Box"), StreamLink.split(replay).headers)
        assertEquals("http://h/a.ts?utc=${start / 1000}&lutc=${now / 1000}", StreamLink.address(replay))
    }

    @Test
    fun keysXtreamLinksByStreamIdAndOthersByLink() {
        assertEquals("42", CatchUp.key("http://h/live/u/p/42.ts"))
        assertEquals("http://h/ch/index.m3u8", CatchUp.key(StreamLink.join("http://h/ch/index.m3u8", mapOf("Referer" to "x"))))
    }
}
