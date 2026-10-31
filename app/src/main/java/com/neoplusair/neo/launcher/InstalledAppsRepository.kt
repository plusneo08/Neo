
package com.neoplusair.neo.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val launchIntent: Intent
)

/** Reads the installed launcher activities and launches them on demand. */
class InstalledAppsRepository(context: Context) {

    private val pm: PackageManager = context.packageManager

    val apps: List<AppInfo> = load()

    private fun load(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { resolve ->
                AppInfo(
                    label = resolve.loadLabel(pm).toString(),
                    packageName = resolve.activityInfo.packageName,
                    icon = resolve.loadIcon(pm),
                    launchIntent = pm.getLaunchIntentForPackage(resolve.activityInfo.packageName)!!
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun find(query: String): AppInfo? {
        val q = query.lowercase()
        return apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
    }
}
