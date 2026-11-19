package app.tuned.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * What a screen reader hears in Settings: switches say on or off (not "selected"), section titles are headings, and
 * every caption colour has a name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSwitchRowIsReadAsOnOrOff() {
        var on = false
        compose.setContent { SwitchRow("Autoplay", on) { on = it } }
        val row = compose.onNodeWithText("Autoplay")
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        row.performClick()
        assertTrue(on)
    }

    @Test
    fun aSectionHeaderIsAHeading() {
        compose.setContent { SectionHeader("Playback") }
        // Shown in small capitals, read as written.
        compose.onNodeWithText("PLAYBACK").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Playback").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun captionColoursHaveNames() {
        compose.setContent { ColourRow("Text colour", selected = 0xFFFFFF00.toInt()) {} }
        compose.onNodeWithContentDescription("Yellow").assertIsSelected()
        compose.onNodeWithContentDescription("White").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))
    }
}
