package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TitlesTest {
    @Test
    fun languageTagsComeOff() {
        assertEquals("Top Gun: Maverick (2022)", Titles.display("EN - Top Gun: Maverick  (2022)"))
        assertEquals("Top Gun", Titles.display("|EN| Top Gun"))
        assertEquals("Amélie", Titles.display("[FR] Amélie"))
        assertEquals("Dune", Titles.display("EN: Dune"))
    }

    @Test
    fun realTitlesStay() {
        assertEquals("Up - The Story", Titles.display("Up - The Story"))
        assertEquals("Her: Part 1", Titles.display("Her: Part 1"))
        assertEquals("ET", Titles.display("ET"))
        assertEquals("EN -", Titles.display("EN -"))
    }
}
