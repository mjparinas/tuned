package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgrammeGenreTest {
    @Test
    fun `the guide's categories decide, the first one recognized winning`() {
        assertEquals(ProgrammeGenre.SPORTS, ProgrammeGenre.of(listOf("Sports event"), "NHL Hockey"))
        assertEquals(ProgrammeGenre.MOVIES, ProgrammeGenre.of(listOf("Movie", "Drama"), "The Long Night"))
        assertEquals(ProgrammeGenre.MOVIES, ProgrammeGenre.of(listOf("Drama", "Film"), "The Long Night"))
        assertEquals(ProgrammeGenre.NEWS, ProgrammeGenre.of(listOf("News / Current affairs"), "The National"))
        assertEquals(ProgrammeGenre.KIDS, ProgrammeGenre.of(listOf("Children's"), "Paw Patrol"))
        assertEquals(ProgrammeGenre.DOCUMENTARY, ProgrammeGenre.of(listOf("Documentary"), "Planet Earth"))
        assertEquals(ProgrammeGenre.SPORTS, ProgrammeGenre.of(listOf("Series", "Football"), "Match of the Day"))
    }

    @Test
    fun `a category it doesn't know leaves the kind unknown`() {
        assertNull(ProgrammeGenre.of(listOf("Series", "Comedy"), "Kim's Convenience"))
        // Science fiction isn't a documentary, and a historical drama isn't history.
        assertNull(ProgrammeGenre.of(listOf("Science fiction"), "Orphan Black"))
        assertNull(ProgrammeGenre.of(listOf("Historical drama"), "Murdoch Mysteries"))
    }

    @Test
    fun `without categories only a match between two sides is sports`() {
        assertEquals(ProgrammeGenre.SPORTS, ProgrammeGenre.of(emptyList(), "NHL: Maple Leafs vs Canadiens"))
        assertEquals(ProgrammeGenre.SPORTS, ProgrammeGenre.of(emptyList(), "Arsenal v Chelsea"))
        assertEquals(ProgrammeGenre.SPORTS, ProgrammeGenre.of(emptyList(), "Lakers @ Celtics"))
        assertEquals(ProgrammeGenre.SPORTS, ProgrammeGenre.of(emptyList(), "UFC 310: Pantoja vs. Asakura"))
        assertNull(ProgrammeGenre.of(emptyList(), "Fight Club"))
        assertNull(ProgrammeGenre.of(emptyList(), "The Evening News"))
    }

    @Test
    fun `codes round trip and never collide`() {
        for (g in ProgrammeGenre.entries) assertEquals(g, ProgrammeGenre.fromCode(g.code))
        assertEquals(
            ProgrammeGenre.entries.size,
            ProgrammeGenre.entries
                .map { it.code }
                .toSet()
                .size,
        )
        assertNull(ProgrammeGenre.fromCode(0))
    }
}
