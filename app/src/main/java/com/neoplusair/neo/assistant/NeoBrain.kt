
package com.neoplusair.neo.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.neoplusair.neo.launcher.InstalledAppsRepository
import com.neoplusair.neo.system.SystemMonitor

/**
 * NEO command processor — parses natural speech into system actions and
 * returns the spoken JARVIS-style reply.
 */
class NeoBrain(
    private val context: Context,
    private val actions: NeoActions
) {
    private val apps by lazy { InstalledAppsRepository(context) }
    private val ai: ConversationalAi = OfflineJarvisBrain()

    fun handleCommand(raw: String): String {
        val text = raw.lowercase().trim()

        return when {
            containsAny(text, "good morning", "good afternoon", "good evening", "hello neo", "hey neo") ->
                JarvisPhrases.greeting()

            text == "help" || text.contains("what can you do") ->
                JarvisPhrases.help()

            text.contains("show") && text.contains("app") -> {
                actions.openTab(1)
                "Displaying all installed applications, sir."
            }

            text.startsWith("open ") || text.startsWith("launch ") || text.startsWith("start ") -> {
                val name = text.removePrefix("open ").removePrefix("launch ")
                    .removePrefix("start ").trim()
                openApp(name)
            }

            text.contains("battery") ->
                SystemMonitor.batterySummary(context)

            text.contains("ram") || text.contains("memory") || text.contains("storage") ->
                SystemMonitor.memorySummary(context)

            text.contains("network") || text.contains("wifi") || text.contains("internet") ->
                SystemMonitor.networkSummary(context)

            (text.contains("system") && text.contains("monitor")) || text.contains("activate system") -> {
                actions.startMonitoringOverlay()
                "System monitoring activated. Deploying holographic interface."
            }

            text.contains("close") || text.contains("go back") -> {
                if (actions.goBack()) "Closing current application, sir."
                else "To close applications I need the accessibility permission, sir. " +
                     "Enable NEO PLUS AIR under Accessibility settings."
            }

            text.contains("go home") || text.contains("home screen") ->
                if (actions.goHome()) "Returning home, sir."
                else "Accessibility permission is not enabled, sir."

            text.contains("recent") ->
                if (actions.openRecents()) "Recents displayed."
                else "Accessibility permission is not enabled, sir."

            text.contains("volume up") || text.contains("increase volume") -> {
                SystemMonitor.adjustVolume(context, +1); "Volume increased, sir."
            }
            text.contains("volume down") || text.contains("decrease volume") -> {
                SystemMonitor.adjustVolume(context, -1); "Volume decreased, sir."
            }

            text.contains("brightness up") || text.contains("increase brightness") -> adjustBrightness(+42)
            text.contains("brightness down") || text.contains("decrease brightness") -> adjustBrightness(-42)

            text.contains("what time") || text.contains("the time") -> {
                val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
                "The time is $time, sir."
            }

            else -> ai.reply(text)
        }
    }

    private fun openApp(name: String): String {
        if (name.isBlank()) return "Which application shall I open, sir?"
        val target = apps.find(name)
            ?: return "I could not find an application named $name, sir."
        context.startActivity(target.launchIntent)
        return "Opening ${target.label}, sir."
    }

    private fun adjustBrightness(delta: Int): String {
        if (!Settings.System.canWrite(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "I require write-settings permission to adjust brightness, sir. " +
                   "The permission screen has been opened."
        }
        val current = SystemMonitor.getBrightness(context)
        val next = (current + delta).coerceIn(1, 255)
        SystemMonitor.setBrightness(context, next)
        return "Brightness adjusted to ${next * 100 / 255} percent, sir."
    }

    private fun containsAny(text: String, vararg keys: String) = keys.any { it in text }
}
