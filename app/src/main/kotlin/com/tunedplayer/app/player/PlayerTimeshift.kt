package com.tunedplayer.app.player

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.container
import com.tunedplayer.app.data.AppSettings

/** The user agent streams are opened with: the one set in Settings, else Tuned's own. */
internal fun AppSettings.playerAgent(): String = userAgent.ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" }

/** The player's live rewind copy, built here so `PlayerActivity` stays small. */
internal fun ComponentActivity.playerTimeshift(settings: () -> AppSettings): LiveTimeshift = LiveTimeshift(
    context = this,
    scope = lifecycleScope,
    http = container.http,
    userAgent = { settings().playerAgent() },
    // Pause and rewind live TV is Premium (docs/decisions/premium.md).
    keepMinutes = { if (container.premium.unlocked.value) settings().liveRewindMinutes else 0 },
    resolve = container::resolveStream,
)
