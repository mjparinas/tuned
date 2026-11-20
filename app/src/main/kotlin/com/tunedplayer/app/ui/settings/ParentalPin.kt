package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.core.PinHash
import com.tunedplayer.app.core.PinThrottle
import com.tunedplayer.app.data.CredentialStore
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.TunedDialog
import java.security.MessageDigest

/** What the PIN dialog is for. */
enum class PinMode { Create, Unlock, Forgot }

enum class PinResult { Ok, Wrong, TooMany }

fun PinThrottle.Result.toPinResult(): PinResult = when (this) {
    PinThrottle.Result.OK -> PinResult.Ok
    PinThrottle.Result.WRONG -> PinResult.Wrong
    PinThrottle.Result.TOO_MANY -> PinResult.TooMany
}

/** What a PIN dialog does with what's typed: the parental PIN in Settings, or a profile's own PIN. */
interface PinActions {
    suspend fun setPin(pin: String)

    suspend fun unlock(pin: String): PinResult

    fun matchesLogin(secret: String): Boolean
}

/** "Forgot PIN": the account password (or, for a playlist link login, the link) proves it's the account holder. */
fun CredentialStore.matches(secret: String): Boolean {
    val login = load() ?: return false
    val expected = login.password.ifEmpty { login.playlistUrl }
    return expected.isNotEmpty() && MessageDigest.isEqual(secret.toByteArray(), expected.toByteArray())
}

/**
 * A PIN dialog. Create asks twice; Unlock asks once and offers "Forgot PIN", which takes the account password (or
 * playlist link) instead, then asks for a new PIN ([forgotResets]) or lets them straight in. [onDone] runs when it
 * closes either way; [onSuccess] only after a PIN was set or matched.
 */
@Composable
fun PinDialog(
    start: PinMode,
    vm: PinActions,
    onDone: () -> Unit,
    onSuccess: () -> Unit = {},
    unlockTitle: String? = null,
    forgotResets: Boolean = true,
) {
    var mode by remember { mutableStateOf(start) }
    var first by remember(mode) { mutableStateOf("") }
    var second by remember(mode) { mutableStateOf("") }
    var error by remember(mode) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    val submit: () -> Unit = {
        scope.launchSafely("ParentalPin") {
            when (mode) {
                PinMode.Create -> {
                    error =
                        when {
                            !PinHash.isValid(first) -> R.string.pin_invalid
                            first != second -> R.string.pin_mismatch
                            else -> null
                        }
                    if (error == null) {
                        vm.setPin(first)
                        onSuccess()
                        onDone()
                    }
                }

                PinMode.Unlock -> {
                    when (vm.unlock(first)) {
                        PinResult.Ok -> {
                            onSuccess()
                            onDone()
                        }

                        PinResult.Wrong -> {
                            error = R.string.pin_wrong
                        }

                        PinResult.TooMany -> {
                            error = R.string.pin_too_many
                        }
                    }
                }

                PinMode.Forgot -> {
                    when {
                        !vm.matchesLogin(first) -> {
                            error = R.string.pin_forgot_wrong
                        }

                        forgotResets -> {
                            mode = PinMode.Create
                        }

                        else -> {
                            onSuccess()
                            onDone()
                        }
                    }
                }
            }
        }
    }

    TunedDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                if (mode == PinMode.Unlock && unlockTitle != null) {
                    unlockTitle
                } else {
                    stringResource(
                        when (mode) {
                            PinMode.Create -> R.string.pin_create_title
                            PinMode.Unlock -> R.string.pin_unlock_title
                            PinMode.Forgot -> R.string.pin_forgot_title
                        },
                    )
                },
            )
        },
        text = {
            Column {
                if (mode == PinMode.Forgot) {
                    Text(
                        stringResource(if (forgotResets) R.string.pin_forgot_body else R.string.pin_forgot_body_enter),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                SecretField(
                    value = first,
                    onChange = { first = it },
                    label = stringResource(if (mode == PinMode.Forgot) R.string.pin_forgot_field else R.string.pin_field),
                    digits = mode != PinMode.Forgot,
                )
                if (mode == PinMode.Create) {
                    Spacer(Modifier.height(8.dp))
                    SecretField(value = second, onChange = { second = it }, label = stringResource(R.string.pin_confirm), digits = true)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (mode == PinMode.Unlock) {
                    TextButton(onClick = { mode = PinMode.Forgot }) { Text(stringResource(R.string.pin_forgot)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SecretField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    digits: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (digits) v.filter(Char::isDigit).take(MAX_PIN) else v) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Password),
    )
}

private const val MAX_PIN = 8
