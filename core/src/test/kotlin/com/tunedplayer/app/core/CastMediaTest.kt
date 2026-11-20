package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastMediaTest {
    @Test
    fun xtreamLiveTsBecomesHls() {
        assertEquals(
            CastMedia.Media("http://tv.example:8080/live/u/p/123.m3u8", "application/x-mpegURL"),
            CastMedia.forCast("http://tv.example:8080/live/u/p/123.ts", live = true),
        )
        // The short form without /live or an extension is the same channel.
        assertEquals(
            CastMedia.Media("http://tv.example/live/u/p/9.m3u8", "application/x-mpegURL"),
            CastMedia.forCast("http://tv.example/u/p/9", live = true),
        )
    }

    @Test
    fun otherLinksKeepTheirUrlAndGetAContentType() {
        assertEquals(
            CastMedia.Media("http://tv.example/movie/u/p/5.mp4", "video/mp4"),
            CastMedia.forCast("http://tv.example/movie/u/p/5.mp4", live = false),
        )
        assertEquals("application/x-mpegURL", CastMedia.forCast("https://cdn.example/a/b/index.m3u8?token=1", live = true).mimeType)
        assertEquals("video/*", CastMedia.forCast("https://cdn.example/stream", live = true).mimeType)
    }

    @Test
    fun aSavedFileStreamedFromThePhoneIsHlsInMp4Pieces() {
        val media = CastMedia.phoneStream("http://192.168.0.71:4000/k/42/index.m3u8")
        assertEquals("application/x-mpegURL", media.mimeType)
        assertTrue(media.fmp4)
        assertFalse(CastMedia.forCast("http://tv.example/u/p/9", live = true).fmp4)
    }

    @Test
    fun aRefusedLoadIsTriedOnceMoreAfterAPause() {
        assertEquals(3_000L, CastMedia.retryDelayMs(attempt = 1))
        assertNull(CastMedia.retryDelayMs(attempt = 2))
    }
}
