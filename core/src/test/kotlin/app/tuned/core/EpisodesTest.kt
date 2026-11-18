package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodesTest {
    @Test
    fun `reads the usual numbering styles`() {
        assertEquals(EpisodeInfo("The Bear", 2, 3), Episodes.parse("The Bear S02 E03", "g"))
        assertEquals(EpisodeInfo("Severance", 1, 10), Episodes.parse("Severance S01E10 - The We We Are", "g"))
        assertEquals(EpisodeInfo("Dark", 3, 8), Episodes.parse("Dark.S03.E08", "g"))
        assertEquals(EpisodeInfo("Friends", 5, 14), Episodes.parse("Friends 5x14", "g"))
        assertEquals(EpisodeInfo("EN - Shogun", 1, 2), Episodes.parse("EN - Shogun S01 E02", "g"))
    }

    @Test
    fun `an unnumbered title is one episode of its own show`() {
        assertEquals(EpisodeInfo("Planet Earth", 1, 1), Episodes.parse("Planet Earth", "Docs"))
        assertEquals(EpisodeInfo("Docs", 2, 1), Episodes.parse("S02 E01", "Docs"))
    }

    @Test
    fun `an episode's own name is the words after its number`() {
        assertEquals("The We We Are", Episodes.title("Severance S01E10 - The We We Are"))
        assertEquals("Kamp Krusty", Episodes.title("EN - The Simpsons (1989) S04 E01 | Kamp Krusty"))
        assertEquals("Pilot", Episodes.title("Show.S01.E01.Pilot"))
        assertEquals(null, Episodes.title("Harbour Town S01E01"))
        assertEquals(null, Episodes.title("Harbour Town S01E01 -"))
        assertEquals("Planet Earth", Episodes.title("Planet Earth"))
    }

    @Test
    fun `show keys ignore case, accents and punctuation`() {
        assertEquals(Episodes.showKey("Grey's Anatomy"), Episodes.showKey("GREY'S  anatomy"))
        assertEquals("tele quebec", Search.normalize("Télé-Québec"))
    }
}
