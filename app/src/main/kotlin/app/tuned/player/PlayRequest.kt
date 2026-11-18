package app.tuned.player

import android.content.Intent

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
