package com.caamano.ccwearos.presentation.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.caamano.ccwearos.presentation.Haptics
import kotlinx.coroutines.delay

/**
 * Speech input with a safe launch. Some Wear OS builds ship without a
 * RecognizerIntent handler; launching it unguarded threw
 * ActivityNotFoundException and crashed the app. [unavailable] flips true for
 * a few seconds so the caller can show a short inline message.
 */
class VoiceInput internal constructor(
    val unavailable: Boolean,
    val launch: (prompt: String) -> Unit,
)

@Composable
fun rememberVoiceInput(onText: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val currentOnText by rememberUpdatedState(onText)
    var unavailable by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) currentOnText(text)
        }
    }

    LaunchedEffect(unavailable) {
        if (unavailable) {
            delay(3_500)
            unavailable = false
        }
    }

    return VoiceInput(unavailable = unavailable) { prompt ->
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            unavailable = true
            Haptics.error(context)
        }
    }
}
