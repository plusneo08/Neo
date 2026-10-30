package com.neoplus.assistant.voice

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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.neoplus.assistant.security.Prefs
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, SPEAKING }

/** Speech-to-text + text-to-speech on Android's own engines. All methods must be called on the main thread. */
class VoiceController(private val ctx: Context, private val prefs: Prefs) {
    var state by mutableStateOf(VoiceState.IDLE); private set
    /** 0..1 microphone level, for the orb. */
    var level by mutableFloatStateOf(0f); private set
    var partial by mutableStateOf(""); private set

    var onFinal: (String) -> Unit = {}
    var onMessage: (String) -> Unit = {}
    /** Called only when a reply finished speaking on its own, not when the user interrupted it. */
    var onSpeechDone: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) { ttsReady = true; applyLocale() }
            else onMessage("Text-to-speech isn't available on this phone.")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { state = VoiceState.IDLE; onSpeechDone() } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { state = VoiceState.IDLE } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { state = VoiceState.IDLE } }
        })
    }

    private fun locale(): Locale = if (prefs.language.isBlank()) Locale.getDefault() else Locale.forLanguageTag(prefs.language)

    fun applyLocale() {
        val r = tts?.setLanguage(locale())
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            onMessage("This phone has no text-to-speech voice for ${locale().displayName}.")
    }

    fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onMessage("Speech recognition isn't available on this phone."); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onMessage("Microphone permission is needed."); return
        }
        stopSpeaking()
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finishListening()
                if (text.isNotBlank()) onFinal(text)
            }
            override fun onError(error: Int) {
                finishListening()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> {}
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onMessage("Microphone permission is needed.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        onMessage("Speech recognition needs a network connection for this language.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onMessage("The speech recognizer is busy. Try again.")
                    else -> onMessage("Speech recognition error ($error).")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (prefs.language.isNotBlank()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
        partial = ""
        state = VoiceState.LISTENING
        r.startListening(i)
    }

    fun stopListening() { rec?.stopListening() }

    private fun finishListening() { state = VoiceState.IDLE; level = 0f; partial = ""; destroyRecognizer() }
    private fun destroyRecognizer() { rec?.destroy(); rec = null }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) { onSpeechDone(); return }
        applyLocale()
        val clipped = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        state = VoiceState.SPEAKING
        tts?.speak(clipped, TextToSpeech.QUEUE_FLUSH, null, "neo-reply")
    }

    /** Interrupts a spoken reply (tap the orb). */
    fun stopSpeaking() { if (state == VoiceState.SPEAKING) { tts?.stop(); state = VoiceState.IDLE } }

    fun release() { destroyRecognizer(); tts?.stop(); tts?.shutdown(); tts = null }
}
