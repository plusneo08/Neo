package com.neoplus.assistant.security

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Append-only JSON-lines log of every action attempt. SMS bodies are never stored, only their length. */
class AuditLogger(ctx: Context) {
    private val file = File(ctx.filesDir, "audit.jsonl")

    @Synchronized
    fun log(tool: String, args: JSONObject, status: String, message: String) {
        val safe = JSONObject(args.toString())
        if (safe.has("message")) { safe.put("message_length", safe.optString("message").length); safe.remove("message") }
        file.appendText(
            JSONObject().put("ts", System.currentTimeMillis()).put("tool", tool)
                .put("args", safe).put("status", status).put("message", message).toString() + "\n"
        )
    }

    fun recent(n: Int = 50): List<String> = if (file.exists()) file.readLines().takeLast(n) else emptyList()
}
