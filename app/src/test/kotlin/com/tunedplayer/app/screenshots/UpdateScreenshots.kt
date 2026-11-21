package com.tunedplayer.app.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.ui.SyncText
import com.tunedplayer.app.ui.common.UpdateBannerContent
import com.tunedplayer.app.ui.common.UpdateInstallButton
import com.tunedplayer.app.ui.common.UpdateText
import com.tunedplayer.app.ui.settings.RowSpinner
import com.tunedplayer.app.ui.settings.SettingRow
import com.tunedplayer.app.updates.GitHubUpdates
import com.tunedplayer.app.updates.GitHubUpdates.InstallProblem
import com.tunedplayer.app.updates.GitHubUpdates.InstallStep
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What a tap on Install, Back up now, Refresh now or Clear watch history shows while it works and when it ends
 * (Melvin, 2026-11-21: "there needs to be some user feedback so they know that the app didn't just crash or freeze").
 * Every step of an update install is drawn one above the other, as the banner on Home shows it, and the Settings rows
 * as they read part way through.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateScreenshots : Screenshots() {
    @Test fun installSteps() = part("update-install-steps", "Waiting for you to confirm") { InstallSteps() }

    @Test
    @Config(qualifiers = TV)
    fun installStepsTv() = part("update-install-steps-tv", "Waiting for you to confirm") { InstallSteps() }

    @Test fun settingsRows() = part("settings-busy-rows", "Backing up") { BusyRows() }
}

private val READY = GitHubUpdates.State.Ready("2.5.0", File("tuned.apk"), size = 41_900_000)

private val STATES =
    listOf(
        READY,
        GitHubUpdates.State.Installing(READY, InstallStep.DOWNLOAD, 12_600_000, 41_900_000),
        GitHubUpdates.State.Installing(READY, InstallStep.CHECK, 30_000_000, 41_900_000),
        GitHubUpdates.State.Installing(READY, InstallStep.PREPARE, 18_000_000, 41_900_000),
        GitHubUpdates.State.Installing(READY, InstallStep.OPEN_INSTALLER),
        GitHubUpdates.State.Installing(READY, InstallStep.CONFIRM),
        GitHubUpdates.State.InstallFailed(READY, InstallProblem.DOWNLOAD),
    )

@Composable
private fun InstallSteps() {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        STATES.forEach { UpdateBannerContent(it, onInstall = {}, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun BusyRows() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        val downloading = GitHubUpdates.State.Installing(READY, InstallStep.PREPARE, 18_000_000, 41_900_000)
        SettingRow(stringResource(R.string.settings_check_now), UpdateText.detail(context, downloading)) {
            UpdateInstallButton(downloading) {}
        }
        SettingRow(
            stringResource(R.string.settings_check_now),
            stringResource(R.string.update_problem_space),
            trailing = { UpdateInstallButton(GitHubUpdates.State.InstallFailed(READY, InstallProblem.SPACE)) {} },
        )
        SettingRow(stringResource(R.string.tuned_account_backup_now), stringResource(R.string.tuned_account_backing_up)) {
            RowSpinner()
        }
        SettingRow(stringResource(R.string.tuned_account_backup_now), stringResource(R.string.tuned_account_backed_up))
        SettingRow(stringResource(R.string.tuned_account_backup_now), stringResource(R.string.tuned_account_backup_failed))
        SettingRow(
            stringResource(R.string.settings_refresh_now),
            SyncText.describe(context, SyncProgress(SyncProgress.Step.DownloadPlaylist, 6_200_000, 18_500_000)),
        ) { RowSpinner() }
        SettingRow(stringResource(R.string.settings_clear_history), stringResource(R.string.settings_history_cleared))
    }
}
