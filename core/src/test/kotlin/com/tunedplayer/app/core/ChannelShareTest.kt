package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChannelShareTest {
    @Test
    fun playlistIdMatchesTheWebsites() {
        // devices/lib/library.mjs playlistId({ server: "http://p.example:8080", username: "sam", playlistUrl: "" })
        assertEquals(WEBSITE_ID, ChannelShare.playlistId("http://p.example:8080", "sam", ""))
    }

    @Test
    fun linksLoseTheirLogin() {
        assertEquals(
            "http://p.example:8080/live/{login}/12.ts",
            ChannelShare.hideLogin("http://p.example:8080/live/sam/s3cret/12.ts", "sam", "s3cret"),
        )
        // The app encodes a login with odd characters; a provider's M3U may not.
        assertEquals("http://p/live/{login}/1.ts", ChannelShare.hideLogin("http://p/live/me%40x/a%2Fb/1.ts", "me@x", "a/b"))
        assertEquals("http://p/{login}/1", ChannelShare.hideLogin("http://p/sam/s3cret/1", "sam", "s3cret"))
        assertEquals(
            "http://p/play?username={user}&password={pass}",
            ChannelShare.hideLogin("http://p/play?username=sam&password=s3cret", "sam", "s3cret"),
        )
        assertEquals("http://p/play.m3u8", ChannelShare.hideLogin("http://p/play.m3u8", "", ""))
    }

    @Test
    fun aDeviceFillsItsOwnLoginBackIn() {
        val links =
            listOf(
                "http://p.example:8080/live/sam/s3cr&t/101.ts",
                "http://p.example:8080/get.php?username=sam&password=s3cr%26t&type=m3u",
                "http://other.example/plain.m3u8",
            )
        for (link in links) {
            val hidden = ChannelShare.hideLogin(link, "sam", "s3cr&t")
            assertEquals(link, ChannelShare.showLogin(hidden, "sam", "s3cr&t"))
        }
    }

    @Test
    fun theCopyHasTheChannelsAndOnlyTheirNextHours() {
        val now = 1_795_201_200_000L
        val channels =
            listOf(
                ChannelShare.Channel("CBC \"East\"", "Canada", "http://i/cbc.png", "cbc.ca", 1, "http://p/live/{login}/1.ts", "ca"),
                ChannelShare.Channel("CP24", "News", null, null, 2, "http://p/live/{login}/2.ts", adult = true),
            )
        val hour = 3_600_000L
        val shows =
            listOf(
                ChannelShare.Show("cbc.ca", now - 2 * hour, now - hour, "Gone"),
                ChannelShare.Show("cbc.ca", now + hour, now + 2 * hour, "Later", genre = 1),
                ChannelShare.Show("cbc.ca", now - hour / 2, now + hour, "Now\non"),
                ChannelShare.Show("cbc.ca", now - hour / 2, now + hour, "Now\non"),
                ChannelShare.Show("cbc.ca", now + 13 * hour, now + 14 * hour, "Tonight"),
                ChannelShare.Show("cbc.ca", now + 25 * hour, now + 26 * hour, "Tomorrow"),
                ChannelShare.Show("other", now, now + hour, "Not ours"),
            )
        val json = ChannelShare.payload("abc", channels, shows, now)
        assertEquals(
            "{\"v\":1,\"playlist\":\"abc\",\"savedAt\":$now,\"channels\":[" +
                "[\"CBC \\\"East\\\"\",\"Canada\",\"http://i/cbc.png\",\"cbc.ca\",1,\"http://p/live/{login}/1.ts\",\"ca\",0]," +
                "[\"CP24\",\"News\",\"\",\"\",2,\"http://p/live/{login}/2.ts\",\"\",1]]," +
                "\"guide\":{\"cbc.ca\":[[${(now - hour / 2) / 1000},${(now + hour) / 1000},\"Now\\non\",0]," +
                "[${(now + hour) / 1000},${(now + 2 * hour) / 1000},\"Later\",1]," +
                "[${(now + 13 * hour) / 1000},${(now + 14 * hour) / 1000},\"Tonight\",0]]}}",
            json,
        )
        assertTrue(ChannelShare.payload("abc", channels, shows, now, withGuide = false).endsWith("\"guide\":{}}"))
    }

    @Test
    fun theFingerprintFollowsTheList() {
        val a = listOf(ChannelShare.Channel("A", "G", null, null, 1, "http://a"))
        val b = listOf(ChannelShare.Channel("B", "G", null, null, 1, "http://a"))
        assertEquals(ChannelShare.fingerprint("x", a), ChannelShare.fingerprint("x", a))
        assertNotEquals(ChannelShare.fingerprint("x", a), ChannelShare.fingerprint("x", b))
        assertNotEquals(ChannelShare.fingerprint("x", a), ChannelShare.fingerprint("y", a))
        assertNotEquals(ChannelShare.fingerprint("x", a), ChannelShare.fingerprint("x", a.map { it.copy(region = "ca") }))
        assertEquals("\"\\u0001\"", ChannelShare.str("\u0001"))
    }

    private companion object {
        const val WEBSITE_ID = "74d5b50ccf496f21"
    }
}
