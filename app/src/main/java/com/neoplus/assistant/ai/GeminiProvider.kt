package com.neoplus.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Talks to the NEO PLUS backend (which holds the Gemini key). No Gemini key exists in this app. */
class GeminiProvider(
    private val baseUrl: () -> String,
    private val token: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(40, TimeUnit.SECONDS).build(),
) : AIProvider {

    override suspend fun generate(history: List<Content>, tools: List<JSONObject>): AIResponse =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("contents", JSONArray(history.map(::serialize)))
            if (tools.isNotEmpty()) {
                body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(tools))))
            }
            val req = Request.Builder()
                .url(baseUrl().trimEnd('/') + "/v1/chat")
                .header("Authorization", "Bearer ${token()}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val text = try {
                client.newCall(req).execute().use { r ->
                    val s = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw AIException(
                        when (r.code) {
                            401 -> "Backend rejected the device token (401)."
                            429 -> "Rate limit reached. Wait a minute."
                            else -> "Backend error ${r.code}."
                        }
                    )
                    s
                }
            } catch (e: AIException) { throw e }
            catch (e: Exception) { throw AIException("Can't reach the backend: ${e.message}") }
            parse(JSONObject(text))
        }

    private fun serialize(c: Content): JSONObject {
        c.raw?.let { return it }
        val parts = JSONArray()
        for (p in c.parts) parts.put(
            when (p) {
                is Part.Text -> JSONObject().put("text", p.text)
                is Part.Call -> JSONObject().put("functionCall", JSONObject().put("name", p.name).put("args", p.args))
                is Part.Result -> JSONObject().put("functionResponse", JSONObject().put("name", p.name).put("response", p.response))
            }
        )
        return JSONObject().put("role", c.role).put("parts", parts)
    }

    private fun parse(json: JSONObject): AIResponse {
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
        val content = cand?.optJSONObject("content")
        val rawParts = content?.optJSONArray("parts")
        if (rawParts == null || rawParts.length() == 0) {
            return AIResponse(Content("model", listOf(Part.Text("I couldn't generate a response to that."))))
        }
        val parts = (0 until rawParts.length()).mapNotNull { i ->
            val p = rawParts.getJSONObject(i)
            when {
                p.has("functionCall") -> p.getJSONObject("functionCall").let {
                    Part.Call(it.getString("name"), it.optJSONObject("args") ?: JSONObject())
                }
                p.has("text") && !p.optBoolean("thought", false) -> Part.Text(p.getString("text"))
                else -> null
            }
        }
        return AIResponse(Content("model", parts, raw = content))
    }
}
