package com.neoplus.assistant.ai

import org.json.JSONObject

sealed class Part {
    data class Text(val text: String) : Part()
    data class Call(val name: String, val args: JSONObject) : Part()
    data class Result(val name: String, val response: JSONObject) : Part()
}

/** [raw] keeps the provider's original content object so signatures/metadata survive round-trips. */
data class Content(val role: String, val parts: List<Part>, val raw: JSONObject? = null)

data class AIResponse(val content: Content) {
    val text: String get() = content.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
    val calls: List<Part.Call> get() = content.parts.filterIsInstance<Part.Call>()
}

class AIException(message: String) : Exception(message)

/** Swap providers by implementing this; nothing else in the app knows about Gemini. */
interface AIProvider {
    suspend fun generate(history: List<Content>, tools: List<JSONObject>): AIResponse
}
