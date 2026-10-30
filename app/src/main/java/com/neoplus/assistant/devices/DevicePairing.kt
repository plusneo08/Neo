package com.neoplus.assistant.devices

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class PairException(message: String) : Exception(message)

data class PairInfo(val host: String, val port: Int, val code: String, val fingerprint: String)

object DevicePairing {
    /** Parses `neoplus://pair?h=<ip>&p=<port>&c=<code>&f=<sha256 hex>` as shown by the PC companion. */
    fun parse(raw: String): PairInfo {
        val u = Uri.parse(raw.trim())
        if (u.scheme != "neoplus" || u.host != "pair") throw PairException("That is not a NEO PLUS pairing code.")
        val host = u.getQueryParameter("h").orEmpty()
        val port = u.getQueryParameter("p")?.toIntOrNull() ?: 8765
        val code = u.getQueryParameter("c").orEmpty()
        val fp = u.getQueryParameter("f").orEmpty().lowercase()
        if (host.isBlank() || code.isBlank() || !Regex("[0-9a-f]{64}").matches(fp))
            throw PairException("Pairing code is incomplete.")
        return PairInfo(host, port, code, fp)
    }

    fun fromManual(host: String, port: String, code: String, fp: String) =
        parse("neoplus://pair?h=${Uri.encode(host.trim())}&p=${port.trim().ifBlank { "8765" }}&c=${Uri.encode(code.trim())}&f=${fp.trim()}")

    /** Both sides authorize: the code proves physical access to the PC, and the PC owner must still approve on the PC. */
    suspend fun pair(info: PairInfo, phoneId: String, phoneName: String): PairedDevice = withContext(Dispatchers.IO) {
        val client = PinnedTls.client(info.fingerprint, callTimeoutSec = 90)
        val body = JSONObject().put("code", info.code).put("deviceId", phoneId).put("deviceName", phoneName)
        val req = Request.Builder().url("https://${info.host}:${info.port}/pair")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            client.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                when (r.code) {
                    200 -> {
                        val o = JSONObject(s)
                        PairedDevice(
                            id = o.getString("pcId"), name = o.optString("pcName", info.host), category = "windows",
                            host = info.host, port = info.port, certSha256 = info.fingerprint, token = o.getString("token"),
                            pairedAt = System.currentTimeMillis(), lastConnected = System.currentTimeMillis(),
                        )
                    }
                    401 -> throw PairException("Wrong or expired pairing code. Generate a new one on the PC.")
                    403 -> throw PairException("The PC owner declined the pairing.")
                    409 -> throw PairException("The PC is already waiting on another pairing request.")
                    429 -> throw PairException("Too many wrong codes. Generate a new code on the PC.")
                    503 -> throw PairException("Remote control is switched off on the PC.")
                    else -> throw PairException("PC replied with error ${r.code}.")
                }
            }
        } catch (e: PairException) { throw e }
        catch (e: Exception) {
            throw PairException("Couldn't reach the PC at ${info.host}:${info.port} (${e.message}). Same Wi-Fi? Firewall allowed?")
        }
    }
}
