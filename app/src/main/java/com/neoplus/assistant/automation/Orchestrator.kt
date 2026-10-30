package com.neoplus.assistant.automation

import com.neoplus.assistant.ai.AIProvider
import com.neoplus.assistant.ai.Content
import com.neoplus.assistant.ai.Part
import com.neoplus.assistant.ai.ToolRegistry
import com.neoplus.assistant.devices.DeviceTools
import com.neoplus.assistant.phone.PhoneActionEngine

/** Gemini plans -> engines execute real tools -> real results go back to Gemini -> final honest reply. */
class Orchestrator(
    private val provider: AIProvider,
    private val phone: PhoneActionEngine,
    private val devices: DeviceTools,
) {
    private val history = mutableListOf<Content>()

    fun clear() = history.clear()

    suspend fun handle(userText: String, confirm: suspend (String) -> Boolean): String {
        history += Content("user", listOf(Part.Text(userText)))
        repeat(MAX_STEPS) {
            val resp = provider.generate(window(), ToolRegistry.all)
            history += resp.content
            val calls = resp.calls
            if (calls.isEmpty()) return resp.text.ifBlank { "Done." }
            val results = calls.map { c ->
                val r = if (c.name in DeviceTools.NAMES) devices.execute(c.name, c.args, confirm)
                        else phone.execute(c.name, c.args, confirm)
                Part.Result(c.name, r.toJson())
            }
            history += Content("user", results)
        }
        return "I stopped after $MAX_STEPS steps without finishing. Please rephrase or split the task."
    }

    /**
     * Last MAX_HISTORY entries, but never starting mid tool-call: Gemini rejects a request that begins with a
     * function response or a model turn, so cut forward to the first plain user message.
     */
    private fun window(): List<Content> {
        val tail = history.takeLast(MAX_HISTORY)
        val start = tail.indexOfFirst { it.role == "user" && it.parts.all { p -> p is Part.Text } }
        return if (start <= 0) tail else tail.drop(start)
    }

    private companion object { const val MAX_STEPS = 6; const val MAX_HISTORY = 40 }
}
