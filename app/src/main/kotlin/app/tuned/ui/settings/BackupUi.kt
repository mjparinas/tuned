package app.tuned.ui.settings

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.BackupCrypto
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

class BackupViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container

    data class State(
        val busy: Boolean = false,
        /** A one-off message for a toast, cleared once shown. */
        val message: String? = null,
        /** Restore: the passphrase didn't open the file; the dialog stays open to try again. */
        val wrongPassphrase: Boolean = false,
        /** Set once a restore brought a login back, so the screen can move on. */
        val restoredLogin: Boolean = false,
    )

    val state = MutableStateFlow(State())

    fun export(
        uri: Uri,
        passphrase: String,
    ) = launchJob {
        val bytes = container.backup.export(passphrase.toCharArray())
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException("Couldn't write the file")
        }
        state.update { it.copy(busy = false, message = getApplication<Application>().getString(R.string.backup_saved)) }
    }

    fun import(
        uri: Uri,
        passphrase: String,
    ) = launchJob {
        val bytes =
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Couldn't read the file")
            }
        val restored = container.backup.import(bytes, passphrase.toCharArray())
        if (restored.signedIn) {
            // Same as signing in: download the playlist and guide now, and keep them fresh.
            container.settings.update { it.copy(lastSyncError = "") }
            container.scheduler.refreshNow(first = true)
            val s = container.settings.settings.first()
            container.scheduler.schedule(s.playlistRefreshHours, s.guideRefreshHours, s.unmeteredOnly)
        }
        state.update {
            it.copy(
                busy = false,
                message = getApplication<Application>().getString(R.string.backup_restored),
                restoredLogin = restored.signedIn,
            )
        }
    }

    fun shown() = state.update { it.copy(message = null) }

    fun clearWrongPassphrase() = state.update { it.copy(wrongPassphrase = false) }

    private fun launchJob(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { State(busy = true) }
        viewModelScope.launchSafely("Backup") {
            try {
                block()
            } catch (_: BackupCrypto.WrongPassphrase) {
                state.update { State(wrongPassphrase = true) }
            } catch (_: BackupCrypto.NotABackup) {
                state.update { State(message = getApplication<Application>().getString(R.string.backup_not_a_backup)) }
            } catch (e: IOException) {
                AppLog.w("Backup", "Couldn't read or write the backup file", e)
                state.update { State(message = getApplication<Application>().getString(R.string.backup_file_error)) }
            } catch (e: SecurityException) {
                // The file picker's permission was taken back before the file was opened.
                AppLog.w("Backup", "No longer allowed to open the backup file", e)
                state.update { State(message = getApplication<Application>().getString(R.string.backup_file_error)) }
            } finally {
                if (state.value.busy) state.update { State(message = getApplication<Application>().getString(R.string.backup_file_error)) }
            }
        }
    }
}

private enum class Step { None, NewPassphrase, Passphrase }

/**
 * "Back up" and "Restore" rows for Settings. A backup asks for a new passphrase, then where to save; a restore asks
 * for the file, then its passphrase.
 */
@Composable
fun BackupRows(vm: BackupViewModel = viewModel()) {
    BackupFlow(vm, showBackup = true, onRestoredLogin = {}) { backup, restore ->
        SettingRow(stringResource(R.string.backup_create), stringResource(R.string.backup_create_summary), onClick = backup)
        SettingRow(stringResource(R.string.backup_restore), stringResource(R.string.backup_restore_summary), onClick = restore)
    }
}

/** "Restore from a backup" on the sign-in screen, for a new phone. */
@Composable
fun RestoreButton(
    onRestoredLogin: () -> Unit,
    vm: BackupViewModel = viewModel(),
) {
    BackupFlow(vm, showBackup = false, onRestoredLogin = onRestoredLogin) { _, restore ->
        TextButton(onClick = restore) { Text(stringResource(R.string.backup_restore_login)) }
    }
}

@Composable
private fun BackupFlow(
    vm: BackupViewModel,
    showBackup: Boolean,
    onRestoredLogin: () -> Unit,
    content: @Composable (backup: () -> Unit, restore: () -> Unit) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var step by remember { mutableStateOf(Step.None) }
    var newPassphrase by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) vm.export(uri, newPassphrase)
            newPassphrase = ""
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            restoreUri = uri
            if (uri != null) step = Step.Passphrase
        }
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.shown()
        }
    }
    LaunchedEffect(state.restoredLogin) { if (state.restoredLogin) onRestoredLogin() }
    LaunchedEffect(state.wrongPassphrase) { if (state.wrongPassphrase) step = Step.Passphrase }

    content({ if (showBackup) step = Step.NewPassphrase }, { open.launch(arrayOf("*/*")) })

    when (step) {
        Step.NewPassphrase -> {
            PassphraseDialog(create = true, wrong = false, onDismiss = { step = Step.None }) { passphrase ->
                newPassphrase = passphrase
                step = Step.None
                save.launch("tuned-backup-${LocalDate.now()}.tuned")
            }
        }

        Step.Passphrase -> {
            PassphraseDialog(create = false, wrong = state.wrongPassphrase, onDismiss = {
                step = Step.None
                vm.clearWrongPassphrase()
            }) { passphrase ->
                step = Step.None
                vm.clearWrongPassphrase()
                restoreUri?.let { vm.import(it, passphrase) }
            }
        }

        Step.None -> {
            Unit
        }
    }
}

@Composable
internal fun PassphraseDialog(
    create: Boolean,
    wrong: Boolean,
    onDismiss: () -> Unit,
    help: String? = null,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val long = passphrase.length >= BackupCrypto.MIN_PASSPHRASE
    val ok = if (create) long && passphrase == again else passphrase.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (create) R.string.backup_passphrase_new else R.string.backup_passphrase)) },
        text = {
            Column {
                Text(
                    help ?: stringResource(if (create) R.string.backup_passphrase_new_help else R.string.backup_passphrase_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                PassphraseField(passphrase, stringResource(R.string.backup_passphrase), isError = wrong) { passphrase = it }
                if (wrong) Text(stringResource(R.string.backup_wrong_passphrase), color = MaterialTheme.colorScheme.error)
                if (create) {
                    Spacer(Modifier.height(8.dp))
                    PassphraseField(
                        again,
                        stringResource(R.string.backup_passphrase_again),
                        isError =
                            again.isNotEmpty() && again != passphrase,
                    ) {
                        again = it
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = ok,
            ) { Text(stringResource(R.string.backup_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PassphraseField(
    value: String,
    label: String,
    isError: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
