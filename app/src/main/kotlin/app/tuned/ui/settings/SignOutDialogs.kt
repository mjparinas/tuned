package app.tuned.ui.settings

import android.text.format.Formatter
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.data.AppReset
import app.tuned.ui.common.TunedDialog

/** Step one of signing out: what it removes. */
@Composable
internal fun SignOutDialog(
    onSignOut: () -> Unit,
    onCancel: () -> Unit,
) {
    TunedDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_sign_out_title)) },
        text = { Text(stringResource(R.string.settings_sign_out_confirm)) },
        confirmButton = { TextButton(onClick = onSignOut) { Text(stringResource(R.string.settings_sign_out)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Step two, only when something is saved: "Also delete 12 downloads (8.4 GB)?" Delete is the default (Melvin,
 * 2026-11-19); backing out of it cancels the sign-out.
 */
@Composable
internal fun SignOutDownloadsDialog(
    saved: AppReset.Saved,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
    onCancel: () -> Unit,
) {
    val size = Formatter.formatShortFileSize(LocalContext.current, saved.bytes)
    TunedDialog(
        onDismissRequest = onCancel,
        title = { Text(pluralStringResource(R.plurals.settings_sign_out_downloads, saved.count, saved.count, size)) },
        text = { Text(stringResource(R.string.settings_sign_out_downloads_keep_note)) },
        confirmButton = { Button(onClick = onDelete) { Text(stringResource(R.string.settings_sign_out_delete)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.settings_sign_out_keep)) } },
    )
}

/** While the reset runs, usually well under a second. */
@Composable
internal fun SigningOutDialog() {
    TunedDialog(
        onDismissRequest = {},
        text = { Text(stringResource(R.string.settings_signing_out)) },
        confirmButton = {},
    )
}
