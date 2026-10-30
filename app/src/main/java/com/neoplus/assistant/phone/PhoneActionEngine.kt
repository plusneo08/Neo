package com.neoplus.assistant.phone

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.neoplus.assistant.security.AuditLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Pipeline per action: supported? -> permission? -> confirm (if sensitive) -> execute -> verify -> honest result.
 * Activities are launched from the app context with NEW_TASK, so the app should be in the foreground.
 */
class PhoneActionEngine(private val ctx: Context, private val audit: AuditLogger) {

    suspend fun execute(name: String, args: JSONObject, confirm: suspend (String) -> Boolean): ActionResult {
        val result = try {
            when (name) {
                "open_app" -> openApp(args.optString("name"))
                "flashlight" -> flashlight(args.optBoolean("on"))
                "get_battery" -> battery()
                "get_storage" -> storage()
                "set_volume" -> volume(args.optString("stream"), args.optInt("percent", -1))
                "set_brightness" -> brightness(args.optInt("percent", -1))
                "set_alarm" -> alarm(args.optInt("hour", -1), args.optInt("minute", -1), args.optString("label"))
                "set_timer" -> timer(args.optInt("seconds", -1))
                "open_settings" -> openSettings(args.optString("page"))
                "call_contact" -> call(args.optString("target"), confirm)
                "send_sms" -> sms(args.optString("target"), args.optString("message"), confirm)
                "lock_screen" -> lock(confirm)
                "navigate" -> navigate(args.optString("destination"))
                "open_camera" -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera opened (not verified).")
                else -> ActionResult(Status.UNSUPPORTED, "Unknown tool: $name")
            }
        } catch (e: SecurityException) {
            ActionResult(Status.NEEDS_PERMISSION, "Android denied this: ${e.message}")
        } catch (e: Exception) {
            ActionResult(Status.FAILED, "Failed: ${e.message}")
        }
        audit.log(name, args, result.status.name, result.message)
        return result
    }

    // ---- helpers
    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun need(p: String, label: String) =
        ActionResult(Status.NEEDS_PERMISSION, "$label permission is not granted. Tap 'Grant permissions' in the app.")

    private fun launch(i: Intent, okMsg: String): ActionResult {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (i.resolveActivity(ctx.packageManager) == null && i.component == null)
            ActionResult(Status.UNSUPPORTED, "No app on this phone can handle that.")
        else { ctx.startActivity(i); ActionResult(Status.HANDED_OFF, okMsg) }
    }

    // ---- actions
    private fun openApp(query: String): ActionResult {
        if (query.isBlank()) return ActionResult(Status.FAILED, "No app name given.")
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(main, 0)
            .filter { it.loadLabel(pm).toString().contains(query, ignoreCase = true) }
            .sortedBy { it.loadLabel(pm).toString().length }
        val m = matches.firstOrNull() ?: return ActionResult(Status.UNSUPPORTED, "No installed app matches '$query'.")
        val i = pm.getLaunchIntentForPackage(m.activityInfo.packageName)
            ?: return ActionResult(Status.FAILED, "Couldn't get a launch intent.")
        return launch(i, "Launched ${m.loadLabel(pm)} (not verified it's in the foreground).")
    }

