package app.tuned.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.core.PassphraseCrypto

/** Choosing a new passphrase, typed twice, at least [PassphraseCrypto.MIN_PASSPHRASE] characters (sync between devices). */
@Composable
internal fun PassphraseDialog(
    help: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = passphrase.length >= PassphraseCrypto.MIN_PASSPHRASE && passphrase == again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passphrase_new)) },
        text = {
            Column {
                Text(help, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                PassphraseField(passphrase, stringResource(R.string.passphrase), isError = false) { passphrase = it }
                Spacer(Modifier.height(8.dp))
                PassphraseField(again, stringResource(R.string.passphrase_again), isError = again.isNotEmpty() && again != passphrase) {
                    again = it
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = ok) { Text(stringResource(R.string.passphrase_continue)) }
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
