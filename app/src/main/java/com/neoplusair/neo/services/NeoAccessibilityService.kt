
package com.neoplusair.neo.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * OPTIONAL accessibility service. Used ONLY to perform global navigation
 * actions (back / home / recents) when the user explicitly commands it.
 * Disabled by default; nothing is observed or collected.
 */
class NeoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private var instance: NeoAccessibilityService? = null
        fun back(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
        fun home(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
        fun recents(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
    }
}
