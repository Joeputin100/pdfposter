package com.posterpdf.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.posterpdf.R
import java.util.Locale

/**
 * RC65: Compose-friendly wrapper around Android's SpeechRecognizer.
 * SpeechRecognizer is callback-based + not lifecycle-aware — wrapping
 * it in a class that the rememberVoiceInputController() helper owns +
 * destroying it in DisposableEffect.onDispose keeps it from leaking
 * when the Q&A sheet dismisses.
 *
 * Caller flow:
 *   val voice = rememberVoiceInputController()
 *   IconButton(onClick = {
 *     if (voice.isListening) voice.stop()
 *     else voice.start(onFinalTranscript = { promptToSend -> ... })
 *   }) { ... }
 *   if (voice.isListening) showTranscript(voice.transcript)
 */
class VoiceInputController(private val context: Context) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null

    var isListening by mutableStateOf(false)
        private set
    var transcript by mutableStateOf("")
        private set
    var error: String? by mutableStateOf(null)
        private set

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    fun start(onFinalTranscript: (String) -> Unit) {
        if (recognizer == null) {
            error = context.getString(R.string.voice_unavailable)
            return
        }
        if (!hasPermission()) {
            error = context.getString(R.string.voice_mic_permission)
            return
        }
        transcript = ""
        error = null
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(errorCode: Int) {
                isListening = false
                error = context.getString(R.string.voice_error_code, errorCode)
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull().orEmpty()
                transcript = best
                if (best.isNotBlank()) onFinalTranscript(best)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                texts?.firstOrNull()?.let { transcript = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        isListening = false
    }

    fun dispose() {
        recognizer?.destroy()
    }
}

@Composable
fun rememberVoiceInputController(): VoiceInputController {
    val context = LocalContext.current
    val controller = remember { VoiceInputController(context) }
    DisposableEffect(Unit) {
        onDispose { controller.dispose() }
    }
    return controller
}
