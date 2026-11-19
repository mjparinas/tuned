package com.tunedplayer.app.ui.settings

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.data.Accent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Every accent swatch fits on a small phone (Melvin, 2026-11-19: the red one was cut off on the right). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class AccentRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theLastSwatchFitsTheRow() {
        compose.setContent { AccentRow(Accent.VIOLET) {} }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val last = compose.onNodeWithContentDescription("Red").getUnclippedBoundsInRoot()
        // The row has 16 dp of padding at each side.
        assertTrue(last.right <= root.right - 16.dp, "Red ends at ${last.right} on a ${root.right} wide screen")
        assertTrue(last.right - last.left >= 24.dp, "Swatches are ${last.right - last.left} wide")
    }
}
