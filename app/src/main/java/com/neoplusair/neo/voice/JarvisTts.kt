
package com.neoplusair.neo.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** JARVIS-style text-to-speech: low-pitched, composed, slightly brisk. */
class JarvisTts(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(0.88f)
            tts?.setSpeechRate(1.04f)
            ready = true
        }
    }

    fun speak(text: String) {
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neo_reply")
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
