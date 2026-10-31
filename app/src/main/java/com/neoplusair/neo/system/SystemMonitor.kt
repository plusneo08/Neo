
package com.neoplusair.neo.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import kotlin.math.abs

/** Phone system integration: battery, memory, storage, network, volume, brightness. */
object SystemMonitor {

    data class Battery(
        val levelPercent: Int, val charging: Boolean,
        val temperatureC: Float, val voltageMv: Int, val health: String
    )

    fun battery(context: Context): Battery {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER-VOLTAGE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
        return Battery(pct, charging, tempC, volts, health)
    }

    fun batterySummary(context: Context): String {
        val b = battery(context)
        val state = if (b.charging) "charging" else "on battery"
        return "Power core at ${b.levelPercent} percent, $state. " +
               "Cell temperature ${"%.1f".format(b.temperatureC)} degrees, " +
               "health ${b.health.lowercase()}."
    }

    fun ram(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem to info.totalMem
    }

    fun storage(): Triple<Long, Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().path)
        val block = stat.blockSizeLong
        val total = stat.blockCountLong * block
        val free = stat.availableBlocksLong * block
        return Triple(total - free, total, free)
    }

    fun memorySummary(context: Context): String {
        val (avail, total) = ram(context)
        val usedPct = ((total - avail) * 100 / total).toInt()
        val (used, totalS, _) = storage()
        val storagePct = (used * 100 / totalS).toInt()
        return "Neural memory at $usedPct percent capacity. " +
               "Archive storage at $storagePct percent utilization, sir."
    }

    fun networkSummary(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
            ?: return "All communication arrays are offline, sir."
        val caps = cm.getNetworkCapabilities(network)
            ?: return "Communication status unknown, sir."
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
        val down = caps.linkDownstreamBandwidthKbps / 1024
        return "Connected via $type uplink at approximately $down megabits, sir."
    }

    fun adjustVolume(context: Context, steps: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        repeat(abs(steps)) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    fun getBrightness(context: Int): Int = 128 // placeholder never used

    fun getBrightness(context: Context): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)

    fun setBrightness(context: Context, value: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        Settings.System.putInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(1, 255))
        return true
    }

    fun summary(context: Context): String {
        val b = battery(context)
        val (avail, total) = ram(context)
        val ramPct = ((total - avail) * 100 / total).toInt()
        val (used, totalS, _) = storage()
        val storPct = (used * 100 / totalS).toInt()
        return buildString {
            append("POWER ......... ${b.levelPercent}% ${if (b.charging) "⚡CHRG" else "BATT"}\n")
            append("NEURAL RAM .... $ramPct% USED\n")
            append("STORAGE ....... $storPct% USED\n")
            append("THERMAL ....... ${"%.1f".format(b.temperatureC)}°C")
        }
    }
}
