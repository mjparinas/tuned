package com.tunedplayer.app.core

import java.util.Locale
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
    fun `the category's country wins over tvg-country, and the channel name is the fallback`() {
        // The category is what people see and hide by (Melvin, 2026-11-18): "|EXYU| ..." hides with Ex-Yugoslavia.
        assertEquals("US", Regions.detect("US| News", "CA;US", "x"))
        assertEquals("EX", Regions.detect("|EXYU| STRANI FILMOVI", "HR", "Ex - R.M.N. (2022)"))
        assertEquals("HR", Regions.detect("Filmovi", "HR", "x"))
        assertEquals("DE", Regions.detect("News", null, "DE: Das Erste"))
        assertEquals("Canada", Regions.displayName("CA"))
        assertEquals("ZZ", Regions.displayName("ZZ"))
        // Codes Tuned has no short name for still read as a country, so they sort and search by name too.
        assertTrue(Regions.displayName("CI").startsWith("C") && Regions.displayName("CI") != "CI")
    }

    @Test
    fun `names follow the reader's language, and Tuned's own tags stay as they are`() {
        val spanish = Locale.forLanguageTag("es")
        assertEquals("Alemania", Regions.displayName("DE", spanish))
        assertEquals("Reino Unido", Regions.displayName("UK", spanish))
        // "AR" is the Arabic tag, never Argentina.
        assertEquals("Árabe", Regions.displayName("AR", spanish))
        assertEquals("Latino", Regions.displayName("LAT", spanish))
        assertEquals("ZZ", Regions.displayName("ZZ", spanish))
        assertEquals("United Kingdom", Regions.displayName("UK", Locale.CANADA))
    }

    @Test
    fun `a country written out at the start counts without a symbol`() {
        assertEquals("SE", group("SWEDEN HANDBOLLSLIGAN PPV"))
        assertEquals("SE", group("SWEDEN SVENSKA KANALER"))
        assertEquals("IN", group("INDIA MOVIES"))
        assertEquals("US", group("UNITED STATES NEWS"))
        assertEquals("KR", group("SOUTH KOREA DRAMA"))
    }

    @Test
    fun `codes and names that start other things still need a symbol`() {
        assertNull(group("US Open"))
        assertNull(group("USA Network"))
        assertNull(group("Georgia Bulldogs"))
        assertNull(group("Jersey Shore"))
        assertNull(group("America's Got Talent"))
    }

    @Test
    fun `the country after a continent wins`() {
        assertEquals("IN", group("ASIA | INDIA MOVIES"))
        assertEquals("DE", group("EUROPE | DE Sports"))
        assertEquals("ASIA", group("ASIA | Movies"))
        assertEquals("Asia", Regions.displayName("ASIA"))
    }

    @Test
    fun `countries in their own language and the nordic area count`() {
        assertEquals("SE", group("SVERIGE | Sport"))
        assertEquals("SE", group("SVENSKA KANALER"))
        assertEquals("NO", group("NORGE: Film"))
        assertEquals("DK", group("DANMARK SPORT"))
        assertEquals("SE", group("NORDIC | SE Viaplay"))
        assertEquals("NORDIC", group("NORDIC FILM"))
        assertEquals("NORDIC", group("SCANDINAVIA | Kids"))
        assertEquals("SE", group("SWEDEN HIGHLIGHTS"))
    }

    @Test
    fun `ex-yu and plain prefixes still read as before`() {
        assertEquals("EX", group("|EXYU| STRANI FILMOVI 1950-2022"))
        assertEquals("US", group("|US| MOVIES NETWORK"))
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

    @Test
    fun `a tag and a bar in the stream's own name beats the category`() {
        // Melvin, 2026-11-18: Hindi channels filed under "US| MOVIES NETWORK" showed with only US and Canada on.
        assertEquals("IN", Regions.detect("US| MOVIES NETWORK", null, "HINDI| & PICTURES FHD"))
        assertEquals("IN", Regions.detect("US| MOVIES NETWORK", null, "IN | Colors Cineplex"))
        assertEquals("US", Regions.detect("US| MOVIES NETWORK", null, "US| THE FILM DETECTIVE"))
        // A colon, a quality tag or an unknown word in front of the bar leaves the category in charge.
        assertEquals("US", Regions.detect("US| Movies", null, "IT: Chapter Two"))
        assertEquals("US", Regions.detect("US| Sports", null, "VIP | ESPN"))
        assertEquals("US", Regions.detect("US| Sports", null, "NFL | Week 1"))
        // The name's own tag beats tvg-country too, which now comes after the category.
        assertEquals("IN", Regions.detect("US| News", "CA", "HINDI| Aaj Tak"))
    }
}
