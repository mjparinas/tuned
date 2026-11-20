package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StalkerTest {
    @Test
    fun `a MAC is read however it is typed`() {
        assertEquals("00:1A:79:AB:CD:EF", Stalker.normalizeMac("00:1a:79:ab:cd:ef"))
        assertEquals("00:1A:79:AB:CD:EF", Stalker.normalizeMac(" 001A79ABCDEF "))
        assertEquals("00:1A:79:AB:CD:EF", Stalker.normalizeMac("00-1A-79-AB-CD-EF"))
        assertNull(Stalker.normalizeMac("00:1A:79:AB:CD"))
        assertNull(Stalker.normalizeMac("00:1A:79:AB:CD:EG"))
    }

    @Test
    fun `the portal address loses its page and the c folder`() {
        assertEquals("http://host.example:8080", Stalker.normalizePortal("host.example:8080/c/"))
        assertEquals("http://host.example/stalker_portal", Stalker.normalizePortal("http://host.example/stalker_portal/c/index.html"))
        assertEquals("https://host.example", Stalker.normalizePortal("https://host.example/"))
    }

    @Test
    fun `api addresses are tried most likely first`() {
        assertEquals(
            listOf(
                "http://host.example:8080/portal.php",
                "http://host.example:8080/server/load.php",
                "http://host.example:8080/stalker_portal/server/load.php",
            ),
            Stalker.apiCandidates("http://host.example:8080/c/"),
        )
        assertEquals(
            "http://host.example/stalker_portal/server/load.php",
            Stalker.apiCandidates("host.example/stalker_portal/c/").first(),
        )
    }

    @Test
    fun `a stored link keeps the command and ends in the channel id`() {
        val url = Stalker.link("itv", "1234", "ffrt http://localhost/ch/1234_")
        assertEquals(Stalker.Link("itv", "1234", "ffrt http://localhost/ch/1234_"), Stalker.parse(url))
        assertTrue(Stalker.isLink(url))
        assertNull(Stalker.parse("http://host.example/live/u/p/1.ts"))
        val entry = Stalker.live(Stalker.Channel("1234", "CNN", "ffrt http://localhost/ch/1234_", null, "US| NEWS", null), 0)
        val renamedCommand = entry.copy(url = Stalker.link("itv", "1234", "ffrt http://localhost/ch/other"))
        assertEquals(StableId.of(entry), StableId.of(renamedCommand))
    }

    @Test
    fun `the address comes out of a command, and a localhost stand-in is not a stream`() {
        val play = "http://cdn.example/play/live.php?stream=1&play_token=x"
        assertEquals(play, Stalker.address("ffmpeg $play"))
        assertEquals("http://cdn.example/a.m3u8", Stalker.address("http://cdn.example/a.m3u8"))
        assertEquals("http://cdn.example/1.ts", Stalker.direct("auto http://cdn.example/1.ts"))
        assertNull(Stalker.direct("ffrt http://localhost/ch/1234_"))
        assertNull(Stalker.address("not a link"))
        assertTrue(Stalker.isHls("ffmpeg http://cdn.example/index.m3u8"))
        assertFalse(Stalker.isHls("ffmpeg http://cdn.example/1.ts"))
    }

    @Test
    fun `a channel is sorted like any other and filed under its guide id`() {
        val bbc = Stalker.Channel("7", "UK: BBC One", "cmd", "http://logo.example/1.png", "UK | ENTERTAINMENT", "bbc1.uk")
        val withId = Stalker.live(bbc, 3)
        assertEquals("bbc1.uk", withId.tvgId)
        assertEquals("UK", withId.region)
        assertEquals(ContentKind.LIVE, withId.kind)
        assertEquals(3, withId.order)
        val without = Stalker.live(Stalker.Channel("8", "Night", "cmd", null, "", null, adult = true), 4)
        assertEquals("stalker:8", without.tvgId)
        assertEquals("Uncategorized", without.group)
        assertTrue(without.adult)
    }

    @Test
    fun `logos on the portal get its address`() {
        assertEquals(
            "http://host.example/stalker_portal/misc/logos/320/1.png",
            Stalker.logo("http://host.example/stalker_portal/c/", "/stalker_portal/misc/logos/320/1.png"),
        )
        assertEquals("http://cdn.example/1.png", Stalker.logo("http://host.example/c/", "http://cdn.example/1.png"))
        assertNull(Stalker.logo("http://host.example/c/", " "))
    }

    @Test
    fun `the serial and ids are the same every time for one MAC`() {
        val mac = "00:1A:79:AB:CD:EF"
        assertEquals(Stalker.serial(mac), Stalker.serial(mac))
        assertEquals(13, Stalker.serial(mac).length)
        assertEquals(64, Stalker.deviceId(mac).length)
        assertEquals("mac=00%3A1A%3A79%3AAB%3ACD%3AEF; stb_lang=en; timezone=UTC", Stalker.cookie(mac))
    }

    @Test
    fun `guide times in seconds or milliseconds`() {
        assertEquals(1_700_000_000_000L, Stalker.millis(1_700_000_000L))
        assertEquals(1_700_000_000_000L, Stalker.millis(1_700_000_000_000L))
    }
}
