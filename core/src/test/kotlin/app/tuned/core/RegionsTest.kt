package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // Codes Tuned has no short name for still read as a country, so they sort and search by name too.
        assertTrue(Regions.displayName("CI").startsWith("C") && Regions.displayName("CI") != "CI")
    }

    @Test
    fun `countries sort by name, not by stream count`() {
        // Busiest first, as the database returns them; "Côte d'Ivoire" sorts as if the accent weren't there.
        val byCount = listOf("US" to 900, "UK" to 500, "FR" to 300, "CI" to 200, "CA" to 50, "AF" to 1)
        assertEquals(
            listOf("AF", "CA", "CI", "FR", "UK", "US"),
            Regions.sortedByName(byCount) { it.first }.map { it.first },
        )
    }

    @Test
    fun `search finds a country by name or tag, ignoring case and accents`() {
        assertTrue(Regions.regionMatches("can", "CA"))
        assertTrue(Regions.regionMatches("ca", "CA"))
        assertTrue(Regions.regionMatches("cote", "CI"))
        assertTrue(Regions.regionMatches("  KINGDOM ", "UK"))
        assertTrue(Regions.regionMatches("", "FR"))
        assertFalse(Regions.regionMatches("germany", "FR"))
        assertTrue(Regions.matchesSearch("espa", listOf("Español")))
        assertFalse(Regions.matchesSearch("french", listOf("English", "US", "UK")))
    }
}
