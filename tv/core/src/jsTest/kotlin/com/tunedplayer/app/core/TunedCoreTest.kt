package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** :core's rules give the same answers in JavaScript as on Android (core/src/test holds the full tests). */
class TunedCoreTest {
    @Test
    fun readsAPlaylistFedInPieces() {
        val reader = M3uReader()
        val text =
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"cbc.ca\" tvg-logo=\"http://l/cbc.png\" group-title=\"CA| News\",CBC News HD\n" +
                "http://tv.example:8080/live/u/p/1.ts\n#EXTINF:-1 group-title=\"Movies\",Heat (1995)\nhttp://tv.example/movie/u/p/2.mkv"
        val firstHalf = reader.feed(text.substring(0, 70))
        val secondHalf = reader.feed(text.substring(70))
        val last = reader.end()
        val all = (firstHalf + secondHalf + last).toList()
        assertEquals(2, all.size)
        assertEquals("CBC News HD", all[0].name)
        assertEquals("live", all[0].kind)
        assertEquals("CA", all[0].region)
        assertEquals("cbc.ca", all[0].tvgId)
        assertEquals("movie", all[1].kind)
        assertEquals(2, reader.count)
    }

    @Test
    fun encodesLikeJava() {
        assertEquals("a+b%21%27%28%29%7E*-._%C3%A9%2F", Platform.formEncode("a b!'()~*-._é/"))
        assertEquals("a b/é", Platform.formDecode("a+b%2F%C3%A9"))
    }

    @Test
    fun redactsTheLogin() {
        val xtream = Xtream("tv.example:8080", "me@x", "p w")
        assertEquals("http://tv.example:8080/player_api.php?username=me%40x&password=p+w", xtream.accountUrl)
        assertEquals("http://tv.example:8080/player_api.php?username=•••&password=•••", xtream.redact(xtream.accountUrl))
        val live = xtream.stream("live", "7", "UK: Sky News", null, "UK | News", "sky.uk", false, null, 0.0, null, null, 0)
        assertEquals("http://tv.example:8080/live/me%40x/p+w/7.ts", live.url)
        assertEquals("UK", live.region)
    }

    @Test
    fun regionsAndSearch() {
        assertEquals("Canada", TunedCore.regionName("CA"))
        assertEquals(listOf("CA", "FR", "US"), TunedCore.sortRegions(arrayOf("US", "FR", "CA")).toList())
        assertEquals("tele quebec", TunedCore.searchText("Télé-Québec"))
        assertEquals("UK", TunedCore.region("🇬🇧 Sports", null, "Sky", null))
        assertNull(TunedCore.region("Sports", null, "ESPN", null))
        assertTrue(TunedCore.isAdult("XXX", "Something"))
    }

    @Test
    fun keepsAndroidsIds() {
        val live = Xtream("h", "u", "p").stream("live", "7", "Sky News", null, "UK | News", null, false, null, 0.0, null, null, 0)
        assertEquals(StableId.fnv64("0|7|Sky News").toString(), live.stableId)
        assertEquals("-3750763034362895579", StableId.fnv64("").toString())
    }
}
