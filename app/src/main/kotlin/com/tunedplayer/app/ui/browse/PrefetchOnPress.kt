package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.tunedplayer.app.container

/**
 * On a phone or tablet, a film's or show's page starts loading the moment a finger comes down on its poster, before
 * the tap ends ([com.tunedplayer.app.work.TitlePrefetch]). It only watches the touch, so the poster's own tap and
 * long press work as before.
 */
@Composable
internal fun Modifier.prefetchOnPress(
    name: String,
    tv: Boolean,
): Modifier {
    val prefetch = LocalContext.current.container.titlePrefetch
    return pointerInput(name, tv) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            prefetch.pressed(name, tv)
        }
    }
}
