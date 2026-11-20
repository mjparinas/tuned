package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** A pop-up never runs to the screen's edges (Melvin, 2026-11-19: "these pop ups shouldn't take the full width"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TunedDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun aLongMessageLeavesRoomAtBothEdgesOfAPhone() {
        assertMargins()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun aDialogOnATvStaysNarrowAndCentred() {
        val screen = assertMargins()
        // Material's widest dialog is 560 dp; on a 960 dp TV it sits in the middle with room either side.
        assertTrue(screen.right - screen.textRight >= 150.dp && screen.textLeft >= 150.dp, "$screen")
    }

    /** The sign-in code dialogs ask for more room on a TV, so the code beside the QR code keeps to one line. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun aWideDialogOnATvUsesMoreThanMaterialsWidthAndStaysCentred() {
        val screen = assertMargins(DialogWideMaxWidth)
        // Material's own dialog stops at 560 dp, so its text was at most 512 dp wide.
        assertTrue(screen.textRight - screen.textLeft > 600.dp, "$screen")
        assertTrue(screen.textLeft >= 100.dp && screen.right - screen.textRight >= 100.dp, "$screen")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun aWideDialogStillLeavesRoomAtBothEdgesOfAPhone() {
        assertMargins(DialogWideMaxWidth)
    }

    private data class Seen(
        val textLeft: Dp,
        val textRight: Dp,
        val right: Dp,
    )

    private fun assertMargins(maxWidth: Dp = DialogMaxWidth): Seen {
        var width = 0.dp
        compose.setContent {
            width =
                with(LocalDensity.current) {
                    LocalWindowInfo.current.containerSize.width
                        .toDp()
                }
            TunedDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                title = { Text("Sign out?") },
                // Filling the card's width, so the text's edges are the card's less its padding: Robolectric's stand-in
                // fonts measure the message far narrower than a real one.
                text = { Text(LONG, Modifier.fillMaxWidth()) },
                maxWidth = maxWidth,
            )
        }
        // Measured on the screen rather than in the dialog's own window, which may be narrower than the screen.
        val text = compose.onNodeWithText(LONG).fetchSemanticsNode()
        val seen =
            with(compose.density) {
                val left = text.positionOnScreen.x.toDp()
                Seen(left, left + text.size.width.toDp(), width)
            }
        // The card's 24 dp of inner padding plus at least most of the 24 dp margin outside it. Without the margin, the
        // card touched the screen's edges and the text started 24 dp in.
        assertTrue(seen.textLeft >= 40.dp, "Text starts ${seen.textLeft} from the left edge")
        assertTrue(seen.right - seen.textRight >= 40.dp, "Text ends ${seen.right - seen.textRight} from the right edge")
        return seen
    }

    private companion object {
        const val LONG =
            "Tuned goes back to how it was when installed. Every playlist and login, the channels and guide, profiles, " +
                "favourites, settings and PINs are removed, and Trakt and your Tuned account are signed out on this device."
    }
}
