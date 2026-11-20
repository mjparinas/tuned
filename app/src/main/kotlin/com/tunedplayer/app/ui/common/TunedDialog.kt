package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** The least room left between a dialog and the screen's edges, on a phone or a TV. */
internal val DialogEdgeMargin = 24.dp

/** The widest a dialog grows, on a tablet or a TV (Material 3's limit). */
internal val DialogMaxWidth = 560.dp

/**
 * Every pop-up in the app: Material 3's [AlertDialog], kept off the screen's edges. Material's own dialog fills the
 * width its window gives it, and on some devices (and in the JVM screenshots) that window is the whole screen, so a
 * long message stretched the dialog edge to edge with its rounded corners cut off (Melvin, 2026-11-19: "these pop ups
 * shouldn't take the full width"). Here it always leaves [DialogEdgeMargin] on each side, stops at [DialogMaxWidth],
 * and sits in the centre. Use this rather than Material's [AlertDialog]; detekt's ForbiddenImport rule enforces it.
 */
@Composable
fun TunedDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        // Take the window's width and centre the card in it, so the dialog is centred whether the window wraps it or
        // fills the screen; the margin sits outside the card, so the card itself never reaches the edge.
        modifier =
            modifier
                .fillMaxWidth()
                .wrapContentWidth()
                .padding(horizontal = DialogEdgeMargin)
                .widthIn(max = DialogMaxWidth),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        properties = properties,
    )
}
