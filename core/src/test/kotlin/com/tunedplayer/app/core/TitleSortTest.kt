package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleSortTest {
    @Test
    fun `added reads epoch seconds and ignores junk`() {
        assertEquals(1_700_000_000L, TitleSort.added("1700000000"))
        assertEquals(1_700_000_000L, TitleSort.added(" 1700000000 "))
        assertEquals(0, TitleSort.added(null))
        assertEquals(0, TitleSort.added(""))
        assertEquals(0, TitleSort.added("0"))
        assertEquals(0, TitleSort.added("2023-01-01"))
        assertEquals(0, TitleSort.added("99999999999999"))
    }

    @Test
    fun `rating out of ten, five-star doubled, zero is none`() {
        assertEquals(7.5, TitleSort.rating("7.5"))
        assertEquals(7.5, TitleSort.rating("7,5"))
        assertEquals(8.0, TitleSort.rating("", "4"))
        assertEquals(8.0, TitleSort.rating("0", "4"))
        assertNull(TitleSort.rating("0", "0"))
        assertNull(TitleSort.rating(null, null))
        assertNull(TitleSort.rating("N/A"))
        assertNull(TitleSort.rating("75"))
    }

    @Test
    fun `year from the year field or a release date`() {
        assertEquals(2021, TitleSort.year("2021"))
        assertEquals(2019, TitleSort.year(null, "2019-05-04"))
        assertEquals(2019, TitleSort.year("", "2019-05-04"))
        assertNull(TitleSort.year("", null))
        assertNull(TitleSort.year("05/04/2019"))
    }

    @Test
    fun `year in the name`() {
        assertEquals(2021, TitleSort.yearInName("EN - Dune (2021)"))
        assertEquals(1999, TitleSort.yearInName("The Matrix 1999"))
        assertNull(TitleSort.yearInName("Dune"))
    }

    @Test
    fun `title drops tags, year and accents`() {
        assertEquals("dune", TitleSort.title("EN - Dune (2021) 4K"))
        assertEquals("elan", TitleSort.title("|FR| Élan [2020]"))
        assertEquals("the matrix", TitleSort.title("The Matrix"))
    }

    @Test
    fun `stored names never change`() {
        assertEquals(listOf("PROVIDER", "ADDED", "YEAR", "RATING", "TITLE"), TitleSort.Order.entries.map { it.name })
    }
}
