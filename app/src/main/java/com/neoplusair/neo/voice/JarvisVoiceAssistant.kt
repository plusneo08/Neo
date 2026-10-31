
package com.neoplusair.neo.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Always-on voice link: continuous SpeechRecognizer loop that forwards
 * final utterances to [Listener.onCommand] and auto-restarts after errors.
 */
class JarvisVoiceAssistant(
    private val context: Context,
    private val listener: Listener
) {
    enum class State { IDLE, LISTENING, PROCESSING }

    interface Listener {
        fun onCommand(text: String)
        fun onState(state: State)
    }

    var isListening = false; private set

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { if (isListening) startListening() }

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onState(State.IDLE)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            isListening = false
            listener.onState(State.IDLE)
            return
        }
        isListening = true
        if (recognizer == null) recognizer = createRecognizer()
        handler.removeCallbacks(restartRunnable)
        listener.onState(State.LISTENING)
        startListening()
    }

    fun stop() {
        isListening = false
        handler.removeCallbacks(restartRunnable)
        recognizer?.stopListening()
        listener.onState(State.IDLE)
    }

    fun destroy() {
        stop()
        recognizer?.destroy(); recognizer = null
    }

    private fun createRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listener.onState(State.LISTENING)
                }
                override fun onBeginningOfSpeech() { listener.onState(State.LISTENING) }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listener.onState(State.PROCESSING) }
                override fun onError(error: Int) {
                    if (isListening) {
                        listener.onState(State.LISTENING)
                        handler.removeCallbacks(restartRunnable)
                        handler.postDelayed(restartRunnable, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900 else 600)
                    } else listener.onState(State.IDLE)
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text != null) {
                        listener.onState(State.PROCESSING)
                        listener.onCommand(text)
                    }
                    if (isListening) handler.postDelayed(restartRunnable, 300)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.EXTRA_LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200)
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: IllegalStateException) {
            if (isListening) handler.postDelayed(restartRunnable, 800)
        } catch (_: SecurityException) {
            isListening = false
            listener.onState(State.IDLE)
        }
    }
}
