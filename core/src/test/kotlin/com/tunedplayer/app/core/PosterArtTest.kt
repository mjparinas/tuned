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
    fun `a show's wide picture is shown whole over a backdrop, never cropped`() {
        assertEquals(PosterArt.Fit.WHOLE_ON_BACKDROP, PosterArt.fit(400f, 225f, show = true))
        assertEquals(PosterArt.Fit.WHOLE_ON_BACKDROP, PosterArt.fit(300f, 300f, show = true))
        assertEquals(PosterArt.Fit.CROP, PosterArt.fit(500f, 750f, show = true))
        assertEquals(PosterArt.Fit.LOGO, PosterArt.fit(400f, 225f, show = false))
        assertEquals(PosterArt.Fit.CROP, PosterArt.fit(500f, 750f, show = false))
    }

    @Test
    fun `a show's card prefers TMDB's poster over the provider's cover`() {
        assertEquals("tmdb.jpg", PosterArt.showCover("tmdb.jpg", "still.jpg"))
        assertEquals("still.jpg", PosterArt.showCover(null, "still.jpg"))
        assertEquals("still.jpg", PosterArt.showCover(" ", "still.jpg"))
        assertNull(PosterArt.showCover(null, ""))
    }
}
