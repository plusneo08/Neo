package com.neoplus.assistant.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.neoplus.assistant.phone.ActionResult
import com.neoplus.assistant.phone.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Live WebSocket sessions to paired devices over pinned TLS. A reply only counts as success if the device says so. */
class RemoteCommandManager(private val registry: DeviceRegistry) {
    private class Conn(val ws: WebSocket) {
        val pending = ConcurrentHashMap<String, CompletableDeferred<ActionResult>>()
    }

    private val conns = ConcurrentHashMap<String, Conn>()

    /** Bumps whenever any connection opens or closes, so Compose screens can re-read [isConnected]. */
    var version by mutableIntStateOf(0); private set

    @Suppress("UNUSED_EXPRESSION")
    fun isConnected(id: String): Boolean { version; return conns.containsKey(id) }

    suspend fun send(d: PairedDevice, cmd: String, args: JSONObject = JSONObject(), timeoutMs: Long = 10_000): ActionResult {
        val conn = try { connect(d) } catch (e: Exception) {
            return ActionResult(Status.FAILED, "Can't reach ${d.name}: ${e.message}. Is the companion running on the same network?")
        }
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<ActionResult>()
        conn.pending[id] = wait
        val ok = conn.ws.send(JSONObject().put("id", id).put("cmd", cmd).put("args", args).toString())
        if (!ok) { conn.pending.remove(id); drop(d.id); return ActionResult(Status.FAILED, "Connection to ${d.name} dropped.") }
        val r = withTimeoutOrNull(timeoutMs) { wait.await() }
        conn.pending.remove(id)
        return r ?: ActionResult(Status.FAILED, "${d.name} didn't answer within ${timeoutMs / 1000}s; treat the command as NOT done.")
    }

    private suspend fun connect(d: PairedDevice): Conn {
        conns[d.id]?.let { return it }
        val opened = CompletableDeferred<Unit>()
        val client = PinnedTls.client(d.certSha256)
        val req = Request.Builder().url("wss://${d.host}:${d.port}/ws").header("Authorization", "Bearer ${d.token}").build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(d.id, text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { drop(d.id) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val e = if (response?.code == 401) IOException("the PC rejected this phone (access revoked, pair again)")
                        else IOException(t.message ?: "connection failed")
                opened.completeExceptionally(e); drop(d.id)
            }
        })
        try { withTimeout(7000) { opened.await() } } catch (e: Exception) { ws.cancel(); throw e }
        val c = Conn(ws)
        conns[d.id] = c
        registry.touch(d.id)
        version++
        return c
    }

    private fun handle(id: String, text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        val conn = conns[id] ?: return
        val status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED)
        conn.pending.remove(o.optString("id"))
            ?.complete(ActionResult(status, o.optString("message"), o.optJSONObject("data") ?: JSONObject()))
    }

    private fun drop(id: String) {
        conns.remove(id)?.let { c -> c.pending.values.forEach { it.complete(ActionResult(Status.FAILED, "Connection lost.")) } }
        version++
    }

    fun disconnect(id: String) { conns.remove(id)?.ws?.close(1000, "bye"); version++ }
    fun disconnectAll() { conns.keys.toList().forEach(::disconnect) }

    /** Asks the PC to forget this phone, then drops the link. Best effort: if the PC is offline, revoke on the PC too. */
    suspend fun revoke(d: PairedDevice): ActionResult {
        val r = send(d, "revoke", timeoutMs = 4000)
        disconnect(d.id)
        return r
    }
}
