package com.tunedplayer.app.ui.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tunedplayer.app.R
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt

/** Whether this device has a speech recogniser for [VoiceSearchButton]; it is checked once per screen. */
@Composable
fun rememberVoiceSearchAvailable(): Boolean {
    val context = LocalContext.current
    val tv = isTelevision()
    return remember(context, tv) { voiceSearchAvailable(context, tv) }
}

/** Phone uses the recogniser activity; TV needs the inline [SpeechRecognizer] service. */
internal fun voiceSearchAvailable(
    context: Context,
    television: Boolean,
): Boolean = if (television) {
    SpeechRecognizer.isRecognitionAvailable(context)
} else {
    voiceIntent().resolveActivity(context.packageManager) != null
}

private fun voiceIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/**
 * A mic button that fills a search with what was said, through the phone's own speech recogniser (Google's on most
 * phones). It hides itself on devices without one.
 */
@Composable
fun VoiceSearchButton(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isTelevision()) {
        TvVoiceSearchButton(onResult, modifier)
        return
    }
    val prompt = stringResource(R.string.voice_prompt)
    val intent = remember { voiceIntent() }
    if (!rememberVoiceSearchAvailable()) return
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
    IconButton(
        onClick = {
            // A recogniser that went away after the check (an update, a disabled Google app) must not crash the app.
            attempt(
                "VoiceSearch",
                "Couldn't start voice search",
            ) { launcher.launch(intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)) }
        },
        modifier = modifier,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.voice_search))
    }
}

/** Records in Tuned's existing search row, avoiding the TV recogniser activity's duplicate mic and input box. */
@Composable
private fun TvVoiceSearchButton(
    onResult: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    if (!rememberVoiceSearchAvailable()) return
    var listening by remember { mutableStateOf(false) }
    val recogniser =
        remember(context) {
            attempt("VoiceSearch", "Couldn't create speech recogniser") { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        }
            ?: return
    val intent = remember { voiceIntent() }
    val listener =
        remember(onResult) {
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    results
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                        )?.firstOrNull()
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(onResult)
                }

                override fun onError(error: Int) {
                    listening = false
                    if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                        AppLog.w("VoiceSearch", "Speech recognition stopped with error $error")
                    }
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            }
        }
    DisposableEffect(recogniser, listener) {
        recogniser.setRecognitionListener(listener)
        onDispose {
            recogniser.cancel()
            recogniser.destroy()
        }
    }
    val start = {
        attempt("VoiceSearch", "Couldn't start voice search") {
            listening = true
            recogniser.startListening(intent)
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else listening = false
        }
    IconButton(
        onClick = {
            if (listening) {
                recogniser.stopListening()
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                start()
            } else {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        modifier = modifier,
    ) {
        if (listening) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        } else {
            Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.voice_search))
        }
    }
}
