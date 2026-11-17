package app.tuned.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A clear outline in [color] while the clickable after it has focus, so a TV remote (or a keyboard) shows where it is.
 * Touch never focuses these items, so on a phone nothing changes. Put it before `clickable` in the chain.
 */
fun Modifier.focusRing(
    shape: Shape,
    color: Color,
    width: Dp = 3.dp,
): Modifier = this then FocusRingElement(shape, color, width)

private data class FocusRingElement(
    val shape: Shape,
    val color: Color,
    val width: Dp,
) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, color, width)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.color = color
        node.width = width
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusRing"
        properties["shape"] = shape
        properties["color"] = color
        properties["width"] = width
    }
}

private class FocusRingNode(
    var shape: Shape,
    var color: Color,
    var width: Dp,
) : Modifier.Node(),
    FocusEventModifierNode,
    DrawModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused != focused) {
            focused = focusState.isFocused
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) {
            // Centred on the edge, so twice the width: the outer half is cut off where the item is clipped to its shape.
            drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(width.toPx() * 2))
        }
    }
}
