package com.tunedplayer.app.ui.common

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.settings.WelcomeScaffold
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * On a TV, "Who's watching?" drew its glow only inside the overscan margin, so the plain margin showed as a thick
 * white frame around it (Melvin's TV, 2026-11-20). The backdrop must reach the screen's edges: a pixel in the margin
 * matches the pixel just inside it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-mdpi")
class TvBackdropBleedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun profilesBackdropFillsTheMarginOnTheLightTheme() = assertNoFrame(ThemeMode.LIGHT)

    @Test
    fun profilesBackdropFillsTheMarginOnTheDarkTheme() = assertNoFrame(ThemeMode.DARK)

    private fun assertNoFrame(mode: ThemeMode) {
        compose.setContent {
            TunedTheme(mode = mode) {
                TvSafeArea { WelcomeScaffold { _ -> } }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // mdpi: 1 dp is 1 px. The top margin is 27 dp and the sides 48 dp; the glow is strongest at the top centre.
        val midX = bitmap.width / 2
        val midY = bitmap.height / 2
        assertClose(bitmap, midX, 2, midX, 30, "$mode top")
        assertClose(bitmap, 2, midY, 50, midY, "$mode side")
    }

    private fun assertClose(
        bitmap: Bitmap,
        outX: Int,
        outY: Int,
        inX: Int,
        inY: Int,
        where: String,
    ) {
        val a = bitmap.getPixel(outX, outY)
        val b = bitmap.getPixel(inX, inY)
        val diff = listOf(16, 8, 0).maxOf { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) }
        assertTrue(diff <= MAX_STEP, "$where: the margin (#${Integer.toHexString(a)}) differs from inside (#${Integer.toHexString(b)})")
    }

    private companion object {
        /** The glow fades smoothly, so a few dp apart differ by a step or two; the old frame differed by far more. */
        const val MAX_STEP = 6
    }
}
