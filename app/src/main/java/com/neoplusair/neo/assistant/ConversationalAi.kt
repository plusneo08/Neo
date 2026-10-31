
package com.neoplusair.neo.assistant

/**
 * Pluggable conversational AI backend.
 *
 * The default [OfflineJarvisBrain] ships fully functional with no network.
 * To connect a cloud LLM, implement [ConversationalAi] with your HTTP client
 * (Retrofit/OkHttp + your API key) and inject it in NeoBrain. The interface
 * is the single integration point — no UI changes required.
 */
interface ConversationalAi {
    fun reply(input: String): String
}

/** Offline JARVIS-style responder — witty, composed, fully local. */
class OfflineJarvisBrain : ConversationalAi {
    override fun reply(input: String): String = when {
        input.contains("who are you") ->
            "I am NEO PLUS AIR — your personal AI operating interface, at your service."
        input.contains("how are you") ->
            "Running at peak efficiency, sir. All cores nominal."
        input.contains("thank") ->
            "Always a pleasure, sir."
        input.contains("status") || input.contains("report") ->
            "All primary systems are green. Gesture link, voice link and holographic engine ready."
        input.contains("love") ->
            "I'm flattered, sir, but my affection is strictly computational."
        else -> listOf(
            "Processing your request, sir. My offline cortex suggests rephrasing, or connecting a cloud AI backend.",
            "Intriguing. My local knowledge core has no answer — the cloud AI integration point is ready when you are.",
            "Noted, sir. Perhaps you would like system status, applications, or the holographic panel?"
        ).random()
    }
}
