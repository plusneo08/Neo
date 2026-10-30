package com.neoplus.assistant.phone

import org.json.JSONObject

/** Only VERIFIED means we observed the effect. HANDED_OFF = passed to Android/another app, outcome unknown. */
enum class Status { VERIFIED, HANDED_OFF, NEEDS_PERMISSION, UNSUPPORTED, DECLINED, FAILED }

data class ActionResult(val status: Status, val message: String, val data: JSONObject = JSONObject()) {
    fun toJson(): JSONObject = JSONObject().put("status", status.name).put("message", message).put("data", data)
}
