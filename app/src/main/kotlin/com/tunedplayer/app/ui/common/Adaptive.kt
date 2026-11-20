package com.tunedplayer.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** The window's width in dp, for [com.tunedplayer.app.core.ScreenLayout]; it follows rotation, split screen and folding. */
@Composable
fun windowWidthDp(): Int {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) {
        size.width
            .toDp()
            .value
            .toInt()
    }
}
