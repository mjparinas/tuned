package app.tuned.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import app.tuned.R
import app.tuned.diagnostics.startActivitySafely

/** Android's answer to an update install: show its confirm screen, or say why it failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION") // the typed overload only exists from Android 13
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                confirm?.let { context.startActivitySafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Updates") }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Unit
            }

            else -> {
                Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
