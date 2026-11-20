package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The least room left between a dialog and the screen's edges, on a phone or a TV. */
internal val DialogEdgeMargin = 24.dp

/** The widest a dialog grows, on a tablet or a TV (Material 3's limit). */
internal val DialogMaxWidth = 560.dp

/**
 * The widest a dialog grows when it asks for more room: a TV's sign-in code dialogs, with a QR code beside the steps and
 * the code, so the code never wraps and the address keeps to one line (Melvin, 2026-11-20: "Can you make the pop-up
 * wider?"). Inside a 1080p TV's 960 dp less its overscan margin.
 */
val DialogWideMaxWidth = 720.dp

/**
 * Every pop-up in the app: Material 3's [AlertDialog], kept off the screen's edges. Material's own dialog fills the
 * width its window gives it, and on some devices (and in the JVM screenshots) that window is the whole screen, so a
 * long message stretched the dialog edge to edge with its rounded corners cut off (Melvin, 2026-11-19: "these pop ups
 * shouldn't take the full width"). Here it always leaves [DialogEdgeMargin] on each side, stops at [DialogMaxWidth],
 * and sits in the centre. Use this rather than Material's [AlertDialog]; detekt's ForbiddenImport rule enforces it.
 *
 * [maxWidth] above [DialogMaxWidth] (as [DialogWideMaxWidth]) draws the same card without Material's 560 dp cap, which
 * Material's dialog applies inside itself and so can't be lifted from outside.
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
    maxWidth: Dp = DialogMaxWidth,
) {
    if (maxWidth > DialogMaxWidth) {
        WideDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text, properties, maxWidth)
        return
    }
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

/** Material 3's dialog card, laid out as [AlertDialog] lays it out, up to [maxWidth] wide. */
@Composable
private fun WideDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    properties: DialogProperties,
    maxWidth: Dp,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // The platform's own width would cap the window before the card could use the room.
        properties =
            DialogProperties(
                dismissOnBackPress = properties.dismissOnBackPress,
                dismissOnClickOutside = properties.dismissOnClickOutside,
                securePolicy = properties.securePolicy,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = properties.decorFitsSystemWindows,
            ),
    ) {
        Surface(
            modifier =
                modifier
                    .fillMaxWidth()
                    .wrapContentWidth()
                    .padding(horizontal = DialogEdgeMargin)
                    .widthIn(min = WideDialogMinWidth, max = maxWidth),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(WideDialogPadding)) {
                icon?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) {
                        Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { it() }
                    }
                }
                title?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            Box(Modifier.padding(bottom = 16.dp)) { it() }
                        }
                    }
                }
                text?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(Modifier.weight(1f, fill = false).padding(bottom = WideDialogPadding)) { it() }
                        }
                    }
                }
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                    ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

private val WideDialogMinWidth = 280.dp
private val WideDialogPadding = 24.dp
