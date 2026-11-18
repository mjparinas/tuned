package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarsTest {
    @Test
    fun keysAreUniqueAndTheSetsAreSplitByPremium() {
        assertEquals(Avatars.all.size, Avatars.all.map { it.key }.toSet().size)
        assertEquals(Avatars.all.size, Avatars.all.map { it.glyph }.toSet().size)
        assertTrue(Avatars.free.none { it.premium })
        assertTrue(Avatars.premium.all { it.premium })
        assertTrue(Avatars.free.isNotEmpty() && Avatars.premium.isNotEmpty())
    }

    @Test
    fun storedKeysNeverChange() {
        // Keys live in the database: renaming one would blank every profile wearing it.
        assertEquals(
            listOf("fox", "panda", "tiger", "lion", "frog", "monkey", "penguin", "dog", "cat", "koala", "bear", "rabbit"),
            Avatars.free.map { it.key },
        )
        assertEquals(
            listOf("dragon", "unicorn", "trex", "owl", "shark", "octopus", "rocket", "alien", "robot", "wizard", "crown", "diamond"),
            Avatars.premium.map { it.key },
        )
    }

    @Test
    fun unknownOrMissingKeysFallBackToTheLetter() {
        assertNull(Avatars.find(null))
        assertNull(Avatars.find("from-a-newer-version"))
        assertEquals("🦊", Avatars.find("fox")?.glyph)
    }

    @Test
    fun premiumAvatarsNeedPremiumButAreKeptWhenItEnds() {
        assertTrue(Avatars.canPick("fox", current = null, premiumUnlocked = false))
        assertTrue(Avatars.canPick(null, current = "dragon", premiumUnlocked = false))
        assertFalse(Avatars.canPick("dragon", current = null, premiumUnlocked = false))
        assertFalse(Avatars.canPick("dragon", current = "unicorn", premiumUnlocked = false))
        assertTrue(Avatars.canPick("dragon", current = "dragon", premiumUnlocked = false))
        assertTrue(Avatars.canPick("dragon", current = null, premiumUnlocked = true))
        assertFalse(Avatars.canPick("from-a-newer-version", current = null, premiumUnlocked = true))
    }
}
