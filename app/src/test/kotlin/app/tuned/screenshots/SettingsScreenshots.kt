package app.tuned.screenshots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tuned.R
import app.tuned.data.AppReset
import app.tuned.data.CaptionStyle
import app.tuned.data.LighterMode
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
import org.robolectric.annotation.Config

/** Settings, every one of its pages, and the screens they open. */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenshots : Screenshots() {
    @Test fun settings() = shot("settings", SettingsRoute, wait = text(R.string.settings_playback))

    /** The cards and coloured badges in the light theme, where the badge colours are deepest. */
    @Test fun settingsLight() = shot("settings-light", SettingsRoute, wait = text(R.string.settings_playback), settings = LIGHT)

    /** Lighter mode turned on, so its row says what it does. */
    @Test
    fun lighterMode() = shot(
        "settings-lighter-mode",
        SettingsPageRoute(SettingsPage.APPEARANCE.name),
        wait = text(R.string.settings_lighter),
        settings = DARK.copy(lighterMode = LighterMode.ON),
    )

    /** Report a problem, under Troubleshooting in Settings › About. */
    @Test
    fun reportProblem() = shot(
        "settings-report-problem",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.settings_user_agent),
        then = { compose.onNodeWithText(text(R.string.report_title)).performScrollTo() },
    )

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

    /** The sign-out dialog on a TV: no wider than 560 dp, in the centre. */
    @Test
    @Config(qualifiers = TV)
    fun signOutTv() = shot(
        "settings-sign-out-tv",
        SettingsPageRoute(SettingsPage.PLAYLISTS.name),
        wait = text(R.string.settings_sign_out_title),
        overlay = { SignOutDialog(onSignOut = {}, onCancel = {}) },
    )

    /** The user agent picklist, opened from Settings › About. */
    @Test
    fun userAgents() = shot(
        "settings-user-agents",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.settings_user_agent),
        then = { compose.onNodeWithText(text(R.string.settings_user_agent)).performScrollTo().performClick() },
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
