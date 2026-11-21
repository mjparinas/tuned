package com.tunedplayer.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.tunedplayer.app.container
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.startActivitySafely

/**
 * Android's answer to an update install: opens its confirm window, and tells [GitHubUpdates] how it went, so the
 * banner and Settings say "waiting for you to confirm", go back to Install when it was cancelled, or show what failed
 * with Try again. Never throws: a broadcast receiver that crashes takes Tuned with it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        attempt(TAG, "installer answer") {
            var status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirm =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION") // the typed overload only exists from Android 13
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                val opened = confirm != null && context.startActivitySafely(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), TAG)
                if (!opened) status = PackageInstaller.STATUS_FAILURE
            }
            context.container.updates.onInstallerAnswer(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    private companion object {
        const val TAG = "Updates"
    }
}
