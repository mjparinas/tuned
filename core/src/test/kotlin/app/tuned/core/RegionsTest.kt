package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionsTest {
    private fun group(g: String) = Regions.detect(g, null, "Some Channel")

    @Test
    fun `reads the common prefix styles`() {
        assertEquals("US", group("US| News"))
        assertEquals("UK", group("UK: Sports"))
        assertEquals("CA", group("|CA| Kids"))
        assertEquals("FR", group("[FR] Cinéma"))
        assertEquals("EN", group("EN - Movies"))
        assertEquals("US", group("USA | Entertainment"))
        assertEquals("UK", group("GB • Documentaries"))
        assertEquals("US", group("4K | US Movies"))
        assertEquals("AR", group("AR | beIN Sports"))
    }

    @Test
    fun `plain names are not regions`() {
        assertNull(group("Sports"))
        assertNull(group("CNN"))
        assertNull(group("Sports | NBA"))
        assertNull(group("HD | Sports"))
        assertNull(group("Entertainment"))
    }

    @Test
    fun `tvg-country wins and the channel name is the fallback`() {
        assertEquals("CA", Regions.detect("US| News", "CA;US", "x"))
        assertEquals("DE", Regions.detect("News", null, "DE: Das Erste"))
        assertEquals("Canada", Regions.displayName("CA"))
        assertEquals("ZZ", Regions.displayName("ZZ"))
    }
}
