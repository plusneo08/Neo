package com.neoplus.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** Declarations Gemini may call. Only these exist; the model can never run arbitrary code or shell. */
object ToolRegistry {
    private fun tool(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).apply {
            if (props.isNotEmpty()) put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } })
                .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
        }

    private fun s(d: String, vararg enum: String) = JSONObject().put("type", "string").put("description", d)
        .also { if (enum.isNotEmpty()) it.put("enum", JSONArray(enum.toList())) }
    private fun i(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun b(d: String) = JSONObject().put("type", "boolean").put("description", d)

    private val DEV get() = mapOf("device" to s("Device name; optional when only one device is paired"))

    val all: List<JSONObject> = listOf(
        tool("open_app", "Open an installed app by its name.", mapOf("name" to s("App name, e.g. YouTube")), listOf("name")),
        tool("flashlight", "Turn the flashlight on or off.", mapOf("on" to b("true = on")), listOf("on")),
        tool("get_battery", "Read the real battery level and charging state."),
        tool("get_storage", "Read internal storage free and total space."),
        tool("set_volume", "Set a volume stream to a percentage.",
            mapOf("stream" to s("Which stream", "music", "ring", "alarm", "notification"), "percent" to i("0-100")),
            listOf("stream", "percent")),
        tool("set_brightness", "Set screen brightness percentage (needs Modify System Settings).",
            mapOf("percent" to i("1-100")), listOf("percent")),
        tool("set_alarm", "Create an alarm at a 24h time.",
            mapOf("hour" to i("0-23"), "minute" to i("0-59"), "label" to s("Optional label")), listOf("hour", "minute")),
        tool("set_timer", "Start a countdown timer.", mapOf("seconds" to i("Duration in seconds")), listOf("seconds")),
        tool("open_settings", "Open an Android settings page (use for Wi-Fi/Bluetooth, which apps cannot toggle directly).",
            mapOf("page" to s("Settings page", "wifi", "bluetooth", "display", "sound", "battery", "location", "notifications", "apps")),
            listOf("page")),
        tool("call_contact", "Place a phone call to a contact name or number. Requires user confirmation.",
            mapOf("target" to s("Contact name or phone number")), listOf("target")),
        tool("send_sms", "Send an SMS to a contact name or number. Requires user confirmation.",
            mapOf("target" to s("Contact name or phone number"), "message" to s("Message text")), listOf("target", "message")),
        tool("lock_screen", "Lock the phone screen. Requires confirmation and Device Admin enabled."),
        tool("navigate", "Start navigation to a destination in the maps app.",
            mapOf("destination" to s("Address or place")), listOf("destination")),
        tool("open_camera", "Open the camera app."),
        // ---- paired devices (Windows PC companion)
        tool("list_devices", "List paired devices (e.g. Windows PCs) and whether each is currently connected."),
        tool("pc_get_status", "Read CPU, RAM, disk and battery from a paired Windows PC.", DEV),
        tool("pc_open_app", "Open an app on a paired Windows PC. Only apps the PC owner allow-listed will open.",
            DEV + mapOf("app" to s("App name, e.g. chrome")), listOf("app")),
        tool("pc_media", "Control media playback on a paired Windows PC.",
            DEV + mapOf("action" to s("Media action", "play_pause", "next", "previous", "stop")), listOf("action")),
        tool("pc_set_volume", "Set or mute the system volume on a paired Windows PC.",
            DEV + mapOf("action" to s("What to do", "set", "mute", "unmute"), "percent" to i("0-100, only for set")), listOf("action")),
        tool("pc_lock", "Lock a paired Windows PC. Requires user confirmation.", DEV),
        tool("disconnect_device", "Close the live connection to a paired device (it stays paired).", DEV),
    )
}
