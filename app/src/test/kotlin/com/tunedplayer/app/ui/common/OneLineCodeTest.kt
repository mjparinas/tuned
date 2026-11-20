package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A sign-in code never wraps (Melvin, 2026-11-20: "I don't ever want to see the code text wrapped to the next line"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OneLineCodeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aCodeInTooLittleRoomShrinksRatherThanWrapping() {
        // About as narrow as the steps column was on Melvin's TV, where "ECUZ-KH4X" broke after the dash.
        compose.setContent {
            Box(Modifier.width(120.dp)) {
                OneLineCode(CODE, TextStyle(fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            }
        }
        val layout = layoutOf(CODE)
        assertEquals(1, layout.lineCount)
        assertFalse(layout.hasVisualOverflow, "The code runs past its room")
    }

    @Test
    fun anAddressNeverBreaksAfterADot() {
        val site = "www.tunedplayer.com/account"
        val joined = unbreakable(site)
        assertEquals(site, joined.replace(WORD_JOINER, ""))
        assertEquals(site.length * 2 - 1, joined.length)
        assertEquals("", unbreakable(""))
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.single()
    }

    private companion object {
        const val CODE = "ECUZ-KH4X"
    }
}
