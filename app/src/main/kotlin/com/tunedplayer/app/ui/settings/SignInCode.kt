package com.tunedplayer.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.diagnostics.attempt

/**
 * The short code a sign-in dialog shows (Trakt, the Tuned account), with a button that copies it, so it can be pasted on
 * the activation page when that's open on the same phone (Melvin, 2026-11-18). The icon turns into a tick once copied.
 */
@Composable
fun SignInCode(code: String) {
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            code,
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { copied = copyCode(context, code) }) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = stringResource(if (copied) R.string.sign_in_code_copied else R.string.sign_in_code_copy),
            )
        }
    }
}

private fun copyCode(
    context: Context,
    code: String,
): Boolean = attempt("SignInCode", "Couldn't copy the sign-in code") {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("No clipboard")
    clipboard.setPrimaryClip(ClipData.newPlainText("Sign-in code", code))
}.isSuccess
