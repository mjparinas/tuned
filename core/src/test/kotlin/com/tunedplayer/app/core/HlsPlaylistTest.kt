package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsPlaylistTest {
    private val base = "http://cdn.example/live/user/pass/news/index.m3u8?token=abc"

    @Test
    fun `a master playlist lists its qualities with full addresses`() {
        val master =
            HlsPlaylist.parse(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,CODECS="avc1.64001f,mp4a.40.2"
                /hd/index.m3u8?t=1
                """.trimIndent(),
                base,
            ) as HlsPlaylist.Master
        assertEquals(
            listOf(
                HlsPlaylist.Variant("http://cdn.example/live/user/pass/news/low/index.m3u8", 800_000),
                HlsPlaylist.Variant("http://cdn.example/hd/index.m3u8?t=1", 3_000_000),
            ),
            master.variants,
        )
        assertTrue(master.keepable)
    }

    @Test
    fun `audio as its own rendition can't be kept`() {
        val master =
            HlsPlaylist.parse(
                """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aud"
                video.m3u8
                """.trimIndent(),
                base,
            ) as HlsPlaylist.Master
        assertFalse(master.keepable)
    }

    @Test
    fun `a media playlist numbers its pieces from the media sequence`() {
        val media =
            HlsPlaylist.parse(
                """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:120
                #EXTINF:6.006,
                120.ts
                #EXTINF:5.5,title
                http://edge.example/121.ts
                """.trimIndent(),
                base,
            ) as HlsPlaylist.Media
        assertEquals(
            listOf(
                HlsPlaylist.Segment("http://cdn.example/live/user/pass/news/120.ts", 120, 6.006),
                HlsPlaylist.Segment("http://edge.example/121.ts", 121, 5.5),
            ),
            media.segments,
        )
        assertEquals(6.0, media.targetSeconds)
        assertFalse(media.ended)
        assertTrue(media.keepable)
    }

    @Test
    fun `encrypted, fMP4 and byte-range pieces can't be kept`() {
        fun keepable(
            extra: String,
            piece: String = "1.ts",
        ) = (
            HlsPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:4\n$extra\n#EXTINF:4,\n$piece", base) as HlsPlaylist.Media
        ).keepable
        assertFalse(keepable("#EXT-X-KEY:METHOD=AES-128,URI=\"key\""))
        assertTrue(keepable("#EXT-X-KEY:METHOD=NONE"))
        assertFalse(keepable("#EXT-X-MAP:URI=\"init.mp4\""))
        assertFalse(keepable("#EXT-X-BYTERANGE:1000@0"))
        assertFalse(keepable("", piece = "1.m4s"))
        assertTrue(keepable(""))
    }

    @Test
    fun `anything else isn't a playlist`() {
        assertNull(HlsPlaylist.parse("<html>Not found</html>", base))
        assertTrue(HlsPlaylist.isPlaylist("http://x/a/index.m3u8?token=1"))
        assertFalse(HlsPlaylist.isPlaylist("http://x/a/1.ts"))
    }

    @Test
    fun `a subtitle rendition is told apart`() {
        val subs = HlsPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsubs/1.vtt", base) as HlsPlaylist.Media
        assertTrue(subs.subtitles)
        val video = HlsPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n1.ts", base) as HlsPlaylist.Media
        assertFalse(video.subtitles)
    }
}
