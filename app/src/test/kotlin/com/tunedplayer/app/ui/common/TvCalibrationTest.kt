package com.tunedplayer.app.ui.common

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class TvCalibrationTest {
    @Test
    fun `defaults match the TV base margins`() {
        val m = TvCalibration().toMargin()
        assertEquals(TV_SIDE_MARGIN, m.start)
        assertEquals(TV_SIDE_MARGIN, m.end)
        assertEquals(TV_VERTICAL_MARGIN, m.top)
        assertEquals(TV_VERTICAL_MARGIN, m.bottom)
    }

    @Test
    fun `zoom above one stretches by shrinking the margins`() {
        val m = TvCalibration(zoom = 2f).toMargin()
        // Zoom is clamped to TV_ZOOM_MAX (1.4).
        assertEquals(TV_SIDE_MARGIN / TV_ZOOM_MAX, m.start)
        assertEquals(TV_VERTICAL_MARGIN / TV_ZOOM_MAX, m.top)
    }

    @Test
    fun `a right shift takes from the start and adds to the end`() {
        val m = TvCalibration(offsetX = 4).toMargin()
        assertEquals(TV_SIDE_MARGIN - 4.dp, m.start)
        assertEquals(TV_SIDE_MARGIN + 4.dp, m.end)
    }

    @Test
    fun `a large shift never goes negative`() {
        // Positive offset eats the start / top; the opposite side grows and stays non-negative.
        val m = TvCalibration(offsetX = TV_OFFSET_MAX, offsetY = TV_OFFSET_MAX).toMargin()
        assertEquals(0.dp, m.start)
        assertEquals(0.dp, m.top)
        assertEquals(TV_SIDE_MARGIN + TV_OFFSET_MAX.dp, m.end)
        assertEquals(TV_VERTICAL_MARGIN + TV_OFFSET_MAX.dp, m.bottom)
    }
}
