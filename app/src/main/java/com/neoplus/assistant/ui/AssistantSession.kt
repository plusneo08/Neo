package com.neoplus.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neoplus.assistant.NeoApp
import com.neoplus.assistant.ai.AIException
import com.neoplus.assistant.voice.VoiceController
import com.neoplus.assistant.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class Msg(val fromUser: Boolean, val text: String)

/** Ties voice input, the orchestrator and spoken output into one conversation. Lives in the activity. */
class AssistantSession(private val app: NeoApp, val voice: VoiceController, private val scope: CoroutineScope) {
    val msgs = mutableStateListOf(Msg(false, "NEO PLUS online. Intelligence at your command."))
    var busy by mutableStateOf(false); private set
    var pending by mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null)

    private val prefs get() = app.prefs
    private var lastWasVoice = false

    val orbState: OrbState get() = when {
        busy -> OrbState.THINKING
        voice.state == VoiceState.LISTENING -> OrbState.LISTENING
        voice.state == VoiceState.SPEAKING -> OrbState.SPEAKING
        else -> OrbState.IDLE
    }

    init {
        voice.onFinal = { text -> lastWasVoice = true; send(text) }
        voice.onMessage = { msgs += Msg(false, it) }
        voice.onSpeechDone = { if (prefs.continuous && !busy && pending == null) voice.listen() }
    }

    fun onOrbTap() {
        when (voice.state) {
            VoiceState.SPEAKING -> voice.stopSpeaking()
            VoiceState.LISTENING -> voice.stopListening()
            VoiceState.IDLE -> if (!busy) voice.listen()
        }
    }

    fun sendTyped(text: String) { lastWasVoice = false; send(text) }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        msgs += Msg(true, text)
        busy = true
        scope.launch {
            val reply = try {
                app.orchestrator.handle(text) { prompt ->
                    val d = CompletableDeferred<Boolean>()
                    pending = prompt to d
                    if (prefs.speakReplies) voice.speak("$prompt Please confirm on screen.")
                    d.await().also { pending = null; voice.stopSpeaking() }
                }
            } catch (e: AIException) { "Error: ${e.message}" } catch (e: Exception) { "Unexpected error: ${e.message}" }
            msgs += Msg(false, reply)
            busy = false
            if (prefs.speakReplies && (lastWasVoice || prefs.continuous)) voice.speak(reply)
            else if (prefs.continuous) voice.listen()
        }
    }

    fun clear() { msgs.clear(); msgs += Msg(false, "Conversation cleared."); app.orchestrator.clear() }
}
