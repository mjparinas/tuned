package com.tunedplayer.app.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.core.AccountSeats
import com.tunedplayer.app.core.ReleaseNotes
import com.tunedplayer.app.data.AppReset
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.CaptionStyle
import com.tunedplayer.app.data.LighterMode
import com.tunedplayer.app.premium.AccountOnlyOffer
import com.tunedplayer.app.premium.MoreDevicesOffer
import com.tunedplayer.app.premium.Offer
import com.tunedplayer.app.ui.CaptionSettingsRoute
import com.tunedplayer.app.ui.DeveloperLogRoute
import com.tunedplayer.app.ui.RegionSettingsRoute
import com.tunedplayer.app.ui.SettingsPageRoute
import com.tunedplayer.app.ui.SettingsRoute
import com.tunedplayer.app.ui.TvScreenCalibrateRoute
import com.tunedplayer.app.ui.onboarding.QrModules
import com.tunedplayer.app.ui.settings.AccountSignIn
import com.tunedplayer.app.ui.settings.AccountSignInDialog
import com.tunedplayer.app.ui.settings.LogReport
import com.tunedplayer.app.ui.settings.ReportDialog
import com.tunedplayer.app.ui.settings.ReportRow
import com.tunedplayer.app.ui.settings.SettingRow
import com.tunedplayer.app.ui.settings.SettingsPage
import com.tunedplayer.app.ui.settings.SignOutDialog
import com.tunedplayer.app.ui.settings.SignOutDownloadsDialog
import com.tunedplayer.app.ui.settings.WhatsNew
import com.tunedplayer.app.ui.settings.WhatsNewNotes
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

    /** Settings on a TV with the theme left at the system default, which is dark there, and Tuned's mark top left. */
    @Test
    @Config(qualifiers = TV)
    fun settingsOnTv() = shot("settings-tv", SettingsRoute, wait = text(R.string.settings_playback), settings = AppSettings())

    /** The same with Light picked in Appearance: the mark and name still read. */
    @Test
    @Config(qualifiers = TV)
    fun settingsOnTvLight() = shot("settings-tv-light", SettingsRoute, wait = text(R.string.settings_playback), settings = LIGHT)

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

    /** Warm next screens on Appearance (docs/decisions/speed.md › Speculative warm). */
    @Test
    fun warmNextScreens() = shot(
        "settings-warm-next",
        SettingsPageRoute(SettingsPage.APPEARANCE.name),
        wait = text(R.string.settings_warm_next),
        settings = DARK.copy(warmNextScreens = true),
        then = { compose.onNodeWithText(text(R.string.settings_warm_next)).performScrollTo() },
    )

    /** Calibrate screen on a TV: corner marks and the move / stretch sliders (Melvin, 2026-11-22). */
    @Test
    @Config(qualifiers = TV)
    fun tvCalibrate() = shot(
        "settings-tv-calibrate",
        TvScreenCalibrateRoute,
        wait = text(R.string.settings_tv_calibrate_hint),
    )

    /** Mini player on Back, beside Picture-in-picture in Settings › Player (Melvin, 2026-11-21). */
    @Test
    fun miniPlayer() = shot(
        "settings-mini-player",
        SettingsPageRoute(SettingsPage.PLAYBACK.name),
        wait = text(R.string.settings_resume),
        then = { compose.onNodeWithText(text(R.string.settings_mini_player)).performScrollTo() },
    )

    /** Report a problem, under Troubleshooting in Settings › About. */
    @Test
    fun reportProblem() = shot(
        "settings-report-problem",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.settings_user_agent),
        then = { compose.onNodeWithText(text(R.string.report_title)).performScrollTo() },
    )

    /** The Report a problem dialog, nearly the phone's width, with Send to Tuned and Cancel (no Share instead). */
    @Test
    fun reportDialog() = shot(
        "settings-report-dialog",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.report_dialog_text),
        overlay = { ReportDialog(busy = false, failed = false, onDismiss = {}, onSend = { _, _ -> }, startWhat = REPORT_WHAT) },
    )

    /** While it sends: a spinner and a line saying the log and the device's details are going. */
    @Test
    fun reportDialogSending() = shot(
        "settings-report-dialog-sending",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.logs_report_sending),
        overlay = { ReportDialog(busy = true, failed = false, onDismiss = {}, onSend = { _, _ -> }, startWhat = REPORT_WHAT) },
    )

    /** After a failed send: what was typed stays, with Try again. */
    @Test
    fun reportDialogFailed() = shot(
        "settings-report-dialog-failed",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.logs_report_failed),
        overlay = { ReportDialog(busy = false, failed = true, onDismiss = {}, onSend = { _, _ -> }, startWhat = REPORT_WHAT) },
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

    /** Record a speed trace, under Troubleshooting after Logs (Melvin, 2026-11-28). */
    @Test
    fun speedTrace() = shot(
        "settings-speed-trace",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.settings_user_agent),
        then = { compose.onNodeWithText(text(R.string.settings_speed_trace)).performScrollTo() },
    )

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

    /** Captions on a TV: the preview beside the rows, the remote starting on Show captions. */
    @Test
    @Config(qualifiers = TV)
    fun captionsTv() = shot(
        "settings-captions-tv",
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

    /** What's new on a phone: the bottom sheet from Settings › Tuned › Version. */
    @Test
    fun whatsNew() = shot(
        "settings-whats-new",
        SettingsRoute,
        wait = "filters chosen on each tab",
        overlay = { WhatsNew(WhatsNewNotes.Loaded(SAMPLE_CHANGES), onRetry = {}, onDismiss = {}) },
    )

    /** What's new on a TV: the whole screen, no drag handle, the first change focused and Done always in view. */
    @Test
    @Config(qualifiers = TV)
    fun whatsNewTv() = shot(
        "settings-whats-new-tv",
        SettingsRoute,
        wait = "filters chosen on each tab",
        overlay = { WhatsNew(WhatsNewNotes.Loaded(SAMPLE_CHANGES), onRetry = {}, onDismiss = {}) },
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

    /** Tuned Premium on Fire TV (docs/decisions/tv.md#fire-tv): no prices, only the Tuned account's sign-in. */
    @Test
    @Config(qualifiers = TV)
    fun premiumFireTv() = shot(
        "premium-fire-tv",
        SettingsPageRoute(SettingsPage.ABOUT.name),
        wait = text(R.string.premium_account_only),
        overlay = {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.premium_lead), style = MaterialTheme.typography.titleMedium)
                    AccountOnlyOffer(
                        checking = false,
                        note = R.string.premium_account_none,
                        account = {
                            SettingRow(
                                stringResource(R.string.tuned_account_sign_in),
                                stringResource(R.string.tuned_account_sign_in_summary),
                                onClick = {},
                            )
                        },
                        onCheck = {},
                    )
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

/** A release's notes, long enough that they scroll on a TV. */
private val SAMPLE_CHANGES =
    ReleaseNotes.parse(
        """
        * feat: keep the filters chosen on each tab, per profile (#268)
        * feat: show progress for installing updates and every refresh (#273)
        * feat: turn the phone into a remote while casting (#274)
        * perf: open Live TV faster from the cache (#270)
        * refactor: tidier More seats card (#280)
        * fix: Top rated lists films by their rating again (#279)
        * fix: the app no longer closes on Android 11 when searching (#276)
        * fix: the guide keeps its place after a refresh (#271)
        """.trimIndent(),
    )

private const val SIGN_IN_PAGE = "https://www.tunedplayer.com/account?tv=K7QF29MD"
private val SIGN_IN_CODE = AccountSignIn.State.Code("K7QF-29MD", SIGN_IN_PAGE, QrModules.of(SIGN_IN_PAGE))
private const val REPORT_WHAT = "The guide stopped loading after I switched playlists."

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
