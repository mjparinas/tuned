package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.data.db.ProfileEntity
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
 * The accent ring on a profile tile sits on the picture (the phone's selected look), not around the whole tile
 * including the name. TV focus uses this same ring (Melvin, 2026-11-22). A tile-wide focusRing would put accent on
 * the bottom edge around the name; this checks that band stays clear.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ProfileTileFocusRingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun avatarRingLeavesTheNameClear() {
        var primary = Color.Unspecified
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                primary = MaterialTheme.colorScheme.primary
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    ProfileTile(
                        PROFILE,
                        selected = true,
                        managing = false,
                        onClick = {},
                        modifier = Modifier.testTag(TILE_TAG),
                    )
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(TILE_TAG).captureToImage().asAndroidBitmap()
        var ringHits = 0
        var nameBandHits = 0
        val nameBandTop = (bitmap.height * 0.78f).toInt()
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (!near(Color(bitmap.getPixel(x, y)), primary)) continue
                ringHits++
                if (y >= nameBandTop) nameBandHits++
            }
        }
        assertTrue(ringHits > 20, "selected tile should draw an accent ring ($ringHits accent pixels)")
        assertTrue(nameBandHits == 0, "accent ring must not frame the name ($nameBandHits pixels below $nameBandTop of ${bitmap.height})")
    }

    private fun near(
        pixel: Color,
        target: Color,
    ): Boolean = abs(pixel.red - target.red) < 0.08f &&
        abs(pixel.green - target.green) < 0.08f &&
        abs(pixel.blue - target.blue) < 0.08f &&
        pixel.alpha > 0.85f

    private companion object {
        const val TILE_TAG = "profile-tile"

        // colour away from the theme primary so the avatar gradient is not counted as the ring.
        val PROFILE = ProfileEntity(id = 1, name = "Alex", colour = 3, kids = false, pinHash = null, createdAt = 0)
    }
}
