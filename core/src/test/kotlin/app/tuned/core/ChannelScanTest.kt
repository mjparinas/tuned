package app.tuned.core

import app.tuned.core.ChannelScan.Failure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelScanTest {
    private val ts = ByteArray(376) { if (it % 188 == 0) 0x47 else 0x00 }

    @Test
    fun aStreamThatSendsVideoWorks() {
        assertNull(ChannelScan.classify(200, ts, timedOut = false, connectionFailed = false))
        assertNull(ChannelScan.classify(200, "#EXTM3U\n#EXTINF:10,\nseg1.ts\n".toByteArray(), timedOut = false, connectionFailed = false))
        // Some data, then the time ran out: it was sending, so it works.
        assertNull(ChannelScan.classify(200, ts, timedOut = true, connectionFailed = false))
    }

    @Test
    fun clearFailuresAreNamed() {
        assertEquals(Failure.HTTP, ChannelScan.classify(404, ByteArray(0), timedOut = false, connectionFailed = false))
        assertEquals(Failure.HTTP, ChannelScan.classify(502, ByteArray(0), timedOut = false, connectionFailed = false))
        assertEquals(Failure.NO_DATA, ChannelScan.classify(200, ByteArray(0), timedOut = false, connectionFailed = false))
        assertEquals(Failure.TIMEOUT, ChannelScan.classify(200, ByteArray(0), timedOut = true, connectionFailed = false))
        assertEquals(Failure.TIMEOUT, ChannelScan.classify(null, ByteArray(0), timedOut = true, connectionFailed = false))
        assertEquals(Failure.CONNECTION, ChannelScan.classify(null, ByteArray(0), timedOut = false, connectionFailed = true))
        val page = "<html><body>Stream not found</body></html>".toByteArray()
        assertEquals(Failure.NOT_VIDEO, ChannelScan.classify(200, page, timedOut = false, connectionFailed = false))
    }

    @Test
    fun loginAndConnectionLimitAnswersAreTheAccountsNotTheChannels() {
        for (code in listOf(401, 403, 429, 458)) {
            assertEquals(Failure.ACCOUNT, ChannelScan.classify(code, ByteArray(0), timedOut = false, connectionFailed = false))
        }
    }

    @Test
    fun textAndPlaylistsAreTold() {
        assertTrue(ChannelScan.isText("{\"error\":\"x\"}".toByteArray()))
        assertFalse(ChannelScan.isText(ts))
        assertTrue(ChannelScan.isPlaylist("﻿#EXTM3U\n".toByteArray()))
    }

    @Test
    fun firstUriResolvesAgainstThePlaylist() {
        val base = "http://host:8080/live/u/p/123.m3u8?token=abc"
        assertEquals("http://host:8080/live/u/p/seg-1.ts", ChannelScan.firstUri("#EXTM3U\n#EXTINF:10,\nseg-1.ts\n", base))
        assertEquals("http://host:8080/hls/x.ts", ChannelScan.firstUri("#EXTM3U\n/hls/x.ts", base))
        assertEquals("https://cdn/a.ts", ChannelScan.firstUri("#EXTM3U\nhttps://cdn/a.ts", base))
        assertNull(ChannelScan.firstUri("#EXTM3U\n#EXT-X-ENDLIST\n", base))
    }

    @Test
    fun aChannelIsHiddenOnlyAfterFailingInTwoSeparateScans() {
        val hour = 60 * 60_000L
        val once = ChannelScan.afterFailure(null, Failure.HTTP, now = 0)
        assertFalse(once.hidden)
        // Again in the same scan, or an hour later: still one.
        val soon = ChannelScan.afterFailure(once, Failure.HTTP, now = hour)
        assertEquals(1, soon.failures)
        assertFalse(soon.hidden)
        val twice = ChannelScan.afterFailure(soon, Failure.TIMEOUT, now = 7 * hour)
        assertEquals(2, twice.failures)
        assertTrue(twice.hidden)
        assertEquals(Failure.TIMEOUT, twice.reason)
    }

    @Test
    fun playingAgainBringsAChannelBack() {
        val hidden = ChannelScan.Record(failures = 2, firstFailAt = 1, lastFailAt = 2)
        val back = ChannelScan.afterOk(hidden, now = 10)
        assertFalse(back.hidden)
        assertEquals(0, back.failures)
        assertEquals(10, back.lastOkAt)
    }

    @Test
    fun aChannelShownAgainByHandIsNeverHiddenByAScan() {
        val kept = ChannelScan.keep(ChannelScan.Record(failures = 3))
        val later = ChannelScan.afterFailure(ChannelScan.afterFailure(kept, Failure.HTTP, 0), Failure.HTTP, ChannelScan.MIN_APART_MS)
        assertEquals(2, later.failures)
        assertFalse(later.hidden)
    }

    @Test
    fun aChannelIsBlamedInThePlayerOnlyWhenEverythingElseWorks() {
        val up = ScanEngine.Server.UP
        assertTrue(ChannelScan.blameOnPlay(internetUp = true, server = up, anotherChannelPlayed = true))
        assertTrue(ChannelScan.blameOnPlay(internetUp = true, server = ScanEngine.Server.UNKNOWN, anotherChannelPlayed = true))
        assertFalse(ChannelScan.blameOnPlay(internetUp = false, server = up, anotherChannelPlayed = true))
        assertFalse(ChannelScan.blameOnPlay(internetUp = true, server = ScanEngine.Server.DOWN, anotherChannelPlayed = true))
        assertFalse(ChannelScan.blameOnPlay(internetUp = true, server = ScanEngine.Server.BUSY, anotherChannelPlayed = true))
        assertFalse(ChannelScan.blameOnPlay(internetUp = true, server = up, anotherChannelPlayed = false))
    }

    @Test
    fun aConfirmedPlayerFailureHidesAtOnceUnlessKept() {
        val hidden = ChannelScan.afterPlayFailure(null, 1_000)
        assertTrue(hidden.hidden)
        assertEquals(Failure.PLAYBACK, hidden.reason)
        val kept = ChannelScan.afterPlayFailure(ChannelScan.keep(null), 1_000)
        assertFalse(kept.hidden)
        // A scan that finds it working brings it back.
        assertFalse(ChannelScan.afterOk(hidden, 2_000).hidden)
    }
}
