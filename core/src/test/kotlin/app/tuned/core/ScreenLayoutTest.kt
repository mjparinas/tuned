package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenLayoutTest {
    @Test
    fun widthsFollowMaterialSizeClasses() {
        assertEquals(ScreenLayout.Width.COMPACT, ScreenLayout.width(411))
        assertEquals(ScreenLayout.Width.MEDIUM, ScreenLayout.width(600))
        assertEquals(ScreenLayout.Width.MEDIUM, ScreenLayout.width(839))
        assertEquals(ScreenLayout.Width.EXPANDED, ScreenLayout.width(840))
    }

    @Test
    fun phonesKeepTheBottomBarAndTabletsGetARail() {
        assertFalse(ScreenLayout.rail(411))
        assertTrue(ScreenLayout.rail(673)) // a foldable, unfolded, in portrait
        assertTrue(ScreenLayout.rail(1280))
    }

    @Test
    fun detailPanesOnlyOnWideScreens() {
        assertFalse(ScreenLayout.panes(411))
        assertFalse(ScreenLayout.panes(800))
        assertTrue(ScreenLayout.panes(1280))
        assertTrue(ScreenLayout.panes(900))
    }

    @Test
    fun postersGrowOffPhones() {
        assertEquals(112, ScreenLayout.posterMinDp(411))
        assertEquals(140, ScreenLayout.posterMinDp(800))
    }

    @Test
    fun guideSitsBesideTheVideoOnlyInWideLandscape() {
        assertTrue(ScreenLayout.guideBesideVideo(1280, 800)) // a 10-inch tablet, landscape
        assertTrue(ScreenLayout.guideBesideVideo(960, 600))
        assertFalse(ScreenLayout.guideBesideVideo(800, 1280)) // the same tablet, portrait
        assertFalse(ScreenLayout.guideBesideVideo(891, 411)) // a phone in landscape
        assertFalse(ScreenLayout.guideBesideVideo(411, 891))
    }
}
