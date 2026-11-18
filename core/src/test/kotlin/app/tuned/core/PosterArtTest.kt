package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
