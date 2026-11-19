package app.tuned.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.tuned.R
import app.tuned.container
import app.tuned.ui.ACTION_PLAY
import app.tuned.ui.EXTRA_PLAY_ID
import app.tuned.ui.MainActivity
import app.tuned.work.TvHome
import kotlinx.coroutines.flow.first

/**
 * The app icon's long-press menu (Melvin, 2026-11-19: "your own quick chips", from the Horizon Maps review): Search,
 * Live events, and the live channel watched last, so the most common ways in are one press from the home screen. They
 * are dynamic shortcuts, refreshed each time Tuned comes to the front, because the debug and release builds have
 * different package names, which a static shortcuts file can't name. An adult channel is never shown there, nothing is
 * shown while signed out, and Settings › Channels and lists › Shortcuts on the app icon turns them off. TVs have no such
 * menu, so nothing is set there.
 */
object AppShortcuts {
    /** Opens a screen named by [EXTRA_SCREEN] ([SEARCH] or [EVENTS]). */
    const val ACTION_OPEN = "app.tuned.action.OPEN"
    const val EXTRA_SCREEN = "screen"
    const val SEARCH = "search"
    const val EVENTS = "events"

    /** Opens the app where it was, from the home screen widgets: no screen of its own, and no jump to the last channel. */
    const val HOME = "home"

    /** Sets the shortcuts to match the settings and the last channel. Reads the database: call off the main thread. */
    suspend fun update(context: Context) {
        if (TvHome.isTv(context)) return
        val c = context.container
        val on =
            c.settings.settings
                .first()
                .appShortcuts && c.credentials.load() != null
        if (!on) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            return
        }
        val shortcuts =
            buildList {
                add(open(context, SEARCH, R.string.shortcut_search, R.drawable.ic_shortcut_search))
                add(open(context, EVENTS, R.string.events_title, R.drawable.ic_shortcut_events))
                c.db.items().lastWatchedChannel()?.takeUnless { it.adult }?.let { channel ->
                    val intent =
                        Intent(context, MainActivity::class.java)
                            .setAction(ACTION_PLAY)
                            .putExtra(EXTRA_PLAY_ID, channel.id)
                    add(
                        ShortcutInfoCompat
                            .Builder(context, "last")
                            .setShortLabel(channel.name.take(SHORT_LABEL))
                            .setLongLabel(context.getString(R.string.shortcut_last_channel, channel.name))
                            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_play))
                            .setIntent(intent)
                            .build(),
                    )
                }
            }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    private fun open(
        context: Context,
        screen: String,
        label: Int,
        icon: Int,
    ): ShortcutInfoCompat = ShortcutInfoCompat
        .Builder(context, screen)
        .setShortLabel(context.getString(label))
        .setIcon(IconCompat.createWithResource(context, icon))
        .setIntent(Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_SCREEN, screen))
        .build()

    /** Launchers cut short labels at about this many characters. */
    private const val SHORT_LABEL = 25
}
