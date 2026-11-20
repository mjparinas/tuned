package com.tunedplayer.app.core

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

    @Test
    fun `ex-yu and space-separated country tags come off`() {
        assertEquals("Dogtanian: One for All", Titles.display("Ex - Dogtanian: One for All"))
        assertEquals("R.M.N. (2022)", Titles.display("EXYU - R.M.N. (2022)"))
        assertEquals("MIxed By Erry", Titles.display("ES  MIxed By Erry"))
        assertEquals("Exit - The Movie", Titles.display("Exit - The Movie"))
        assertEquals("IT Crowd", Titles.display("IT Crowd"))
        assertEquals("ZZ Top", Titles.display("ZZ  Top"))
    }
}
