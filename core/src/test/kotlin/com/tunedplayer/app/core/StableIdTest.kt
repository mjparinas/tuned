package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StableIdTest {
    @Test
    fun `a show's id is pinned, so every device and the TV apps agree on it`() {
        // The TV apps work this out in TypeScript (tv/shared/src/favourites.ts); a change here breaks synced favourites.
        assertEquals(-8280274774364748610L, StableId.show("breaking bad"))
    }

    @Test
    fun `a show's id comes from its key, so it survives refreshes and renames of its episodes`() {
        assertEquals(StableId.show(Episodes.showKey("Breaking Bad")), StableId.show(Episodes.showKey("breaking  bad")))
        assertNotEquals(StableId.show("breaking bad"), StableId.show("better call saul"))
        // Never the id of a stream with the same name.
        assertNotEquals(StableId.fnv64("breaking bad"), StableId.show("breaking bad"))
    }
}
