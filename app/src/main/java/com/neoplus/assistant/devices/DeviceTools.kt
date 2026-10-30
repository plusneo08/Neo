package com.neoplus.assistant.devices

import com.neoplus.assistant.phone.ActionResult
import com.neoplus.assistant.phone.Status
import com.neoplus.assistant.security.AuditLogger
import org.json.JSONArray
import org.json.JSONObject

/** Tool implementations for paired devices. Same contract as the phone tools: honest status, confirmation for sensitive ones. */
class DeviceTools(
    private val registry: DeviceRegistry,
    private val remote: RemoteCommandManager,
    private val audit: AuditLogger,
) {
    companion object {
        val NAMES = setOf("list_devices", "pc_get_status", "pc_open_app", "pc_media", "pc_set_volume", "pc_lock", "disconnect_device")
    }

    suspend fun execute(name: String, args: JSONObject, confirm: suspend (String) -> Boolean): ActionResult {
        val result = try { run(name, args, confirm) } catch (e: Exception) { ActionResult(Status.FAILED, "Failed: ${e.message}") }
        audit.log(name, args, result.status.name, result.message)
        return result
    }

    private suspend fun run(name: String, args: JSONObject, confirm: suspend (String) -> Boolean): ActionResult {
        if (name == "list_devices") return list()
        val (d, err) = pick(args.optString("device")); if (d == null) return err!!
        return when (name) {
            "pc_get_status" -> remote.send(d, "status", timeoutMs = 15_000)
            "pc_open_app" -> remote.send(d, "open_app", JSONObject().put("app", args.optString("app")))
            "pc_media" -> remote.send(d, "media", JSONObject().put("action", args.optString("action")))
            "pc_set_volume" -> remote.send(d, "volume",
                JSONObject().put("action", args.optString("action")).put("percent", args.optInt("percent", -1)))
            "pc_lock" ->
                if (!confirm("Lock ${d.name}?")) ActionResult(Status.DECLINED, "You declined to lock ${d.name}.")
                else remote.send(d, "lock")
            "disconnect_device" -> {
                remote.disconnect(d.id)
                if (!remote.isConnected(d.id)) ActionResult(Status.VERIFIED, "Disconnected from ${d.name}. It stays paired.")
                else ActionResult(Status.FAILED, "Connection to ${d.name} is still open.")
            }
            else -> ActionResult(Status.UNSUPPORTED, "Unknown tool: $name")
        }
    }

    private fun list(): ActionResult {
        val ds = registry.devices
        if (ds.isEmpty()) return ActionResult(Status.VERIFIED, "No devices are paired yet. Pair one from the Devices tab.")
        val arr = JSONArray(ds.map {
            JSONObject().put("name", it.name).put("category", it.category)
                .put("live_connection", remote.isConnected(it.id)).put("last_connected_ms", it.lastConnected)
        })
        return ActionResult(Status.VERIFIED, "${ds.size} paired device(s). live_connection=false only means no open session right now; the device may still be reachable.",
            JSONObject().put("devices", arr))
    }

    private fun pick(query: String): Pair<PairedDevice?, ActionResult?> {
        val ds = registry.devices
        if (ds.isEmpty()) return null to ActionResult(Status.FAILED, "No devices are paired. Pair one from the Devices tab first.")
        if (query.isBlank()) return if (ds.size == 1) ds[0] to null
            else null to ActionResult(Status.FAILED, "Which device? Paired: ${ds.joinToString { it.name }}.")
        val m = ds.filter { it.name.contains(query, ignoreCase = true) }
        return when {
            m.size == 1 -> m[0] to null
            m.isEmpty() -> null to ActionResult(Status.FAILED, "No paired device matches '$query'. Paired: ${ds.joinToString { it.name }}.")
            else -> null to ActionResult(Status.FAILED, "Several devices match: ${m.joinToString { it.name }}. Which one?")
        }
    }
}
