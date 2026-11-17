package app.tuned.ui

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.tuned.container
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps Tuned current. Copies installed from Play update through Play; every other copy (GitHub Releases, for
 * now) through [app.tuned.updates.GitHubUpdates].
 *
 * GitHub: checked each time Tuned comes to the front (at most hourly, see [app.tuned.core.ReleaseTag.checkDue]) and
 * after background refreshes, so a copy left running still hears about a new release.
 *
 * Play: keeps Tuned current through Google Play (Melvin, 2026-11-17: "auto android updates whenever a new version has
 * been uploaded"). On every start it asks Play; a newer build installs straight away (immediate update), or, where
 * Play only allows a flexible one, downloads in the background and restarts into it when ready. Builds that
 * didn't come from Play (debug, sideloaded) get "no update" from Play and carry on.
 */
class AppUpdates(
    private val activity: ComponentActivity,
) {
    private val manager = AppUpdateManagerFactory.create(activity)
    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    private val listener =
        InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) manager.completeUpdate()
        }

    /** Who installed this copy: Play's updates only work for copies Play installed. */
    private val fromPlay: Boolean = activity.container.updates.fromPlay()
    private var askedPlay = false

    fun check() {
        if (!fromPlay) {
            activity.lifecycleScope.launch {
                val container = activity.container
                if (container.settings.settings
                        .first()
                        .checkUpdates
                ) {
                    container.updates.check()
                }
            }
            return
        }
        // Play is asked once per screen; it keeps its own update going from there.
        if (askedPlay) return
        askedPlay = true
        manager.registerListener(listener)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@addOnSuccessListener
            val type =
                when {
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                    else -> return@addOnSuccessListener
                }
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        }
    }

    /** An immediate update interrupted (app left mid-install) must be resumed, as Play's docs require. */
    fun resume() {
        if (!fromPlay) return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            when {
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
                }

                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    manager.completeUpdate()
                }
            }
        }
    }

    fun stop() {
        manager.unregisterListener(listener)
    }
}
