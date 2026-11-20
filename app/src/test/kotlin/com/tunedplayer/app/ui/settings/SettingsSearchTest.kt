package com.tunedplayer.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test
    fun `typed words ignore case, accents and extra spaces`() {
        assertEquals(listOf("picture", "in"), settingsWords("  Picture-IN "))
        assertEquals(emptyList(), settingsWords("   "))
    }

    @Test
    fun `a row matches only when it has every typed word`() {
        val words = settingsWords("pic pip")
        assertFalse(hasWords("Picture-in-picture", words))
        assertTrue(hasWords("Picture-in-picture Shrinks to a small window (pip)", words))
        assertTrue(hasWords("Thème", settingsWords("theme")))
    }
}
