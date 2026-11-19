package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class XtreamUrlsTest {
    @Test
    fun `builds playlist and guide urls with encoded login`() {
        assertEquals(
            "http://iptv.example.net/get.php?username=a+b&password=p%26w&type=m3u_plus&output=ts",
            XtreamUrls.playlist("iptv.example.net/", "a b", "p&w"),
        )
        assertEquals("https://h:8080/xmltv.php?username=u&password=p", XtreamUrls.guide("https://h:8080", "u", "p"))
    }

    @Test
    fun `redacts the login from text`() {
        val url = "http://h/live/myuser/secret99/1.ts?password=secret99"
        val redacted = XtreamUrls.redact(url, "myuser", "secret99")
        assertFalse("secret99" in redacted || "myuser" in redacted)
    }

    @Test
    fun `stable ids ignore the login in the url`() {
        val a = entry("http://h/live/u1/p1/101.ts")
        val b = entry("http://h/live/u2/p2/101.ts")
        assertEquals(StableId.of(a), StableId.of(b))
        assertNotEquals(StableId.of(a), StableId.of(entry("http://h/live/u1/p1/102.ts")))
    }

    private fun entry(url: String) = PlaylistEntry("CNN", url, ContentKind.LIVE, "News", null, null, null, false, null, 0)
}
