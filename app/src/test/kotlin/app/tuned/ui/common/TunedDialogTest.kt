package app.tuned.ui.common

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    private data class Seen(
        val textLeft: Dp,
        val textRight: Dp,
        val right: Dp,
    )

    private fun assertMargins(): Seen {
        var width = 0.dp
        compose.setContent {
            width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
            TunedDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                title = { Text("Sign out?") },
                text = { Text(LONG) },
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
                "favourites, settings and PINs are removed, and Trakt and Google Drive are disconnected."
    }
}
