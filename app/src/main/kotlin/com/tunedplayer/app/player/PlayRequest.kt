package com.tunedplayer.app.player

import android.content.Intent
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.PlayGate
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.db.ItemEntity

/** What a player intent asks to play, so a second tap on the same thing doesn't restart it. */
internal data class PlayRequest(
    val id: Long,
    val tune: String?,
    val recording: Long,
    val from: Long,
) {
    companion object {
        fun of(intent: Intent?): PlayRequest = PlayRequest(
            id = intent?.getLongExtra(PlayerActivity.EXTRA_ID, 0) ?: 0,
            tune = intent?.getStringExtra(PlayerActivity.EXTRA_TUNE),
            recording = intent?.getLongExtra(PlayerActivity.EXTRA_RECORDING, 0) ?: 0,
            from = intent?.getLongExtra(PlayerActivity.EXTRA_FROM, 0) ?: 0,
        )
    }
}

/**
 * Whether this profile may play [item]: the tile, the widget, TV home cards and other apps' play intents skip the
 * lists' filters, so the player checks again. Out here so [PlayerActivity] stays under detekt's size limit.
 */
internal fun AppSettings.mayPlay(item: ItemEntity): Boolean = PlayGate.allowed(
    item.adult,
    item.groupName,
    item.name,
    live = item.kind == ContentKind.LIVE.code,
    showAdult = showAdult,
    kidsProfile = kidsProfile,
)
