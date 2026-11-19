package app.tuned.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp

/** The [layoutId] that keeps a child of [SettingsGroups] out of the cards: section headers, the search box, notes. */
internal const val SETTINGS_HEADER = "settings-header"

/** True inside [SettingsGroups], where a section header lines up with the text on the cards below it. */
internal val LocalInCards = staticCompositionLocalOf { false }

/** Marks a child of [SettingsGroups] as standing between cards rather than in one. */
internal fun Modifier.outsideCards(): Modifier = layoutId(SETTINGS_HEADER)

/**
 * Settings' grouped look (Melvin, 2026-11-19, after Horizon Maps' screenshots): the rows between two section headers sit
 * together on one rounded card. The pages stay flat lists of rows and headers, so no page file changes and a row added to
 * a page joins its section's card by itself; this layout finds the runs of rows between headers ([outsideCards]) and draws
 * a card behind each run. Each row's own press ripple is clipped to its card's rounded corners.
 */
@Composable
internal fun SettingsGroups(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cards = remember { CardBounds() }
    val colour = MaterialTheme.colorScheme.surfaceContainer
    Layout(
        content = { CompositionLocalProvider(LocalInCards provides true, content) },
        modifier =
            modifier.drawWithContent {
                val radius = CornerRadius(CARD_RADIUS.toPx())
                cards.rects.forEach { r -> drawRoundRect(colour, Offset(r.left, r.top), Size(r.width, r.height), radius) }
                val clip =
                    Path().apply {
                        cards.rects.forEach { addRoundRect(RoundRect(it, radius)) }
                        cards.open.forEach { addRect(it) }
                    }
                clipPath(clip) { this@drawWithContent.drawContent() }
            },
    ) { measurables, constraints ->
        val inset = CARD_MARGIN.roundToPx()
        val padding = CARD_PADDING.roundToPx()
        val gap = CARD_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val inner = loose.copy(maxWidth = (constraints.maxWidth - 2 * inset).coerceAtLeast(0))
        val placed =
            measurables.map { m ->
                val outside = m.layoutId == SETTINGS_HEADER
                outside to m.measure(if (outside) loose else inner)
            }
        val ys = IntArray(placed.size)
        val rects = ArrayList<Rect>()
        val open = ArrayList<Rect>()
        var y = 0
        var runStart = -1

        fun closeRun() {
            if (runStart < 0) return
            y += padding
            rects += Rect(inset.toFloat(), runStart.toFloat(), (constraints.maxWidth - inset).toFloat(), y.toFloat())
            y += gap
            runStart = -1
        }
        placed.forEachIndexed { i, (outside, p) ->
            if (outside) {
                closeRun()
                ys[i] = y
                open += Rect(0f, y.toFloat(), constraints.maxWidth.toFloat(), (y + p.height).toFloat())
            } else if (runStart < 0) {
                if (i == 0) y += gap
                runStart = y
                y += padding
                ys[i] = y
            } else {
                ys[i] = y
            }
            y += p.height
        }
        closeRun()
        cards.rects = rects
        cards.open = open
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placed.maxOfOrNull { it.second.width } ?: 0
        layout(width, y.coerceAtLeast(constraints.minHeight)) {
            placed.forEachIndexed { i, (outside, p) -> p.placeRelative(if (outside) 0 else inset, ys[i]) }
        }
    }
}

/** Where the cards are, from the last layout, for drawing. Not state: a new layout always redraws. */
private class CardBounds {
    var rects: List<Rect> = emptyList()
    var open: List<Rect> = emptyList()
}

private val CARD_MARGIN = 16.dp
private val CARD_PADDING = 6.dp
private val CARD_GAP = 8.dp
private val CARD_RADIUS = 24.dp

/**
 * The colours of a page's round icon badge and of What's new's pills. Each has a deep shade for light themes and a soft
 * one for dark themes, so the icon, and the pill's word, reads at 4.5:1 or better on its own tinted circle or pill
 * either way (checked against every card colour Settings uses).
 */
internal enum class BadgeHue(
    private val light: Long,
    private val dark: Long,
) {
    BLUE(0xFF14509A, 0xFF8AB4F8),
    VIOLET(0xFF5235C8, 0xFFB9A6FF),
    TEAL(0xFF005D52, 0xFF4DD0C2),
    RED(0xFF9E1B1B, 0xFFFF8A80),
    AMBER(0xFF7A4700, 0xFFFFC266),
    GREEN(0xFF1B5E20, 0xFF81C995),
    ;

    @Composable
    fun colour(): Color = Color(if (MaterialTheme.colorScheme.surface.luminance() < DARK) dark else light)

    private companion object {
        const val DARK = 0.5f
    }
}

/** A round tinted badge with an icon, as each Settings page and each What's new card has. */
@Composable
internal fun IconBadge(
    icon: ImageVector,
    hue: BadgeHue,
    modifier: Modifier = Modifier,
) {
    val colour = hue.colour()
    Box(
        modifier.size(44.dp).background(colour.copy(alpha = BADGE_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(22.dp))
    }
}

private const val BADGE_ALPHA = 0.18f
