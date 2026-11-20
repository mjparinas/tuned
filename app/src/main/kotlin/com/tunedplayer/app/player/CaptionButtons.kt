package com.tunedplayer.app.player

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * The subtitle search and subtitle timing buttons sit in the bottom bar just before the CC button, so everything about
 * captions is in one place (Melvin, 2026-11-18). Media3 draws that bar, so the buttons go into the CC button's own row
 * and move with it when a narrow screen folds the row into the overflow menu. False when this Media3 has no CC button,
 * and the caller keeps them in the top toolbar.
 */
@OptIn(UnstableApi::class)
internal fun placeBesideCaptions(
    playerView: PlayerView,
    buttons: List<View>,
): Boolean {
    val cc = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle) ?: return false
    val row = cc.parent as? ViewGroup ?: return false
    for (button in buttons) {
        (button.parent as? ViewGroup)?.removeView(button)
        row.addView(button, row.indexOfChild(cc), ViewGroup.LayoutParams(cc.layoutParams.width, cc.layoutParams.height))
    }
    return true
}
