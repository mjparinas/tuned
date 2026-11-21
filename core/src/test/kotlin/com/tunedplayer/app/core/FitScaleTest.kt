package com.tunedplayer.app.core

import com.tunedplayer.app.core.FitScale.Mode
import kotlin.test.Test
import kotlin.test.assertEquals

class FitScaleTest {
    private val wide = 16f / 9f

    @Test
    fun aSixteenByNinePictureOnASixteenByNineTvStillChangesForZoomAndStretch() {
        assertEquals(FitScale.NONE, FitScale.of(Mode.FIT, wide, wide))
        assertEquals(FitScale.Scale(4f / 3f, 4f / 3f), FitScale.of(Mode.ZOOM, wide, wide))
        assertEquals(FitScale.Scale(4f / 3f, 1f), FitScale.of(Mode.STRETCH, wide, wide))
    }

    @Test
    fun aNearlyMatchingShapeCountsAsTheSame() {
        // 1920×1080 on a 1920×1088 surface, or a TV's 3840×2160 mode.
        assertEquals(FitScale.Scale(4f / 3f, 4f / 3f), FitScale.of(Mode.ZOOM, 1920f / 1080f, 1920f / 1088f))
    }

    @Test
    fun aDifferentShapeIsLeftToMedia3() {
        // A 4:3 channel on a TV, or 16:9 on a 20:9 phone: Media3's resize mode already changes the picture.
        assertEquals(FitScale.NONE, FitScale.of(Mode.ZOOM, 4f / 3f, wide))
        assertEquals(FitScale.NONE, FitScale.of(Mode.STRETCH, wide, 20f / 9f))
    }

    @Test
    fun anUnknownSizeLeavesThePictureAlone() {
        assertEquals(FitScale.NONE, FitScale.of(Mode.ZOOM, 0f, wide))
        assertEquals(FitScale.NONE, FitScale.of(Mode.STRETCH, wide, 0f))
    }
}
