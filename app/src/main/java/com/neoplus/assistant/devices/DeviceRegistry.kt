package com.neoplus.assistant.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neoplus.assistant.security.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** One paired device. [certSha256] is the PC's pinned certificate; [token] is this phone's credential for it. */
data class PairedDevice(
    val id: String, val name: String, val category: String,
    val host: String, val port: Int, val certSha256: String, val token: String,
    val pairedAt: Long, val lastConnected: Long = 0L,
)

/** Authorized devices, stored inside the Keystore-encrypted preferences. */
class DeviceRegistry(private val prefs: Prefs) {
    var devices by mutableStateOf(load()); private set

    private fun load(): List<PairedDevice> = try {
        val arr = JSONArray(prefs.sp.getString("devices", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PairedDevice(o.getString("id"), o.getString("name"), o.optString("category", "windows"),
                o.getString("host"), o.getInt("port"), o.getString("cert"), o.getString("token"),
                o.optLong("pairedAt"), o.optLong("last"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<PairedDevice>) {
        val arr = JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("category", it.category).put("host", it.host)
                .put("port", it.port).put("cert", it.certSha256).put("token", it.token)
                .put("pairedAt", it.pairedAt).put("last", it.lastConnected)
        })
        prefs.sp.edit().putString("devices", arr.toString()).apply()
        devices = list
    }

    fun add(d: PairedDevice) = save(devices.filter { it.id != d.id } + d)
    fun remove(id: String) = save(devices.filter { it.id != id })
    fun touch(id: String) = save(devices.map { if (it.id == id) it.copy(lastConnected = System.currentTimeMillis()) else it })
    fun updateAddress(id: String, host: String, port: Int) =
        save(devices.map { if (it.id == id) it.copy(host = host, port = port) else it })
}
