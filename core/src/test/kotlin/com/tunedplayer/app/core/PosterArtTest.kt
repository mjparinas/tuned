package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosterArtTest {
    @Test
    fun `tall pictures are posters and wide or square ones are logos`() {
        assertTrue(PosterArt.isPoster(500f, 750f))
        assertTrue(PosterArt.isPoster(342f, 513f))
        assertFalse(PosterArt.isPoster(300f, 300f)) // square channel logo
        assertFalse(PosterArt.isPoster(400f, 225f)) // wide logo
        assertFalse(PosterArt.isPoster(0f, 0f))
    }

    @Test
    fun `a show's card fills its tile even with a wide episode still`() {
        assertTrue(PosterArt.fills(400f, 225f, show = true))
        assertTrue(PosterArt.fills(300f, 300f, show = true))
        assertFalse(PosterArt.fills(400f, 225f, show = false))
        assertTrue(PosterArt.fills(500f, 750f, show = false))
    }

    @Test
    fun `a show's card prefers TMDB's poster over the provider's cover`() {
        assertEquals("tmdb.jpg", PosterArt.showCover("tmdb.jpg", "still.jpg"))
        assertEquals("still.jpg", PosterArt.showCover(null, "still.jpg"))
        assertEquals("still.jpg", PosterArt.showCover(" ", "still.jpg"))
        assertNull(PosterArt.showCover(null, ""))
    }
}
