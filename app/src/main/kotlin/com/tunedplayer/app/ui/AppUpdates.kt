package com.tunedplayer.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.container
import com.tunedplayer.app.data.ServerFlags
import com.tunedplayer.app.premium.Premium
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps Tuned current. Copies installed from Play update through Play; every other copy (GitHub Releases, for
 * now) through [com.tunedplayer.app.updates.GitHubUpdates].
 *
 * GitHub: checked each time Tuned comes to the front (at most hourly, see [com.tunedplayer.app.core.ReleaseTag.checkDue]) and
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
    // Made only when Play is asked, so a Fire TV (no Play) never touches Play's library.
    private val manager by lazy { AppUpdateManagerFactory.create(activity) }
    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    private val listener =
        InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) manager.completeUpdate()
        }

    /** Who installed this copy: Play's updates only work for copies Play installed. */
    private val fromPlay: Boolean = activity.container.updates.fromPlay()
    private var askedPlay = false

    /** The Amazon Appstore keeps the Fire TV build current itself, and Fire TV has no Play to ask. */
    private val byAppstore = BuildConfig.STORE == Premium.STORE_AMAZON

    fun check() {
        if (byAppstore) return
        if (!fromPlay) {
            // The app's scope, not this screen's: leaving the screen mid-download (Back, the player) must not cut
            // the check short. The check never throws.
            val container = activity.container
            container.appScope.launch {
                container.quietStart.await()
                // A version older than the admin's `min_version_code` looks for an update even with the setting off.
                val tooOld = ServerFlags.now.value.tooOld(BuildConfig.VERSION_CODE)
                if (tooOld ||
                    container.settings.settings
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
        if (byAppstore) return
        if (!fromPlay) {
            // Back from the installer's confirm window, or from allowing Tuned to install apps.
            activity.container.updates.onReturned(activity)
            return
        }
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
        if (askedPlay) manager.unregisterListener(listener)
    }
}
