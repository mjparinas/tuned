package app.tuned.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialsTest {
    @Test
    fun `kind follows what the playlist is`() {
        assertEquals(Credentials.Kind.Xtream, Credentials("http://h.com", "u", "p").kind)
        assertEquals(Credentials.Kind.Link, Credentials("", "", "", "https://h.com/list.m3u").kind)
        assertEquals(Credentials.Kind.File, Credentials("", "", "", "file:/data/x/playlists/a.m3u").kind)
    }

    @Test
    fun `a name is shown first, otherwise where it comes from without the password`() {
        assertEquals("Sports", Credentials("http://h.com", "me", "secret", name = "Sports").label)
        assertEquals("me @ h.com:8080", Credentials("http://h.com:8080", "me", "secret").label)
        assertEquals("lists.example.com", Credentials("", "", "", "https://lists.example.com/a.m3u?token=x").label)
        assertFalse("secret" in Credentials("http://h.com", "me", "secret").label)
    }

    @Test
    fun `a new name or guide is still the same playlist`() {
        val a = Credentials("http://h.com", "me", "p")
        assertTrue(a.samePlaylist(a.copy(name = "Living room", guideUrl = "http://g.com/x.xml", password = "new")))
        assertFalse(a.samePlaylist(a.copy(username = "other")))
    }
}
