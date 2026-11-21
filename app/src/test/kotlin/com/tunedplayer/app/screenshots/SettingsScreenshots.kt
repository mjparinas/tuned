package com.tunedplayer.app.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.core.AccountSeats
import com.tunedplayer.app.data.AppReset
import com.tunedplayer.app.data.CaptionStyle
import com.tunedplayer.app.data.LighterMode
import com.tunedplayer.app.premium.MoreDevicesOffer
import com.tunedplayer.app.premium.Offer
import com.tunedplayer.app.ui.CaptionSettingsRoute
import com.tunedplayer.app.ui.DeveloperLogRoute
import com.tunedplayer.app.ui.RegionSettingsRoute
import com.tunedplayer.app.ui.SettingsPageRoute
import com.tunedplayer.app.ui.SettingsRoute
import com.tunedplayer.app.ui.onboarding.QrModules
import com.tunedplayer.app.ui.settings.AccountSignIn
import com.tunedplayer.app.ui.settings.AccountSignInDialog
import com.tunedplayer.app.ui.settings.LogReport
import com.tunedplayer.app.ui.settings.ReportRow
import com.tunedplayer.app.ui.settings.SettingsPage
import com.tunedplayer.app.ui.settings.SignOutDialog
import com.tunedplayer.app.ui.settings.SignOutDownloadsDialog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings, every one of its pages, and the screens they open. */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenshots : Screenshots() {
    @Test fun settings() = shot("settings", SettingsRoute, wait = text(R.string.settings_playback))

    /** A tablet shows the pages and the page picked side by side. */
    @Test
    @Config(qualifiers = TABLET)
    fun settingsTablet() = shot("settings-tablet", SettingsRoute, wait = text(R.string.settings_playback))

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

    /** On a TV, Troubleshooting has only Logs: there's no keyboard to say what happened. */
    @Test
    @Config(qualifiers = TV)
    fun aboutOnTv() = shot(
        "settings-about-tv",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.settings_user_agent),
        then = { compose.onNodeWithText(text(R.string.logs_title)).performScrollTo() },
    )

    /** Logs, with Send to Tuned first: the log and this device's details with nothing to type. */
    @Test fun logs() = shot("settings-logs", DeveloperLogRoute, wait = text(R.string.logs_report_summary))

    /** Logs on a TV, where the remote starts on Send to Tuned. */
    @Test
    @Config(qualifiers = TV)
    fun logsOnTv() = shot("settings-logs-tv", DeveloperLogRoute, wait = text(R.string.logs_report_summary))

    /** Send to Tuned while it sends, and after a failed send, with Try again. */
    @Test
    fun logsReportStates() = part("settings-logs-report-states", text(R.string.logs_report_failed)) {
        Column {
            ReportRow(LogReport.SENDING) {}
            ReportRow(LogReport.SENT) {}
            ReportRow(LogReport.FAILED) {}
        }
    }

    /** The usage stats switch, under Privacy in Settings › About. */
    @Test
    fun usageStats() = shot(
        "settings-usage-stats",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.settings_user_agent),
        then = { compose.onNodeWithText(text(R.string.settings_usage_stats)).performScrollTo() },
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

    /** Only Canada and the United States on: English shows which, and "EN" has no row of its own (it follows them). */
    @Test
    fun countriesUsCanada() = shot(
        "settings-countries-us-canada",
        RegionSettingsRoute,
        wait = text(R.string.settings_countries),
        settings = DARK.copy(hiddenRegions = SampleData.allButUsCanada, seenRegions = SampleData.regions),
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

    /** Signing in to the Tuned account: the code to enter on the website (docs/decisions/accounts.md). */
    @Test
    fun accountSignIn() = shot(
        "settings-account-sign-in",
        SettingsPageRoute(SettingsPage.PLAYLISTS.name),
        wait = text(R.string.tuned_account_waiting),
        overlay = { AccountSignInDialog(SIGN_IN_CODE, text(R.string.tuned_account_sign_in), onRetry = {}, onClose = {}) },
    )

    /** The same on a TV, with a QR code of the website's page for the code. */
    @Test
    @Config(qualifiers = TV)
    fun accountSignInTv() = shot(
        "settings-account-sign-in-tv",
        SettingsPageRoute(SettingsPage.PLAYLISTS.name),
        wait = text(R.string.tuned_account_waiting),
        overlay = { AccountSignInDialog(SIGN_IN_CODE, text(R.string.tuned_account_sign_in), onRetry = {}, onClose = {}) },
    )

    /** Settings › Guide and refresh › Notifications, which opens Android's page for the dot on the app icon. */
    @Test
    fun notifications() = shot(
        "settings-notifications",
        SettingsPageRoute(SettingsPage.GUIDE.name),
        wait = text(R.string.settings_reminder_lead),
        then = { compose.onNodeWithText(text(R.string.settings_notifications)).performScrollTo() },
    )

    /** Tuned Premium › More devices on a signed-in account with lifetime Premium: one-time seats from Play. */
    @Test
    fun premiumMoreDevices() = shot(
        "premium-more-devices",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.premium_more_title),
        overlay = {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp)) {
                    MoreDevicesOffer(Offer(AccountSeats.SEAT_ONCE, "US$4.99", 0, yearly = false), most = 7, failed = false, onBuy = {})
                    MoreDevicesOffer(Offer(AccountSeats.SEAT_YEARLY, "US$1.99", 0, yearly = true), most = 1, failed = false, onBuy = {})
                }
            }
        },
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

private const val SIGN_IN_PAGE = "https://www.tunedplayer.com/account?tv=K7QF29MD"
private val SIGN_IN_CODE = AccountSignIn.State.Code("K7QF-29MD", SIGN_IN_PAGE, QrModules.of(SIGN_IN_PAGE))

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
