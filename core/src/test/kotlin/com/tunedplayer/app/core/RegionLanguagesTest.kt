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

    @Test
    fun englishFilmsShowWithOnlyCanadaAndTheUnitedStatesOn() {
        // Melvin, 2026-11-20: films and shows filed under "EN - Action" vanished with only CA and US on.
        val seen = setOf("EN", "US", "CA", "UK", "FR", "IN", "none")
        val hidden = setOf("EN", "UK", "FR", "IN", "none")
        val result = RegionLanguages.effectiveHidden(hidden, seen)
        assertEquals(setOf("UK", "FR", "IN", "none"), result)
    }

    @Test
    fun englishHidesWhenEveryEnglishCountryIsOff() {
        val seen = setOf("EN", "US", "CA", "FR")
        assertEquals(setOf("US", "CA", "EN"), RegionLanguages.effectiveHidden(setOf("US", "CA"), seen))
    }

    @Test
    fun englishKeepsItsOwnSwitchWhenNoEnglishCountryIsThere() {
        val seen = setOf("EN", "FR")
        assertEquals(setOf("EN"), RegionLanguages.effectiveHidden(setOf("EN"), seen))
        assertEquals(emptySet(), RegionLanguages.effectiveHidden(emptySet(), seen))
    }

    @Test
    fun otherTagsAreLeftAsTheyAre() {
        // Spanish, French and Arabic lead with a country, so their tags keep their own switches.
        val seen = setOf("ES", "MX", "LAT", "FR", "AR", "SA")
        val hidden = setOf("ES", "LAT", "AR")
        assertEquals(hidden, RegionLanguages.effectiveHidden(hidden, seen))
    }

    @Test
    fun onlyEnglishFollowsWhenACountryOfItIsThere() {
        assertEquals(setOf("EN"), RegionLanguages.following(listOf("EN", "US", "FR")))
        assertEquals(emptySet(), RegionLanguages.following(listOf("EN", "FR")))
        assertEquals(emptySet(), RegionLanguages.following(listOf("US", "ES", "MX", "LAT")))
    }
}
