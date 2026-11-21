package com.tunedplayer.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Every panel that slides up from the bottom (Live TV's Filters, What's new): Material's bottom sheet on a phone or
 * tablet, and the whole screen on a TV (Melvin, 2026-11-21: "Dragging handles shouldn't really be on the TV app", and
 * of Filters: "I can't click to any of these selections"). A sheet opened half way with a drag handle a remote can't
 * drag, and the remote's focus slipped out of it into the guide behind, which scrolled under it. On a TV the panel is
 * its own full-screen window inside the overscan margin, so focus stays inside it, every option is a press of the
 * arrows away, the list scrolls as focus moves, and Back closes it. Use this rather than Material's
 * [ModalBottomSheet]; detekt's ForbiddenImport rule enforces it.
 */
@Composable
fun TunedSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isTelevision()) {
        TvFullScreen(onDismissRequest) { Column(Modifier.fillMaxSize(), content = content) }
    } else {
        ModalBottomSheet(onDismissRequest = onDismissRequest, content = content)
    }
}

/** A TV's full-screen window for a panel: the theme's background, the overscan margin, and Back to close. */
@Composable
fun TvFullScreen(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            TvSafeArea(content)
        }
    }
}
