package app.tuned.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.tuned.R
import app.tuned.container
import app.tuned.player.PlayerActivity
import app.tuned.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick-settings tile: one tap goes straight back to the live channel watched last (or opens the app if there's none). */
class LastChannelTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_last_channel)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::open) else open()
    }

    private fun open() {
        val c = applicationContext.container
        c.appScope.launch {
            val channel = if (c.credentials.load() != null) c.db.items().lastWatchedChannel() else null
            val intent =
                (
                    if (channel !=
                        null
                    ) {
                        PlayerActivity.intent(this@LastChannelTile, channel.id)
                    } else {
                        Intent(this@LastChannelTile, MainActivity::class.java)
                    }
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            withContext(Dispatchers.Main) { launch(intent) }
        }
    }

    // The Intent form is deprecated and throws on Android 14+, so it's only used below that; the PendingIntent form needs 14.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
