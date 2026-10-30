package com.neoplus.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ApiKeyInfo(val id: String, val label: String, val masked: String, val active: Boolean)
data class KeyOutcome(val keys: List<ApiKeyInfo>?, val message: String)

/** Manages the Gemini keys stored on YOUR backend. The app never keeps a Gemini key; it only sends one to the backend over HTTPS. */
class KeyAdminApi(
    private val baseUrl: () -> String,
    private val adminToken: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) {
    suspend fun list() = call("GET", "/admin/keys")
    suspend fun add(label: String, key: String) =
        call("POST", "/admin/keys", JSONObject().put("label", label).put("key", key))
    suspend fun activate(id: String) = call("POST", "/admin/keys/$id/activate", JSONObject())
    suspend fun delete(id: String) = call("DELETE", "/admin/keys/$id")

    private suspend fun call(method: String, path: String, body: JSONObject? = null): KeyOutcome = withContext(Dispatchers.IO) {
        if (adminToken().isBlank()) return@withContext KeyOutcome(null, "Enter the admin token first.")
        val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(baseUrl().trimEnd('/') + path)
            .header("Authorization", "Bearer ${adminToken()}")
            .method(method, rb ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody())
            .build()
        try {
            client.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                val o = runCatching { JSONObject(s) }.getOrNull()
                if (!r.isSuccessful) return@use KeyOutcome(null, when (r.code) {
                    401 -> "Admin token rejected."
                    403 -> "Key management is disabled on the backend (ADMIN_TOKEN not set)."
                    else -> o?.optString("error")?.takeIf { it.isNotBlank() } ?: "Backend error ${r.code}."
                })
                val arr = o?.optJSONArray("keys")
                val keys = arr?.let { a -> (0 until a.length()).map {
                    val k = a.getJSONObject(it)
                    ApiKeyInfo(k.getString("id"), k.getString("label"), k.getString("masked"), k.getBoolean("active"))
                } }
                val msg = when {
                    o?.has("verified") == true && o.isNull("verified") -> "Key saved. Couldn't check it with Google right now."
                    o?.has("verified") == true && !o.getBoolean("verified") -> "Key saved, but Google rejected it. Check that it is correct."
                    o?.has("verified") == true -> "Key saved and accepted by Google."
                    else -> "Done."
                }
                KeyOutcome(keys, msg)
            }
        } catch (e: Exception) { KeyOutcome(null, "Can't reach the backend: ${e.message}") }
    }
}
