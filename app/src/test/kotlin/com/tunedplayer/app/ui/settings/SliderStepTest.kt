package com.tunedplayer.app.ui.settings

import org.junit.Test
import kotlin.test.assertEquals

/** One press of left or right on a slider: the next stop, never past either end. */
class SliderStepTest {
    private val size = 0.5f..2.5f

    @Test
    fun movesOneStop() {
        assertEquals(1.25f, sliderStep(1f, size, steps = 7, more = true))
        assertEquals(0.75f, sliderStep(1f, size, steps = 7, more = false))
    }

    @Test
    fun stopsAtTheEnds() {
        assertEquals(2.5f, sliderStep(2.5f, size, steps = 7, more = true))
        assertEquals(0.5f, sliderStep(0.5f, size, steps = 7, more = false))
    }

    @Test
    fun aValueBetweenStopsGoesToTheNextOne() {
        assertEquals(1.25f, sliderStep(1.05f, size, steps = 7, more = true))
    }

    @Test
    fun aSliderWithoutStepsMovesATwentieth() {
        assertEquals(0.05f, sliderStep(0f, 0f..1f, steps = 0, more = true), 0.0001f)
    }
}
