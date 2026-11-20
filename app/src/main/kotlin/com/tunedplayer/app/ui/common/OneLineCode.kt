package com.tunedplayer.app.ui.common

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * A pairing or sign-in code (the Tuned account, Trakt, signing in a TV with a phone), always on one line (Melvin,
 * 2026-11-20: "I don't ever want to see the code text wrapped to the next line"). It is drawn at [style]'s size when
 * there is room, and steps down as far as [MinCodeSize] when there isn't (a narrow phone, a long language, large text),
 * rather than breaking at the dash as a plain Text did on a TV.
 */
@Composable
fun OneLineCode(
    code: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val color = if (style.color.isSpecified) style.color else LocalContentColor.current
    val largest = if (style.fontSize.isSpecified) style.fontSize else DefaultCodeSize
    BasicText(
        code,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        autoSize =
            TextAutoSize.StepBased(
                minFontSize = MinCodeSize,
                maxFontSize = if (largest < MinCodeSize) MinCodeSize else largest,
                stepSize = 1.sp,
            ),
    )
}

/**
 * [text] (a web address) with a word joiner between every character, so a line never breaks inside it: Android broke
 * "www.tunedplayer.com/account" after the dot on a TV (Melvin's photo, 2026-11-20). The whole address moves to the next
 * line instead. The joiners take no space and aren't read aloud, and an address too long for a whole line still breaks
 * rather than running off the edge.
 */
fun unbreakable(text: String): String = text.toList().joinToString(WORD_JOINER)

/** U+2060, which forbids a line break between the characters either side of it. */
internal const val WORD_JOINER = "⁠"

private val MinCodeSize = 12.sp
private val DefaultCodeSize = 28.sp
