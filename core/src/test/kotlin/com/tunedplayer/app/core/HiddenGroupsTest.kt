package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HiddenGroupsTest {
    @Test
    fun keysNameTheSectionSoSameNamedGroupsStayApart() {
        assertEquals("g:1:Action", HiddenGroups.key(ContentKind.MOVIE.code, "Action"))
        assertNotEquals(HiddenGroups.key(ContentKind.MOVIE.code, "Action"), HiddenGroups.key(ContentKind.SERIES.code, "Action"))
    }

    @Test
    fun countryCodesAreNeverMistakenForGroups() {
        assertTrue(HiddenGroups.isGroupKey(HiddenGroups.key(0, "UK | Sports")))
        assertFalse(HiddenGroups.isGroupKey("UK"))
        assertFalse(HiddenGroups.isGroupKey("GR"))
    }
}
