package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * The names on "Who's watching?" must be readable on their own, whatever surrounds the tile. On Melvin's phone they
 * drew near-black on the dark theme (2026-11-19), because the name took the surrounding content colour, which falls
 * back to black. This draws a tile with nothing setting that colour and checks the name's pixels against the backdrop.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ProfileTileContrastTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nameIsReadableOnTheDarkTheme() = assertNameReadable(ThemeMode.DARK)

    @Test
    fun nameIsReadableOnTheLightTheme() = assertNameReadable(ThemeMode.LIGHT)

    private fun assertNameReadable(mode: ThemeMode) {
        var backdrop = Color.Unspecified
        compose.setContent {
            TunedTheme(mode = mode) {
                backdrop = MaterialTheme.colorScheme.background
                Box(Modifier.background(backdrop)) {
                    ProfileTile(PROFILE, selected = false, managing = false, onClick = {})
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithText(NAME, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val bg = backdrop.luminance()
        // Only the name itself is drawn (not the tile, whose avatar has its own white letter). The pixel that stands out
        // most from the backdrop is the inside of a letter's stroke: the text's own colour.
        var best = 1f
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                best = maxOf(best, contrast(Color(bitmap.getPixel(x, y)).luminance(), bg))
            }
        }
        assertTrue(best >= 4.5f, "$mode: the profile name's contrast is $best, below 4.5:1")
    }

    private fun contrast(
        a: Float,
        b: Float,
    ): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    private companion object {
        const val NAME = "Melvin"
        val PROFILE = ProfileEntity(id = 1, name = NAME, colour = 0, kids = false, pinHash = null, createdAt = 0)
    }
}
