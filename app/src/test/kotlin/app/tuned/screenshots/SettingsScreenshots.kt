package app.tuned.screenshots

import app.tuned.R
import app.tuned.data.AppReset
import app.tuned.data.CaptionStyle
import app.tuned.ui.CaptionSettingsRoute
import app.tuned.ui.RegionSettingsRoute
import app.tuned.ui.SettingsPageRoute
import app.tuned.ui.SettingsRoute
import app.tuned.ui.settings.SettingsPage
import app.tuned.ui.settings.SignOutDialog
import app.tuned.ui.settings.SignOutDownloadsDialog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner

/** Settings, every one of its pages, and the screens they open. */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenshots : Screenshots() {
    @Test fun settings() = shot("settings", SettingsRoute, wait = text(R.string.settings_playback))

    @Test
    fun captions() = shot(
        "settings-captions",
        CaptionSettingsRoute,
        wait = text(R.string.settings_captions),
        settings = DARK.copy(captions = CaptionStyle(enabled = true)),
    )

    @Test
    fun countries() = shot(
        "settings-countries",
        RegionSettingsRoute,
        wait = text(R.string.settings_countries),
        settings = DARK.copy(hiddenRegions = setOf("DE", "EXYU")),
    )

    @Test
    fun signOut() = shot(
        "settings-sign-out",
        SettingsPageRoute(SettingsPage.PLAYLISTS.name),
        wait = text(R.string.settings_sign_out_title),
        overlay = { SignOutDialog(onSignOut = {}, onCancel = {}) },
    )

    @Test
    fun signOutDownloads() = shot(
        "settings-sign-out-downloads",
        SettingsPageRoute(SettingsPage.PLAYLISTS.name),
        wait = text(R.string.settings_sign_out_delete),
        overlay = {
            SignOutDownloadsDialog(AppReset.Saved(count = 12, bytes = 8_400_000_000), onDelete = {}, onKeep = {}, onCancel = {})
        },
    )
}

/** Every page of Settings, so a new page or row shows up without anyone adding a line. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class SettingsPageScreenshots(
    private val page: SettingsPage,
) : Screenshots() {
    @Test fun page() = shot("settings-page-${page.name.lowercase()}", SettingsPageRoute(page.name), wait = text(page.title))

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun pages(): List<Array<Any>> = SettingsPage.entries.map { arrayOf(it) }
    }
}
