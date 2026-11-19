package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionLanguagesTest {
    @Test
    fun englishCoversTheEnglishSpeakingTags() {
        for (tag in listOf("EN", "US", "UK", "CA", "AU", "IE", "NZ")) assertEquals("en", RegionLanguages.of(tag))
    }

    @Test
    fun countriesWithSeveralLanguagesBelongToNone() {
        assertNull(RegionLanguages.of("BE"))
        assertNull(RegionLanguages.of("CH"))
        assertNull(RegionLanguages.of("IN"))
        assertNull(RegionLanguages.of("XYZ"))
    }

    @Test
    fun groupsOnlyTheTagsThePlaylistHas() {
        val groups = RegionLanguages.group(listOf("US", "FR", "UK", "BE", "MX"))
        assertEquals(listOf("en" to listOf("UK", "US"), "es" to listOf("MX"), "fr" to listOf("FR")), groups)
    }
}
