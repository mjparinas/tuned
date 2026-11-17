package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogScrubberTest {
    @Test
    fun `hides the login in a playlist address`() {
        val text = "Failed: http://tv.example:8080/get.php?username=alice&password=s3cret&type=m3u_plus"
        assertEquals("Failed: http://tv.example:8080/get.php?username=•••&password=•••&type=m3u_plus", LogScrubber.scrub(text))
    }

    @Test
    fun `hides the login in a stream path`() {
        val scrubbed = LogScrubber.scrub("Source error http://tv.example/live/alice/s3cret/123.ts and /movie/bob/pw/9.mkv")
        assertEquals("Source error http://tv.example/live/•••/•••/123.ts and /movie/•••/•••/9.mkv", scrubbed)
    }

    @Test
    fun `hides a timeshift login`() {
        val scrubbed = LogScrubber.scrub("http://tv.example/timeshift/alice/s3cret/60/2026-11-17:20-00/5.ts")
        assertFalse("alice" in scrubbed || "s3cret" in scrubbed)
    }

    @Test
    fun `hides a login in the address and keys`() {
        assertEquals("https://•••@host/list.m3u", LogScrubber.scrub("https://alice:s3cret@host/list.m3u"))
        assertEquals("api.themoviedb.org/3/movie?api_key=•••", LogScrubber.scrub("api.themoviedb.org/3/movie?api_key=abc123"))
        assertEquals("Authorization: Bearer •••", LogScrubber.scrub("Authorization: Bearer eyJhbGciOi.x-y"))
    }

    @Test
    fun `leaves ordinary text alone`() {
        val text = "Guide refreshed: 12,345 programmes in 4.2 s (http://tv.example/xmltv.php)"
        assertEquals(text, LogScrubber.scrub(text))
    }
}
