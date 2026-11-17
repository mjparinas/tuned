package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholdersTest {
    @Test
    fun emptyEventSlotsArePlaceholders() {
        assertTrue(Placeholders.isPlaceholder("- NO EVENT STREAMING - | 8K EXCLUSIVE | SE: PLAY+ PPV 43"))
        assertTrue(Placeholders.isPlaceholder("US| PPV 12: No Events Scheduled"))
        assertTrue(Placeholders.isPlaceholder("DAZN 4 (No Live Event)"))
    }

    @Test
    fun realChannelsAndRenamedSlotsAreNot() {
        assertFalse(Placeholders.isPlaceholder("SE: PLAY+ PPV 43 | UFC 310: Pantoja vs Asakura"))
        assertFalse(Placeholders.isPlaceholder("CA| CP24 HD"))
        assertFalse(Placeholders.isPlaceholder("Snoeventing TV")) // a word that only contains it
    }
}