    private suspend fun flashlight(on: Boolean): ActionResult {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
            return ActionResult(Status.UNSUPPORTED, "This phone has no flashlight.")
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ActionResult(Status.UNSUPPORTED, "No camera with a flash found.")
        val done = CompletableDeferred<Boolean>()
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id && enabled == on) done.complete(true)
            }
        }
        cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        return try {
            cm.setTorchMode(id, on)
            if (withTimeoutOrNull(2000) { done.await() } == true)
                ActionResult(Status.VERIFIED, "Flashlight is ${if (on) "on" else "off"}.")
            else ActionResult(Status.FAILED, "Asked for flashlight ${if (on) "on" else "off"} but Android never reported the change (camera may be in use).")
        } catch (e: CameraAccessException) {
            ActionResult(Status.FAILED, "Camera unavailable: ${e.message}")
        } finally { cm.unregisterTorchCallback(cb) }
    }

    private fun battery(): ActionResult {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ActionResult(Status.FAILED, "Battery info unavailable.")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else return ActionResult(Status.FAILED, "Battery level unreadable.")
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return ActionResult(Status.VERIFIED, "Battery is $pct%${if (charging) " and charging" else ""}.",
            JSONObject().put("percent", pct).put("charging", charging))
    }

    private fun storage(): ActionResult {
        val s = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024
        val free = s.availableBytes / gb; val total = s.totalBytes / gb
        return ActionResult(Status.VERIFIED, "%.1f GB free of %.1f GB.".format(free, total),
            JSONObject().put("free_gb", free).put("total_gb", total))
    }

    private fun volume(stream: String, percent: Int): ActionResult {
        if (percent !in 0..100) return ActionResult(Status.FAILED, "Percent must be 0-100.")
        val type = when (stream) {
            "music" -> AudioManager.STREAM_MUSIC; "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> return ActionResult(Status.FAILED, "Unknown stream '$stream'.")
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        val target = (percent / 100f * am.getStreamMaxVolume(type)).roundToInt()
        am.setStreamVolume(type, target, 0)
        val actual = am.getStreamVolume(type)
        return if (actual == target) ActionResult(Status.VERIFIED, "$stream volume set to $percent%.")
        else ActionResult(Status.FAILED, "Requested $target but Android reports $actual (Do Not Disturb may block it).")
    }

    private fun brightness(percent: Int): ActionResult {
        if (percent !in 1..100) return ActionResult(Status.FAILED, "Percent must be 1-100.")
        if (!Settings.System.canWrite(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ActionResult(Status.NEEDS_PERMISSION, "Opened the 'Modify system settings' screen. Allow NEO PLUS, then ask again.")
        }
        val value = (percent / 100f * 255).roundToInt().coerceIn(1, 255)
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
        return if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) == value)
            ActionResult(Status.VERIFIED, "Brightness set to $percent%.")
        else ActionResult(Status.FAILED, "Brightness setting didn't change.")
    }

    private fun alarm(h: Int, m: Int, label: String): ActionResult {
        if (h !in 0..23 || m !in 0..59) return ActionResult(Status.FAILED, "Invalid time.")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return launch(i, "Alarm for %02d:%02d sent to the Clock app (not verified; check the Clock app).".format(h, m))
    }

    private fun timer(sec: Int): ActionResult {
        if (sec !in 1..86400) return ActionResult(Status.FAILED, "Timer must be 1-86400 seconds.")
        return launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Timer for $sec s sent to the Clock app (not verified).")
    }

    private fun openSettings(page: String): ActionResult {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS; "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY; "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS; "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> return ActionResult(Status.UNSUPPORTED, "Unknown settings page '$page'.")
        }
        val i = Intent(action)
        if (page == "notifications") i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        return launch(i, "Opened $page settings. Android doesn't let apps toggle this directly; switch it there.")
    }

    private fun navigate(dest: String): ActionResult {
        if (dest.isBlank()) return ActionResult(Status.FAILED, "No destination given.")
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))), "Navigation to $dest opened (not verified).")
    }

    // ---- contacts / call / sms
    private data class Match(val name: String, val number: String)

    private fun resolve(target: String): Pair<Match?, ActionResult?> {
        if (Regex("^[+\\d\\s\\-()]{3,}$").matches(target.trim()))
            return Match(target.trim(), target.filter { it.isDigit() || it == '+' }) to null
        if (!has(Manifest.permission.READ_CONTACTS)) return null to need(Manifest.permission.READ_CONTACTS, "Contacts")
        val found = mutableListOf<Match>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$target%"), null
        )?.use { c -> while (c.moveToNext()) found += Match(c.getString(0), c.getString(1)) }
        val names = found.map { it.name }.distinct()
        return when {
            names.isEmpty() -> null to ActionResult(Status.FAILED, "No contact matches '$target'.")
            names.size > 1 -> null to ActionResult(Status.FAILED, "Several contacts match: ${names.take(5).joinToString()}. Which one?")
            else -> found.first() to null
        }
    }

    private suspend fun call(target: String, confirm: suspend (String) -> Boolean): ActionResult {
        val (m, err) = resolve(target); if (m == null) return err!!
        if (!confirm("Call ${m.name} (${m.number})?")) return ActionResult(Status.DECLINED, "You declined the call.")
        val uri = Uri.parse("tel:" + Uri.encode(m.number))
        return if (has(Manifest.permission.CALL_PHONE))
            launch(Intent(Intent.ACTION_CALL, uri), "Dialing ${m.name} (not verified the call connected).")
        else launch(Intent(Intent.ACTION_DIAL, uri), "CALL_PHONE not granted, so I opened the dialer for ${m.name}. Press call to proceed.")
    }

    private suspend fun sms(target: String, body: String, confirm: suspend (String) -> Boolean): ActionResult {
        if (body.isBlank()) return ActionResult(Status.FAILED, "Message is empty.")
        if (!has(Manifest.permission.SEND_SMS)) return need(Manifest.permission.SEND_SMS, "SMS")
        val (m, err) = resolve(target); if (m == null) return err!!
        if (!confirm("Send SMS to ${m.name} (${m.number}):\n\n\"$body\"")) return ActionResult(Status.DECLINED, "You declined to send it.")

        val sms = ctx.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(body)
        val action = "com.neoplus.SMS_SENT_" + System.nanoTime()
        val codes = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { codes += resultCode; if (codes.size >= parts.size) done.complete(Unit) }
        }
        ContextCompat.registerReceiver(ctx, rcv, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            sms.sendMultipartTextMessage(m.number, null, parts, ArrayList(parts.map { pi }), null)
            if (withTimeoutOrNull(15000) { done.await() } == null)
                ActionResult(Status.FAILED, "No send report from Android within 15 s; treat the SMS as not sent.")
            else if (codes.all { it == Activity.RESULT_OK })
                ActionResult(Status.VERIFIED, "SMS to ${m.name} was sent by the phone. Delivery to their handset is not confirmed.")
            else ActionResult(Status.FAILED, "Android reported an SMS send error (code ${codes.first { it != Activity.RESULT_OK }}). Check credit/SIM/signal.")
        } finally { ctx.unregisterReceiver(rcv) }
    }

    private suspend fun lock(confirm: suspend (String) -> Boolean): ActionResult {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(ctx, NeoAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            ctx.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets NEO PLUS lock the screen. Nothing else.")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return ActionResult(Status.NEEDS_PERMISSION, "Opened Device Admin activation. Enable it, then ask again.")
        }
        if (!confirm("Lock the screen now?")) return ActionResult(Status.DECLINED, "You declined.")
        dpm.lockNow()
        return ActionResult(Status.HANDED_OFF, "Lock requested. Android gives no callback, so I can't verify it.")
    }
}
