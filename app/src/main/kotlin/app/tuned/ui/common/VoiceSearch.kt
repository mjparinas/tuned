package app.tuned.ui.common

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.tuned.R

/**
 * A mic button that fills a search with what was said, through the phone's own speech recogniser (Google's on most
 * phones). It hides itself on devices without one.
 */
@Composable
fun VoiceSearchButton(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val intent =
        remember {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    if (!available) return
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val text =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
            if (!text.isNullOrEmpty()) onResult(text)
        }
    IconButton(onClick = { launcher.launch(intent.putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_prompt))) }) {
        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.voice_search))
    }
}
